package com.puretube.app.haramblur.system

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.haramblur.engine.DetectedBlurRegion
import com.puretube.app.haramblur.engine.HaramBlurEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * System-Wide Shield: protects third-party apps with an in-memory
 * MediaProjection ring buffer and a 100% click-through overlay window
 * (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE).
 *
 * Token handshake: this service is only ever started AFTER the consent
 * activity returns RESULT_OK with the MediaProjection intent token.
 */
class SystemBlurOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var windowManager: WindowManager
    private lateinit var haramBlurEngine: HaramBlurEngine
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: ClickThroughBlurOverlayView? = null
    private val motionDetector = MotionDeltaDetector()

    private val captureWidth = 360
    private val captureHeight = 640

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        haramBlurEngine = HaramBlurEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHIELD) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startShieldForeground()
        attachClickThroughOverlayWindow()
        startMediaProjectionLoop(resultCode, resultData)
        return START_STICKY
    }

    private fun startShieldForeground() {
        val channelId = "haramblur_shield_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "HaramBlur System Shield", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("HaramBlur System Shield Active")
            .setContentText("100% on-device protection running across apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun attachClickThroughOverlayWindow() {
        if (overlayView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        overlayView = ClickThroughBlurOverlayView(this)
        windowManager.addView(overlayView, params)
    }

    private fun startMediaProjectionLoop(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, null)
        }
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HaramBlurSystemDisplay",
            captureWidth,
            captureHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        serviceScope.launch {
            val reusableBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            while (isActive) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * captureWidth
                        val motion = motionDetector.hasMotion(buffer)
                        if (motion && rowPadding == 0) {
                            buffer.rewind()
                            reusableBitmap.copyPixelsFromBuffer(buffer)
                        }
                        image.close()
                        if (motion && rowPadding == 0) {
                            val regions = haramBlurEngine.detectRegionsToBlur(
                                sourceBitmap = reusableBitmap,
                                targetGender = BlurTargetGender.FEMALE_ONLY,
                                sensitivity = 0.65f
                            )
                            overlayView?.updateRegions(regions)
                        }
                    } catch (e: Exception) {
                        runCatching { image.close() }
                    }
                }
                delay(100L)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class ClickThroughBlurOverlayView(context: Context) : View(context) {
        @Volatile
        private var regions: List<DetectedBlurRegion> = emptyList()
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4141414")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        fun updateRegions(newRegions: List<DetectedBlurRegion>) {
            regions = newRegions
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (region in regions) {
                val rect = RectF(
                    region.normalizedRect.left * width,
                    region.normalizedRect.top * height,
                    region.normalizedRect.right * width,
                    region.normalizedRect.bottom * height
                )
                canvas.drawRoundRect(rect, 20f, 20f, maskPaint)
                canvas.drawRoundRect(rect, 20f, 20f, borderPaint)
            }
        }
    }

    companion object {
        const val ACTION_STOP_SHIELD = "com.puretube.app.ACTION_STOP_SHIELD"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 4092

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, SystemBlurOverlayService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, SystemBlurOverlayService::class.java).apply {
                action = ACTION_STOP_SHIELD
            }
    }
}
