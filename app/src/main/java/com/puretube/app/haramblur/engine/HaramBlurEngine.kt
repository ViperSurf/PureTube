package com.puretube.app.haramblur.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.puretube.app.data.local.datastore.BlurScope
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.domain.BlazeFaceAnchors
import com.puretube.app.domain.BlurScopeMath
import com.puretube.app.domain.HaramBlurPolicy
import com.puretube.app.domain.TileMath
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
 * Bundled models:
 * - Face (v1.3): the official MediaPipe BlazeFace short-range float16
 *   detector in assets/models/blaze_face_short_range.tflite (input
 *   [1,128,128,3] float normalized to [-1,1]; outputs regressors
 *   [1,896,16] + classifiers [1,896,1]; decode via [BlazeFaceAnchors]).
 * - Gender (v1.5): assets/models/model_gender_q.tflite, from the MIT
 *   project shubham0204/Age-Gender_Estimation_TF-Android (trained on
 *   UTKFace; host-verified with LiteRT before bundling — see
 *   assets/models/README.txt). Input [1,128,128,3] float normalized to
 *   [0,1]; output [1,2] probabilities ordered [male, female]. It expects
 *   a TIGHT face crop, so classification uses the detected face box with
 *   only a small margin. With it loaded, Who-to-Blur is a real
 *   classification; if it ever fails to load, [HaramBlurPolicy] applies
 *   the all-faces fallback and the UI says so.
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

    /** Gender model input buffer (128x128 RGB float) — sized for the real model. */
    private val cropBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * GENDER_INPUT_SIZE * GENDER_INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

    /**
     * How the most recent [detectRegionsToBlur] call found its faces:
     * "full", "tiled", "upscale" or "none". Read by the video sampler for
     * its diagnostics line.
     */
    @Volatile
    var lastPassType: String = "none"
        private set

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
                    loadInterpreter(GENDER_MODEL_ASSET)
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

    /**
     * Detects the regions to blur in [sourceBitmap].
     *
     * Detection passes, in order (v1.5 small-face work):
     * 1. Full-frame BlazeFace pass.
     * 2. If empty and [allowTiledRetry]: a TILED pass over 2x2 overlapping
     *    quadrants ([TileMath]) — small/distant faces occupy far more of
     *    the model's input inside a tile. Mapped back + NMS-merged.
     * 3. If still empty, [allowTiledRetry] and the source is small
     *    (< 256 px wide, e.g. a feed thumbnail): one full pass on a 2x
     *    upscale of the source.
     * [lastPassType] records which pass produced the faces.
     *
     * Each face box is then expanded per [blurScope] (face/head only, or
     * the approximated person region) and filtered by Who-to-Blur: with
     * the gender model loaded that is a real classification of the TIGHT
     * face crop; without it, the all-faces fallback applies.
     */
    suspend fun detectRegionsToBlur(
        sourceBitmap: Bitmap,
        targetGender: BlurTargetGender,
        sensitivity: Float,
        blurScope: BlurScope = BlurScope.FACE_ONLY,
        allowTiledRetry: Boolean = true
    ): List<DetectedBlurRegion> = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext emptyList()
        val threshold = (1.0f - sensitivity).coerceIn(0.15f, 0.75f)
        var rawFaces = interpreterMutex.withLock {
            runCatching { runFaceDetection(sourceBitmap, threshold) }
                .getOrDefault(emptyList())
        }
        lastPassType = if (rawFaces.isNotEmpty()) "full" else "none"

        if (rawFaces.isEmpty() && allowTiledRetry) {
            val tiled = interpreterMutex.withLock {
                runCatching { runTiledFaceDetection(sourceBitmap, threshold) }
                    .getOrDefault(emptyList())
            }
            if (tiled.isNotEmpty()) {
                rawFaces = tiled
                lastPassType = "tiled"
            }
        }
        if (rawFaces.isEmpty() && allowTiledRetry && sourceBitmap.width < 256) {
            val upscaled = interpreterMutex.withLock {
                runCatching {
                    val big = Bitmap.createScaledBitmap(
                        sourceBitmap, sourceBitmap.width * 2, sourceBitmap.height * 2, true
                    )
                    runFaceDetection(big, threshold)
                }.getOrDefault(emptyList())
            }
            if (upscaled.isNotEmpty()) {
                rawFaces = upscaled
                lastPassType = "upscale"
            }
        }
        if (rawFaces.isEmpty()) return@withContext emptyList()

        // Gender model loaded -> real classification; otherwise the
        // all-faces fallback (v1.3 rule, unchanged).
        val effectiveTarget = HaramBlurPolicy.effectiveTarget(targetGender, hasGenderModel)

        val now = System.currentTimeMillis()
        val matchedRegions = mutableListOf<DetectedBlurRegion>()

        for ((index, candidateRect) in rawFaces.withIndex()) {
            val scopedRect = BlurScopeMath.expand(
                BlurBoxMath.expandFaceBox(candidateRect), blurScope
            )
            if (effectiveTarget == BlurTargetGender.BOTH_ALL_PEOPLE) {
                matchedRegions.add(
                    DetectedBlurRegion(
                        id = index,
                        normalizedRect = scopedRect,
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
                        normalizedRect = scopedRect,
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
     * Tiled face detection: runs the detector on each of the 2x2
     * overlapping quadrant crops of [bitmap] and maps the boxes back to
     * full-frame coordinates, NMS-merged. Caller holds the interpreter
     * mutex. Never throws (per-tile failures are skipped).
     */
    private fun runTiledFaceDetection(bitmap: Bitmap, minScore: Float): List<NormalizedRect> {
        if (bitmap.width < 32 || bitmap.height < 32) return emptyList()
        val found = mutableListOf<NormalizedRect>()
        for (tile in TileMath.tiles()) {
            val left = (tile.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (tile.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val w = (tile.width * bitmap.width).toInt().coerceIn(1, bitmap.width - left)
            val h = (tile.height * bitmap.height).toInt().coerceIn(1, bitmap.height - top)
            val crop = runCatching { Bitmap.createBitmap(bitmap, left, top, w, h) }.getOrNull()
                ?: continue
            val localFaces = runCatching { runFaceDetection(crop, minScore) }
                .getOrDefault(emptyList())
            localFaces.forEach { found.add(TileMath.mapToFullFrame(tile, it)) }
        }
        return BlurBoxMath.nonMaxSuppression(
            found.map { it to 0.5f }, iouThreshold = 0.35f
        )
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
            val genderNote = if (hasGenderModel) {
                // The bundled test portrait is a man: a working gender model
                // blurs 0 regions of it under the Female-only target.
                val femaleOnly = detectRegionsToBlur(
                    sourceBitmap = bitmap,
                    targetGender = BlurTargetGender.FEMALE_ONLY,
                    sensitivity = 0.65f
                )
                " Gender model active: ${femaleOnly.size} region(s) blurred under " +
                    "Female-only on this (male) portrait — 0 means it classified him male."
            } else {
                " No gender model — all-faces fallback."
            }
            "${regions.size} face region(s) detected.$genderNote"
        }.getOrElse { e -> "Test detection failed: ${e::class.simpleName}: ${e.message ?: ""}" }
    }

    companion object {
        const val TEST_IMAGE_ASSET = "test/face_test.jpg"

        /** Bundled gender classifier (v1.5) — provenance in assets/models/README.txt. */
        const val GENDER_MODEL_ASSET = "models/model_gender_q.tflite"

        /** The real gender model's input edge (128), host-verified. */
        const val GENDER_INPUT_SIZE = 128

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

    /**
     * Classifies the crop inside [normRect] with the bundled gender model.
     * Caller must hold the interpreter mutex (input buffers are shared).
     *
     * Real model contract (host-verified, see assets/models/README.txt):
     * input [1,128,128,3] float normalized to [0,1]; output [1,2]
     * probabilities ordered [0]=male, [1]=female. The model was trained
     * on TIGHT UTKFace-style face crops, so the crop is the detected face
     * box with only a ~8% margin — background-heavy crops measurably
     * mislead it. Returns UNKNOWN_PERSON when there is no interpreter or
     * the winning probability is below [threshold].
     */
    private fun classifyCropGender(
        source: Bitmap,
        normRect: NormalizedRect,
        threshold: Float
    ): ClassifiedSubject {
        val interpreter = genderAttributeInterpreter ?: return ClassifiedSubject.UNKNOWN_PERSON
        val marginX = normRect.width * 0.08f
        val marginY = normRect.height * 0.08f
        val left = ((normRect.left - marginX).coerceAtLeast(0f) * source.width).toInt()
        val top = ((normRect.top - marginY).coerceAtLeast(0f) * source.height).toInt()
        val right = ((normRect.right + marginX).coerceAtMost(1f) * source.width).toInt()
        val bottom = ((normRect.bottom + marginY).coerceAtMost(1f) * source.height).toInt()
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val resized = Bitmap.createScaledBitmap(crop, GENDER_INPUT_SIZE, GENDER_INPUT_SIZE, true)
        cropBuffer.rewind()
        val pxArray = IntArray(GENDER_INPUT_SIZE * GENDER_INPUT_SIZE)
        resized.getPixels(pxArray, 0, GENDER_INPUT_SIZE, 0, 0, GENDER_INPUT_SIZE, GENDER_INPUT_SIZE)
        for (px in pxArray) {
            cropBuffer.putFloat((px shr 16 and 0xFF) / 255f)
            cropBuffer.putFloat((px shr 8 and 0xFF) / 255f)
            cropBuffer.putFloat((px and 0xFF) / 255f)
        }
        cropBuffer.rewind()
        val output = Array(1) { FloatArray(2) }
        interpreter.run(cropBuffer, output)
        // Real model output order: [0] = male probability, [1] = female probability.
        val maleProb = output[0][0]
        val femaleProb = output[0][1]
        return when {
            femaleProb >= threshold && femaleProb >= maleProb -> ClassifiedSubject.FEMALE
            maleProb >= threshold && maleProb > femaleProb -> ClassifiedSubject.MALE
            else -> ClassifiedSubject.UNKNOWN_PERSON
        }
    }
}
