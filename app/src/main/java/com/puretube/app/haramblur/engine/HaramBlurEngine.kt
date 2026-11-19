package com.puretube.app.haramblur.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.domain.BlazeFaceAnchors
import com.puretube.app.domain.HaramBlurPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Observable HaramBlur engine state for the player pill + Settings block. */
data class HaramBlurStatus(
    val faceModelLoaded: Boolean = false,
    val genderModelLoaded: Boolean = false,
    val mode: String = "Face model not loaded",
    val lastError: String? = null
)

/**
 * On-device vision coordinator. All inference runs 100% locally in memory with
 * TensorFlow Lite; zero frames or bitmaps are ever written to storage or sent
 * over the network.
 *
 * Bundled model (v1.3): the official MediaPipe BlazeFace short-range
 * float16 face detector in assets/models/blaze_face_short_range.tflite
 * (input [1,128,128,3] float normalized to [-1,1]; outputs regressors
 * [1,896,16] + classifiers [1,896,1]; decode via [BlazeFaceAnchors]).
 *
 * There is NO gender model bundled. Until a gender_attr_classifier_int8
 * .tflite is dropped into assets/models/, [HaramBlurPolicy] applies the
 * all-faces fallback: every detected face is blurred regardless of the
 * Who-to-Blur setting, and the UI says so.
 *
 * The engine never throws: failures land in [statusFlow] as state.
 */
class HaramBlurEngine(private val context: Context) {

    private val interpreterMutex = Mutex()
    private var gpuDelegate: GpuDelegate? = null
    private var faceDetectorInterpreter: Interpreter? = null
    private var genderAttributeInterpreter: Interpreter? = null
    private var initAttempted = false

    private val _status = MutableStateFlow(HaramBlurStatus())
    val statusFlow: StateFlow<HaramBlurStatus> = _status

    /** True once the gender interpreter actually loaded (not just attempted). */
    val hasGenderModel: Boolean get() = genderAttributeInterpreter != null

    val isModelAvailable: Boolean get() = faceDetectorInterpreter != null

    private val cropBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * 96 * 96 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }

    private fun publishStatus(error: String? = _status.value.lastError) {
        val faceLoaded = faceDetectorInterpreter != null
        _status.value = HaramBlurStatus(
            faceModelLoaded = faceLoaded,
            genderModelLoaded = hasGenderModel,
            mode = HaramBlurPolicy.modeLabel(
                requested = BlurTargetGender.FEMALE_ONLY,
                faceModelLoaded = faceLoaded,
                hasGenderModel = hasGenderModel
            ),
            lastError = error
        )
    }

    /** Loads the face model (and gender model if present). Never throws. */
    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (faceDetectorInterpreter != null) return@withContext true
        interpreterMutex.withLock {
            if (faceDetectorInterpreter != null) return@withLock true
            if (initAttempted) return@withLock faceDetectorInterpreter != null
            initAttempted = true
            val result = runCatching {
                faceDetectorInterpreter = loadInterpreter("models/blaze_face_short_range.tflite")
                genderAttributeInterpreter = runCatching {
                    loadInterpreter("models/gender_attr_classifier_int8.tflite")
                }.getOrNull()
            }
            publishStatus(error = result.exceptionOrNull()?.let {
                "${it::class.simpleName}: ${it.message ?: "model load failed"}"
            })
            result.isSuccess && faceDetectorInterpreter != null
        }
    }

    /** GPU delegate first, CPU fallback — a broken GPU path must not kill detection. */
    private fun loadInterpreter(assetPath: String): Interpreter {
        val mapped = loadModelMapped(assetPath)
        val gpuOptions = Interpreter.Options().apply {
            setNumThreads(2)
            runCatching {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            }
        }
        return runCatching { Interpreter(mapped, gpuOptions) }
            .getOrElse { Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) }) }
    }

    suspend fun detectRegionsToBlur(
        sourceBitmap: Bitmap,
        targetGender: BlurTargetGender,
        sensitivity: Float
    ): List<DetectedBlurRegion> = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext emptyList()
        val threshold = (1.0f - sensitivity).coerceIn(0.15f, 0.75f)
        val rawFaces = interpreterMutex.withLock {
            runCatching { runFaceDetection(sourceBitmap, threshold) }
                .getOrDefault(emptyList())
        }
        if (rawFaces.isEmpty()) return@withContext emptyList()

        // v1.3 rule: no gender model on device -> blur ALL detected faces,
        // whatever Who-to-Blur says. With a gender model, the requested
        // target applies as before.
        val effectiveTarget = HaramBlurPolicy.effectiveTarget(targetGender, hasGenderModel)

        val now = System.currentTimeMillis()
        val matchedRegions = mutableListOf<DetectedBlurRegion>()

        for ((index, candidateRect) in rawFaces.withIndex()) {
            if (effectiveTarget == BlurTargetGender.BOTH_ALL_PEOPLE) {
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
            if (shouldBlur(effectiveTarget, subject, sensitivity)) {
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

    /**
     * "Test detection" (Settings): runs the engine on the bundled
     * public-domain test portrait and reports the region count or the
     * error. Never throws.
     */
    suspend fun runTestDetection(): String = withContext(Dispatchers.Default) {
        runCatching {
            if (!ensureInitialized()) {
                return@withContext "Face model failed to load: " +
                    (_status.value.lastError ?: "unknown error")
            }
            val bitmap = context.assets.open(TEST_IMAGE_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext "Test image could not be decoded"
            val regions = detectRegionsToBlur(
                sourceBitmap = bitmap,
                targetGender = BlurTargetGender.BOTH_ALL_PEOPLE,
                sensitivity = 0.65f
            )
            "${regions.size} face region(s) detected"
        }.getOrElse { e -> "Test detection failed: ${e::class.simpleName}: ${e.message ?: ""}" }
    }

    companion object {
        const val TEST_IMAGE_ASSET = "test/face_test.jpg"

        /**
         * Gender-target filter. At strict sensitivity (>= 0.75) an UNKNOWN subject
         * is blurred under FEMALE_ONLY, so an uncertain face never slips through.
         * (Only reached when a gender model is present; see HaramBlurPolicy.)
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

    private fun runFaceDetection(bitmap: Bitmap, minScore: Float): List<NormalizedRect> {
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
        val flatRegressors = FloatArray(896 * 16)
        val flatLogits = FloatArray(896)
        for (i in 0 until 896) {
            System.arraycopy(regressors[0][i], 0, flatRegressors, i * 16, 16)
            flatLogits[i] = classifiers[0][i][0]
        }
        return BlazeFaceAnchors.decode(flatRegressors, flatLogits, minScore)
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
