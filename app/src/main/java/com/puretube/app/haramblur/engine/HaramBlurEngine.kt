package com.puretube.app.haramblur.engine

import android.content.Context
import android.graphics.Bitmap
import com.puretube.app.data.local.datastore.BlurTargetGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device vision coordinator. All inference runs 100% locally in memory with
 * TensorFlow Lite; zero frames or bitmaps are ever written to storage or sent
 * over the network.
 *
 * Model files are expected in assets/models/:
 *  - blaze_face_short_range.tflite
 *  - gender_attr_classifier_int8.tflite
 * When the model assets are absent the engine degrades gracefully and returns
 * no regions instead of crashing.
 */
class HaramBlurEngine(private val context: Context) {

    private val interpreterMutex = Mutex()
    private var gpuDelegate: GpuDelegate? = null
    private var faceDetectorInterpreter: Interpreter? = null
    private var genderAttributeInterpreter: Interpreter? = null
    private var initAttempted = false

    private val cropBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * 96 * 96 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }

    /** Returns true when both models loaded. Never throws. */
    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (faceDetectorInterpreter != null) return@withContext true
        interpreterMutex.withLock {
            if (faceDetectorInterpreter != null) return@withLock true
            if (initAttempted) return@withLock faceDetectorInterpreter != null
            initAttempted = true
            runCatching {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                    runCatching {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                    }
                }
                faceDetectorInterpreter =
                    Interpreter(loadModelMapped("models/blaze_face_short_range.tflite"), options)
                genderAttributeInterpreter = runCatching {
                    Interpreter(loadModelMapped("models/gender_attr_classifier_int8.tflite"), options)
                }.getOrNull()
            }.isSuccess && faceDetectorInterpreter != null
        }
    }

    val isModelAvailable: Boolean get() = faceDetectorInterpreter != null

    suspend fun detectRegionsToBlur(
        sourceBitmap: Bitmap,
        targetGender: BlurTargetGender,
        sensitivity: Float
    ): List<DetectedBlurRegion> = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext emptyList()
        val threshold = (1.0f - sensitivity).coerceIn(0.15f, 0.75f)
        val rawFaces = interpreterMutex.withLock {
            runCatching { runFaceAndUpperBodyDetection(sourceBitmap, threshold) }
                .getOrDefault(emptyList())
        }
        if (rawFaces.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val matchedRegions = mutableListOf<DetectedBlurRegion>()

        for ((index, candidateRect) in rawFaces.withIndex()) {
            if (targetGender == BlurTargetGender.BOTH_ALL_PEOPLE) {
                matchedRegions.add(
                    DetectedBlurRegion(
                        id = index,
                        normalizedRect = BlurBoxMath.expandForHairAndShoulders(candidateRect),
                        classifiedGender = ClassifiedSubject.UNKNOWN_PERSON,
                        confidence = 0.95f,
                        timestampMs = now
                    )
                )
                continue
            }
            val subject = interpreterMutex.withLock {
                runCatching { classifyCropGender(sourceBitmap, candidateRect, threshold) }
                    .getOrDefault(ClassifiedSubject.UNKNOWN_PERSON)
            }
            if (shouldBlur(targetGender, subject, sensitivity)) {
                matchedRegions.add(
                    DetectedBlurRegion(
                        id = index,
                        normalizedRect = BlurBoxMath.expandForHairAndShoulders(candidateRect),
                        classifiedGender = subject,
                        confidence = 0.90f,
                        timestampMs = now
                    )
                )
            }
        }
        matchedRegions
    }

    companion object {
        /**
         * Gender-target filter. At strict sensitivity (>= 0.75) an UNKNOWN subject
         * is blurred under FEMALE_ONLY, so an uncertain face never slips through.
         */
        fun shouldBlur(
            targetGender: BlurTargetGender,
            subject: ClassifiedSubject,
            sensitivity: Float
        ): Boolean = when (targetGender) {
            BlurTargetGender.FEMALE_ONLY -> subject == ClassifiedSubject.FEMALE ||
                (sensitivity >= 0.75f && subject == ClassifiedSubject.UNKNOWN_PERSON)
            BlurTargetGender.MALE_ONLY -> subject == ClassifiedSubject.MALE
            BlurTargetGender.BOTH_ALL_PEOPLE -> true
        }
    }

    private fun loadModelMapped(assetPath: String): ByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun runFaceAndUpperBodyDetection(bitmap: Bitmap, minScore: Float): List<NormalizedRect> {
        val interpreter = faceDetectorInterpreter ?: return emptyList()
        val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, false)
        val input = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(128 * 128)
        scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)
        for (px in pixels) {
            input.putFloat(((px shr 16 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((px shr 8 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((px and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val regressors = Array(1) { Array(896) { FloatArray(16) } }
        val classifiers = Array(1) { Array(896) { FloatArray(1) } }
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(0 to regressors, 1 to classifiers)
        )
        val detected = mutableListOf<NormalizedRect>()
        for (i in 0 until 896) {
            val score = 1f / (1f + kotlin.math.exp(-classifiers[0][i][0]))
            if (score >= minScore) {
                val dy = regressors[0][i][0] / 128f
                val dx = regressors[0][i][1] / 128f
                val h = (regressors[0][i][2] / 128f).coerceIn(0.05f, 1f)
                val w = (regressors[0][i][3] / 128f).coerceIn(0.05f, 1f)
                val cx = ((i % 16) / 16f + dx).coerceIn(0f, 1f)
                val cy = ((i / 16) / 56f + dy).coerceIn(0f, 1f)
                detected.add(
                    NormalizedRect(
                        (cx - w / 2f).coerceIn(0f, 1f),
                        (cy - h / 2f).coerceIn(0f, 1f),
                        (cx + w / 2f).coerceIn(0f, 1f),
                        (cy + h / 2f).coerceIn(0f, 1f)
                    )
                )
            }
        }
        return BlurBoxMath.nonMaxSuppression(detected, iouThreshold = 0.35f)
    }

    private fun classifyCropGender(
        source: Bitmap,
        normRect: NormalizedRect,
        threshold: Float
    ): ClassifiedSubject {
        val interpreter = genderAttributeInterpreter ?: return ClassifiedSubject.UNKNOWN_PERSON
        val left = (normRect.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (normRect.top * source.height).toInt().coerceIn(0, source.height - 1)
        val width = ((normRect.width * source.width).toInt()).coerceIn(1, source.width - left)
        val height = ((normRect.height * source.height).toInt()).coerceIn(1, source.height - top)

        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val resized = Bitmap.createScaledBitmap(crop, 96, 96, false)
        cropBuffer.rewind()
        val pxArray = IntArray(96 * 96)
        resized.getPixels(pxArray, 0, 96, 0, 0, 96, 96)
        for (px in pxArray) {
            cropBuffer.putFloat((px shr 16 and 0xFF) / 255f)
            cropBuffer.putFloat((px shr 8 and 0xFF) / 255f)
            cropBuffer.putFloat((px and 0xFF) / 255f)
        }
        cropBuffer.rewind()
        val output = Array(1) { FloatArray(2) }
        interpreter.run(cropBuffer, output)
        val femaleProb = output[0][0]
        val maleProb = output[0][1]
        return when {
            femaleProb >= threshold && femaleProb >= maleProb -> ClassifiedSubject.FEMALE
            maleProb >= threshold && maleProb > femaleProb -> ClassifiedSubject.MALE
            else -> ClassifiedSubject.UNKNOWN_PERSON
        }
    }
}
