package com.puretube.app.haramblur.video

import android.graphics.Bitmap
import android.view.TextureView
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
import com.puretube.app.haramblur.engine.BoundingBoxTracker
import com.puretube.app.haramblur.engine.DetectedBlurRegion
import com.puretube.app.haramblur.engine.HaramBlurEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sampler diagnostics (v1.4), surfaced in Settings near the HaramBlur
 * status block: how many frames were actually sampled, how bright the
 * last sampled frame was (0-255 mean), how many regions the last
 * detection produced, and the last silent failure, if any.
 */
data class SamplerDiagnostics(
    val framesSampled: Long = 0L,
    val lastFrameBrightness: Int = -1,
    val lastDetectionCount: Int = -1,
    val lastError: String? = null,
    /** v1.5: which detection pass produced the last faces — "full", "tiled", "upscale", "none". */
    val lastPassType: String = "none",
    /** v1.5: cumulative regions detected since sampling started. */
    val totalDetections: Long = 0L
)

/**
 * Decoupled ~12 FPS (83 ms) live-video sampler, v1.4 rewrite.
 *
 * ROOT CAUSE of the v1.3 in-video blur failure: the sampler captured
 * frames with PixelCopy.request(SurfaceView, …). The player's video
 * surface is a SurfaceView whose pixels are composited by the
 * hardware/SurfaceFlinger path; PixelCopy against that surface
 * routinely reported failure (or produced black frames) on real
 * devices, and every failure was swallowed by the defensive
 * runCatching design — so thumbnails blurred (Coil path, works) while
 * live video silently never produced a single detection.
 *
 * v1.4: the player renders into a TextureView (Player.setVideoTextureView)
 * and the sampler copies frames with TextureView.getBitmap() into the
 * fixed buffer on its own coroutine — NO PixelCopy anywhere.
 * TextureView keeps its frames in a regular texture the app can read
 * back, which is exactly what a sampler needs.
 *
 * v1.5: the buffer is 640x360 (was 320x180) so small faces survive the
 * downsample, and the engine's tiled second pass runs at a reduced
 * cadence: only after two consecutive full-frame passes found nothing
 * (roughly every 3rd sample), with the 450 ms hysteresis tracker
 * bridging the gaps so boxes don't flicker between passes.
 *
 * Defensive by design (kept from v1.1 hardening):
 * - The whole sampling loop is wrapped in runCatching; any failure
 *   degrades to "no regions" instead of crashing the player, and is
 *   recorded in [diagnostics] instead of vanishing silently.
 * - Sampling only runs while the player reports it is playing (or
 *   until the first frame has been cleared by the zero-flash gate).
 * - A preference kill switch (videoFrameSamplingEnabled) disables sampling.
 * - A channel-exemption provider skips sampling entirely for exempt
 *   channels.
 */
class VideoFrameSampler(
    private val haramBlurEngine: HaramBlurEngine,
    private val scope: CoroutineScope,
    private val diagnosticsFlow: MutableStateFlow<SamplerDiagnostics>? = null
) {
    private val sampleBufferBitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Consecutive full-frame passes that found nothing (drives the tiled cadence). */
    @Volatile private var consecutiveEmptyFullPasses: Int = 0
    private val tracker = BoundingBoxTracker(holdMs = 450L, alpha = 0.65f)

    private val _activeBlurRegions = MutableStateFlow<List<DetectedBlurRegion>>(emptyList())
    val activeBlurRegions: StateFlow<List<DetectedBlurRegion>> = _activeBlurRegions

    private val _isInitialFrameCleared = MutableStateFlow(false)
    val isInitialFrameCleared: StateFlow<Boolean> = _isInitialFrameCleared

    private val _diagnostics = MutableStateFlow(SamplerDiagnostics())
    val diagnostics: StateFlow<SamplerDiagnostics> = _diagnostics

    @Volatile private var samplingJob: Job? = null
    @Volatile private var released = false

    private fun publishDiagnostics(transform: (SamplerDiagnostics) -> SamplerDiagnostics) {
        val next = transform(_diagnostics.value)
        _diagnostics.value = next
        diagnosticsFlow?.value = next
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
        _isInitialFrameCleared.value = false
        samplingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching {
                    val prefs = prefsProvider()
                    // Kill switch: sampling fully disabled by preference.
                    if (!prefs.videoFrameSamplingEnabled) {
                        _activeBlurRegions.value = emptyList()
                        _isInitialFrameCleared.value = true
                        delay(500L)
                        return@runCatching
                    }
                    val videoProtectionActive =
                        (prefs.protectionScope == ProtectionScope.IN_APP_ONLY ||
                            prefs.protectionScope == ProtectionScope.BOTH) &&
                            (prefs.protectedMediaType == ProtectedMediaType.VIDEOS_ONLY ||
                                prefs.protectedMediaType == ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS)

                    if (!videoProtectionActive) {
                        _activeBlurRegions.value = emptyList()
                        _isInitialFrameCleared.value = true
                        delay(500L)
                        return@runCatching
                    }

                    // Channel exemption: never sample (or blur) this channel.
                    if (runCatching { isExemptProvider() }.getOrDefault(false)) {
                        _activeBlurRegions.value = emptyList()
                        _isInitialFrameCleared.value = true
                        delay(500L)
                        return@runCatching
                    }

                    val playing = runCatching { isPlayingProvider() }.getOrDefault(false)
                    val available = runCatching { textureView.isAvailable }.getOrDefault(false)
                    // Only sample once playback is actually running; before the
                    // first frame is cleared we still try so the gate can open.
                    if (available && (playing || !_isInitialFrameCleared.value)) {
                        val buffer = sampleBufferBitmap
                        val copied = if (buffer != null) copyFrame(textureView, buffer) else false
                        if (copied && buffer != null) {
                            val brightness = meanBrightness(buffer)
                            // Tiled pass cadence: only after 2 consecutive
                            // empty full-frame passes (about every 3rd sample).
                            val allowTiled = consecutiveEmptyFullPasses >= 2
                            val detected = runCatching {
                                haramBlurEngine.detectRegionsToBlur(
                                    sourceBitmap = buffer,
                                    targetGender = prefs.blurTargetGender,
                                    sensitivity = prefs.detectionSensitivity,
                                    blurScope = prefs.blurScope,
                                    allowTiledRetry = allowTiled
                                )
                            }.getOrElse { e ->
                                publishDiagnostics {
                                    it.copy(lastError = "${e::class.simpleName}: ${e.message ?: "detection failed"}")
                                }
                                emptyList()
                            }
                            val passType = haramBlurEngine.lastPassType
                            consecutiveEmptyFullPasses = when {
                                detected.isNotEmpty() -> 0
                                passType == "tiled" || passType == "upscale" -> 0
                                else -> consecutiveEmptyFullPasses + 1
                            }
                            _activeBlurRegions.value = runCatching {
                                tracker.onDetections(detected)
                            }.getOrDefault(emptyList())
                            _isInitialFrameCleared.value = true
                            publishDiagnostics {
                                it.copy(
                                    framesSampled = it.framesSampled + 1,
                                    lastFrameBrightness = brightness,
                                    lastDetectionCount = detected.size,
                                    lastError = null,
                                    lastPassType = passType,
                                    totalDetections = it.totalDetections + detected.size
                                )
                            }
                        }
                    }
                }.onFailure { e ->
                    publishDiagnostics {
                        it.copy(lastError = "${e::class.simpleName}: ${e.message ?: "sampling failed"}")
                    }
                }
                delay(83L)
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
    }

    fun release() {
        stopSampling()
        released = true
    }

    /**
     * Copies the TextureView's current frame into [buffer] (640x360).
     * TextureView.getBitmap(Bitmap) scales into the supplied bitmap.
     * Returns false on any failure — silent no-op by design.
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
}
