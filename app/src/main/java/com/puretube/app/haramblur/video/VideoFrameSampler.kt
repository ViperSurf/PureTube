package com.puretube.app.haramblur.video

import android.graphics.Bitmap
import android.view.TextureView
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.datastore.BlurDetectionMode
import com.puretube.app.data.local.datastore.BlurStrength
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
import com.puretube.app.domain.BlurStrengthMath
import com.puretube.app.domain.FullScreenBlurHold
import com.puretube.app.haramblur.engine.BoundingBoxTracker
import com.puretube.app.haramblur.engine.DetectedBlurRegion
import com.puretube.app.haramblur.engine.HaramBlurEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Sampler diagnostics, surfaced in Settings near the HaramBlur status
 * block. v2.1 adds the sequence pair — [sampledSequence] counts every
 * published frame snapshot, [drawnSequence] is the newest sequence the
 * overlay actually drew. During healthy playback both climb together at
 * the sampler cadence; a freeze is now visible as numbers (sampled
 * advancing / drawn stuck, or both stuck with a growing sample age).
 */
data class SamplerDiagnostics(
    val framesSampled: Long = 0L,
    val lastFrameBrightness: Int = -1,
    val lastDetectionCount: Int = -1,
    val lastError: String? = null,
    /** Which detection pass produced the last faces — "full", "tiled", "upscale", "none". */
    val lastPassType: String = "none",
    /** Cumulative regions detected since sampling started. */
    val totalDetections: Long = 0L,
    /** Is the sampling loop alive right now. */
    val samplerRunning: Boolean = false,
    /** Pixel size of the TextureView surface being sampled (0 = none yet). */
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    /** Regions the overlay actually drew on its last pass. */
    val overlayRegionsDrawn: Int = 0,
    /** v2.1: sequence of the newest published frame snapshot. */
    val sampledSequence: Long = 0L,
    /** v2.1: newest snapshot sequence the overlay reports having drawn. */
    val drawnSequence: Long = 0L,
    /** v2.1: wall-clock duration of the last sample (capture + detection). */
    val lastSampleDurationMs: Long = 0L
)

/**
 * One atomic frame snapshot (v2.1). Everything the overlay needs for one
 * redraw travels together in a single immutable value:
 * - [sequence]: strictly increasing; the overlay keys its Canvas to it,
 *   so EVERY published sample forces a redraw — a stale draw is
 *   impossible without the diagnostics sequence pair showing it.
 * - [pixelFrame]: a private COPY of the low-res sampled frame at the
 *   active blur strength's resolution (never the sampler's mutable
 *   working buffer — the v2.0 overlay drew from a bitmap that was being
 *   overwritten in place while it read it).
 * - [fullScreenActive]: FULL_SCREEN detection mode + hysteresis — the
 *   whole frame gets the treatment while this is true.
 */
data class BlurFrameSnapshot(
    val sequence: Long = 0L,
    val regions: List<DetectedBlurRegion> = emptyList(),
    val pixelFrame: Bitmap? = null,
    val fullScreenActive: Boolean = false,
    val sampledAtMs: Long = 0L
)

/**
 * Live-video sampler, v2.1 restructure.
 *
 * WHY v2.0 STILL FROZE (field report: blur painted once on a face at a
 * layout event — minimize/resize — then stayed there): the chain had
 * three structural weaknesses, and the fix addresses all three.
 * 1. The engine invoked GPU-delegate TFLite interpreters from arbitrary
 *    pool threads (thread-affine native code can block instead of
 *    throwing; the loop wedged silently — see HaramBlurEngine). The
 *    engine now pins all inference to one dedicated CPU thread, and
 *    this sampler additionally time-boxes each detection call so a slow
 *    call degrades one sample instead of killing the loop.
 * 2. Frame capture (TextureView.getBitmap) ran on a pooled background
 *    thread; SurfaceTexture frames are produced against the UI thread's
 *    choreography. Capture now hops to the main thread for the copy
 *    only (a 640x360 readback), then returns here for detection.
 * 3. The overlay consumed three separate flows plus an in-place-mutated
 *    bitmap through composable parameters. Now the sampler publishes
 *    ONE StateFlow of immutable [BlurFrameSnapshot]s and the overlay
 *    collects it directly — the state chain has a single link.
 *
 * The loop itself runs on its own single-thread dispatcher, at ~12 FPS.
 * The tiled small-face pass keeps its v1.5 cadence (only after two
 * consecutive empty full-frame passes), with the 450 ms box tracker
 * bridging gaps.
 */
class VideoFrameSampler(
    private val haramBlurEngine: HaramBlurEngine,
    private val scope: CoroutineScope,
    private val diagnosticsFlow: MutableStateFlow<SamplerDiagnostics>? = null
) {
    private val samplerDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PureTubeSampler").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val sampleBufferBitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Working pixel buffer, resized to the active strength (recreated on change). */
    private var pixelBufferBitmap: Bitmap? = null
    private var pixelCanvas: android.graphics.Canvas? = null
    private var pixelStrength: BlurStrength? = null
    private val pixelPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    private val _frameState = MutableStateFlow(BlurFrameSnapshot())
    val frameState: StateFlow<BlurFrameSnapshot> = _frameState

    // Kept for the player screen's zero-flash gate + any other readers.
    private val _activeBlurRegions = MutableStateFlow<List<DetectedBlurRegion>>(emptyList())
    val activeBlurRegions: StateFlow<List<DetectedBlurRegion>> = _activeBlurRegions

    private val _isInitialFrameCleared = MutableStateFlow(false)
    val isInitialFrameCleared: StateFlow<Boolean> = _isInitialFrameCleared

    private val _diagnostics = MutableStateFlow(SamplerDiagnostics())
    val diagnostics: StateFlow<SamplerDiagnostics> = _diagnostics

    @Volatile private var sequence: Long = 0L
    @Volatile private var lastDetectionAtMs: Long = 0L
    @Volatile private var consecutiveEmptyFullPasses: Int = 0
    private val tracker = BoundingBoxTracker(holdMs = 450L, alpha = 0.65f)

    @Volatile private var samplingJob: Job? = null
    @Volatile private var released = false

    private fun publishDiagnostics(transform: (SamplerDiagnostics) -> SamplerDiagnostics) {
        val next = transform(_diagnostics.value)
        _diagnostics.value = next
        diagnosticsFlow?.value = next
    }

    /** Publishes one atomic snapshot (the ONLY way overlay state changes). */
    private fun publishSnapshot(
        regions: List<DetectedBlurRegion>,
        pixelFrameCopy: Bitmap?,
        fullScreenActive: Boolean
    ) {
        sequence += 1
        _frameState.value = BlurFrameSnapshot(
            sequence = sequence,
            regions = regions,
            pixelFrame = pixelFrameCopy,
            fullScreenActive = fullScreenActive,
            sampledAtMs = System.currentTimeMillis()
        )
        _activeBlurRegions.value = regions
        publishDiagnostics { it.copy(sampledSequence = sequence) }
    }

    private fun ensurePixelBuffer(strength: BlurStrength): Bitmap? {
        if (pixelBufferBitmap != null && pixelStrength == strength) return pixelBufferBitmap
        val (w, h) = BlurStrengthMath.pixelFrameSize(strength)
        pixelBufferBitmap = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        pixelCanvas = pixelBufferBitmap?.let { android.graphics.Canvas(it) }
        pixelStrength = strength
        return pixelBufferBitmap
    }

    fun startSampling(
        textureView: TextureView,
        prefsProvider: () -> AppPreferencesState,
        isPlayingProvider: () -> Boolean,
        isExemptProvider: () -> Boolean = { false }
    ) {
        stopSampling()
        if (released) return
        runCatching { tracker.reset() }
        lastDetectionAtMs = 0L
        consecutiveEmptyFullPasses = 0
        _isInitialFrameCleared.value = false
        publishDiagnostics {
            it.copy(
                samplerRunning = true,
                surfaceWidth = runCatching { textureView.width }.getOrDefault(0),
                surfaceHeight = runCatching { textureView.height }.getOrDefault(0)
            )
        }
        samplingJob = scope.launch(samplerDispatcher) {
            while (isActive) {
                val sampleStartMs = System.currentTimeMillis()
                var publishedEmpty = false
                var sampleError: String? = null
                runCatching {
                    val prefs = prefsProvider()
                    val videoProtectionActive =
                        prefs.videoFrameSamplingEnabled &&
                            (prefs.protectionScope == ProtectionScope.IN_APP_ONLY ||
                                prefs.protectionScope == ProtectionScope.BOTH) &&
                            (prefs.protectedMediaType == ProtectedMediaType.VIDEOS_ONLY ||
                                prefs.protectedMediaType == ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS)

                    if (!videoProtectionActive ||
                        runCatching { isExemptProvider() }.getOrDefault(false)
                    ) {
                        publishSnapshot(emptyList(), _frameState.value.pixelFrame, false)
                        _isInitialFrameCleared.value = true
                        publishedEmpty = true
                        delay(500L)
                        return@runCatching
                    }

                    val playing = runCatching { isPlayingProvider() }.getOrDefault(false)
                    val available = runCatching { textureView.isAvailable }.getOrDefault(false)
                    // Only sample once playback is actually running; before the
                    // first frame is cleared we still try so the gate can open.
                    if (available && (playing || !_isInitialFrameCleared.value)) {
                        val buffer = sampleBufferBitmap
                        val copied = if (buffer != null) {
                            // Capture on the main thread: the SurfaceTexture's
                            // frames are produced against UI choreography.
                            withContext(Dispatchers.Main.immediate) {
                                copyFrame(textureView, buffer)
                            }
                        } else {
                            false
                        }
                        if (copied && buffer != null) {
                            // Publish the low-res pixel frame (at the active
                            // strength's resolution) for real pixel patches.
                            val pixelCopy = runCatching {
                                val pb = ensurePixelBuffer(prefs.blurStrength)
                                val pc = pixelCanvas
                                if (pb != null && pc != null) {
                                    pc.drawBitmap(
                                        buffer, null,
                                        android.graphics.Rect(0, 0, pb.width, pb.height),
                                        pixelPaint
                                    )
                                    pb.copy(Bitmap.Config.ARGB_8888, false)
                                } else {
                                    null
                                }
                            }.getOrNull()
                            val brightness = meanBrightness(buffer)
                            // Tiled pass cadence: only after 2 consecutive
                            // empty full-frame passes (about every 3rd sample).
                            val allowTiled = consecutiveEmptyFullPasses >= 2
                            val detected = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
                                runCatching {
                                    haramBlurEngine.detectRegionsToBlur(
                                        sourceBitmap = buffer,
                                        targetGender = prefs.blurTargetGender,
                                        sensitivity = prefs.detectionSensitivity,
                                        blurScope = prefs.blurScope,
                                        allowTiledRetry = allowTiled
                                    )
                                }.getOrElse { e ->
                                    sampleError = "${e::class.simpleName}: " +
                                        (e.message ?: "detection failed")
                                    emptyList()
                                }
                            } ?: run {
                                sampleError = "Detection timed out (>2.5s) — sample skipped"
                                emptyList()
                            }
                            val passType = haramBlurEngine.lastPassType
                            consecutiveEmptyFullPasses = when {
                                detected.isNotEmpty() -> 0
                                passType == "tiled" || passType == "upscale" -> 0
                                else -> consecutiveEmptyFullPasses + 1
                            }
                            if (detected.isNotEmpty()) {
                                lastDetectionAtMs = System.currentTimeMillis()
                            }
                            val tracked = runCatching {
                                tracker.onDetections(detected)
                            }.getOrDefault(emptyList())
                            val fullScreen = prefs.blurDetectionMode == BlurDetectionMode.FULL_SCREEN &&
                                FullScreenBlurHold.isActive(
                                    lastDetectionAtMs, System.currentTimeMillis(),
                                    hasDetectionsNow = tracked.isNotEmpty()
                                )
                            publishSnapshot(tracked, pixelCopy, fullScreen)
                            _isInitialFrameCleared.value = true
                            publishDiagnostics {
                                it.copy(
                                    framesSampled = it.framesSampled + 1,
                                    lastFrameBrightness = brightness,
                                    lastDetectionCount = detected.size,
                                    lastError = sampleError,
                                    lastPassType = passType,
                                    totalDetections = it.totalDetections + detected.size,
                                    lastSampleDurationMs =
                                        System.currentTimeMillis() - sampleStartMs
                                )
                            }
                        }
                    }
                }.onFailure { e ->
                    publishDiagnostics {
                        it.copy(lastError = "${e::class.simpleName}: ${e.message ?: "sampling failed"}")
                    }
                }
                if (!publishedEmpty) delay(83L)
            }
        }
    }

    /** Zero-flash gate: a seek or source change re-arms the pre-blur curtain. */
    fun onVideoSeekOrSourceChange(prefs: AppPreferencesState) {
        if (prefs.zeroFlashSafePreBlur) {
            _isInitialFrameCleared.value = false
        }
    }

    fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        if (_diagnostics.value.samplerRunning) {
            publishDiagnostics { it.copy(samplerRunning = false) }
        }
    }

    /** The overlay reports the newest snapshot sequence it actually drew. */
    fun noteOverlayDrawn(drawnSeq: Long, regionsDrawn: Int) {
        val current = _diagnostics.value
        if (current.drawnSequence != drawnSeq || current.overlayRegionsDrawn != regionsDrawn) {
            publishDiagnostics {
                it.copy(drawnSequence = drawnSeq, overlayRegionsDrawn = regionsDrawn)
            }
        }
    }

    fun release() {
        stopSampling()
        released = true
    }

    /**
     * Copies the TextureView's current frame into [buffer] (640x360).
     * MUST be called on the main thread (v2.1). Returns false on any
     * failure — silent no-op by design.
     */
    private fun copyFrame(textureView: TextureView, buffer: Bitmap): Boolean =
        runCatching {
            textureView.getBitmap(buffer) != null
        }.getOrElse { e ->
            publishDiagnostics {
                it.copy(lastError = "getBitmap: ${e::class.simpleName}: ${e.message ?: ""}")
            }
            false
        }

    /** Mean brightness 0-255 over a strided pixel sample of the buffer. */
    private fun meanBrightness(bitmap: Bitmap): Int = runCatching {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            sum += (r + g + b) / 3
            count++
            i += 16 // stride sample: 1/16 of pixels is plenty for a mean
        }
        if (count == 0) -1 else (sum / count).toInt()
    }.getOrDefault(-1)

    companion object {
        /** A single detection call may not stall the loop longer than this. */
        const val DETECTION_TIMEOUT_MS = 2_500L
    }
}
