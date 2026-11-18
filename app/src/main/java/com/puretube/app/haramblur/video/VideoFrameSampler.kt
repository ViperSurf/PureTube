package com.puretube.app.haramblur.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Decoupled 12 FPS (83ms) live-video sampler: PixelCopy runs on a dedicated
 * HandlerThread into a fixed 320x180 buffer, inference runs off the UI thread,
 * and smoothed regions are emitted via StateFlow to the Compose overlay.
 *
 * Defensive by design (v1.1 crash hardening):
 * - The whole sampling loop is wrapped in runCatching; any failure degrades
 *   to "no regions" instead of crashing the player.
 * - PixelCopy uses suspendCancellableCoroutine + tryResume, so a cancelled
 *   or late callback can never resume twice or leak.
 * - Sampling only runs while the player reports it is playing (or until the
 *   first frame has been cleared by the zero-flash gate).
 * - release() cancels the sampling job BEFORE quitting the HandlerThread.
 * - A preference kill switch (videoFrameSamplingEnabled) disables sampling.
 */
class VideoFrameSampler(
    private val haramBlurEngine: HaramBlurEngine,
    private val scope: CoroutineScope
) {
    @Volatile private var pixelCopyThread: HandlerThread? = null
    @Volatile private var pixelCopyHandler: Handler? = null
    private val sampleBufferBitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    private val tracker = BoundingBoxTracker(holdMs = 450L, alpha = 0.65f)

    private val _activeBlurRegions = MutableStateFlow<List<DetectedBlurRegion>>(emptyList())
    val activeBlurRegions: StateFlow<List<DetectedBlurRegion>> = _activeBlurRegions

    private val _isInitialFrameCleared = MutableStateFlow(false)
    val isInitialFrameCleared: StateFlow<Boolean> = _isInitialFrameCleared

    @Volatile private var samplingJob: Job? = null
    @Volatile private var released = false

    private fun ensureThread(): Handler? {
        if (released) return null
        pixelCopyHandler?.let { return it }
        return synchronized(this) {
            pixelCopyHandler ?: runCatching {
                val thread = HandlerThread("HaramBlurVideoPixelCopy").apply { start() }
                pixelCopyThread = thread
                Handler(thread.looper).also { pixelCopyHandler = it }
            }.getOrNull()
        }
    }

    fun startSampling(
        surfaceView: SurfaceView,
        prefsProvider: () -> AppPreferencesState,
        isPlayingProvider: () -> Boolean
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

                    val playing = runCatching { isPlayingProvider() }.getOrDefault(false)
                    val surfaceValid = runCatching {
                        surfaceView.holder.surface.isValid
                    }.getOrDefault(false)
                    // Only sample once playback is actually running; before the
                    // first frame is cleared we still try so the gate can open.
                    if (surfaceValid && (playing || !_isInitialFrameCleared.value)) {
                        val copySuccess = requestDownsampledFrame(surfaceView)
                        val buffer = sampleBufferBitmap
                        if (copySuccess && buffer != null) {
                            val detected = runCatching {
                                haramBlurEngine.detectRegionsToBlur(
                                    sourceBitmap = buffer,
                                    targetGender = prefs.blurTargetGender,
                                    sensitivity = prefs.detectionSensitivity
                                )
                            }.getOrDefault(emptyList())
                            _activeBlurRegions.value = runCatching {
                                tracker.onDetections(detected)
                            }.getOrDefault(emptyList())
                            _isInitialFrameCleared.value = true
                        }
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
        // Cancel the job FIRST so no PixelCopy request is in flight against a
        // dead handler, then quit the thread.
        stopSampling()
        released = true
        runCatching { pixelCopyThread?.quitSafely() }
        pixelCopyThread = null
        pixelCopyHandler = null
    }

    private suspend fun requestDownsampledFrame(surfaceView: SurfaceView): Boolean {
        val buffer = sampleBufferBitmap ?: return false
        val handler = ensureThread() ?: return false
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                try {
                    PixelCopy.request(
                        surfaceView,
                        buffer,
                        { copyResult ->
                            continuation.tryResume(copyResult == PixelCopy.SUCCESS) {}
                        },
                        handler
                    )
                } catch (e: Exception) {
                    continuation.tryResume(false) {}
                }
            }
        }.getOrDefault(false)
    }
}
