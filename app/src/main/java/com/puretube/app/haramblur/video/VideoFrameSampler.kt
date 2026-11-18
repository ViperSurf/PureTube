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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Decoupled 12 FPS (83ms) live-video sampler: PixelCopy runs on a dedicated
 * HandlerThread into a fixed 320x180 buffer, inference runs off the UI thread,
 * and smoothed regions are emitted via StateFlow to the Compose overlay.
 */
class VideoFrameSampler(
    private val haramBlurEngine: HaramBlurEngine,
    private val scope: CoroutineScope
) {
    private val pixelCopyThread = HandlerThread("HaramBlurVideoPixelCopy").apply { start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)
    private val sampleBufferBitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    private val tracker = BoundingBoxTracker(holdMs = 450L, alpha = 0.65f)

    private val _activeBlurRegions = MutableStateFlow<List<DetectedBlurRegion>>(emptyList())
    val activeBlurRegions: StateFlow<List<DetectedBlurRegion>> = _activeBlurRegions

    private val _isInitialFrameCleared = MutableStateFlow(false)
    val isInitialFrameCleared: StateFlow<Boolean> = _isInitialFrameCleared

    private var samplingJob: Job? = null

    fun startSampling(
        surfaceView: SurfaceView,
        prefsProvider: () -> AppPreferencesState,
        isPlayingProvider: () -> Boolean
    ) {
        stopSampling()
        tracker.reset()
        _isInitialFrameCleared.value = false
        samplingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val prefs = prefsProvider()
                val videoProtectionActive =
                    (prefs.protectionScope == ProtectionScope.IN_APP_ONLY ||
                        prefs.protectionScope == ProtectionScope.BOTH) &&
                        (prefs.protectedMediaType == ProtectedMediaType.VIDEOS_ONLY ||
                            prefs.protectedMediaType == ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS)

                if (!videoProtectionActive) {
                    _activeBlurRegions.value = emptyList()
                    _isInitialFrameCleared.value = true
                    delay(500L)
                    continue
                }

                if (surfaceView.holder.surface.isValid &&
                    (isPlayingProvider() || !_isInitialFrameCleared.value)
                ) {
                    val copySuccess = requestDownsampledFrame(surfaceView)
                    if (copySuccess) {
                        val detected = haramBlurEngine.detectRegionsToBlur(
                            sourceBitmap = sampleBufferBitmap,
                            targetGender = prefs.blurTargetGender,
                            sensitivity = prefs.detectionSensitivity
                        )
                        _activeBlurRegions.value = tracker.onDetections(detected)
                        _isInitialFrameCleared.value = true
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
        pixelCopyThread.quitSafely()
    }

    private suspend fun requestDownsampledFrame(surfaceView: SurfaceView): Boolean =
        suspendCoroutine { continuation ->
            try {
                PixelCopy.request(
                    surfaceView,
                    sampleBufferBitmap,
                    { copyResult -> continuation.resume(copyResult == PixelCopy.SUCCESS) },
                    pixelCopyHandler
                )
            } catch (e: Exception) {
                continuation.resume(false)
            }
        }
}
