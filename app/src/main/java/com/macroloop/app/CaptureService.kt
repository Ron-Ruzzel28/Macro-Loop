package com.macroloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps one screen-capture session open so macros can look at the screen.
 * Frames are kept in memory only. Nothing is written to disk.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7

        /** True while capture is working. Read by the Setup screen. */
        var running by mutableStateOf(false)
            private set

        @Volatile
        var instance: CaptureService? = null
            private set

        /** A private copy of the newest frame, or null if none yet. */
        fun grab(): Bitmap? = instance?.copyFrame()

        /**
         * Like [grab], but waits (up to [maxWaitMs]) for a frame taken after [sinceMs]
         * (SystemClock.elapsedRealtime). After a tap the screen changes; without this, the first look
         * could still see the screen from before the tap. A still screen never sends a new frame, so
         * the wait is capped and the last frame is used.
         */
        suspend fun grabFresh(sinceMs: Long, maxWaitMs: Long = 300): Bitmap? {
            val svc = instance ?: return null
            val limit = SystemClock.elapsedRealtime() + maxWaitMs
            while (svc.lastFrameAt < sinceMs && SystemClock.elapsedRealtime() < limit) {
                kotlinx.coroutines.delay(20)
            }
            return svc.copyFrame()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    /** Set to true while a macro or test is looking at the screen, for faster frames. */
    @Volatile
    var fast = false

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private val lock = Any()
    private var buffer: Bitmap? = null
    @Volatile
    private var lastFrameAt = 0L
    private var pending: android.media.Image? = null
    private var convertScheduled = false
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        teardown()

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj

        val bounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        val dpi = resources.displayMetrics.densityDpi

        val t = HandlerThread("capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        handlerRef = handler

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                teardown()
                stopSelf()
            }
        }, handler)

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) {
                pending?.close()
                pending = image
            }
            scheduleConvert(handler)
        }, handler)

        display = proj.createVirtualDisplay(
            "macroloop",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
        instance = this
        running = true
        return START_NOT_STICKY
    }

    private var handlerRef: Handler? = null

    /** When the phone is turned, the captured picture must turn with it, or every position would be wrong. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = display ?: return
        val handler = handlerRef ?: return
        val b = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        if (b.width() == width && b.height() == height) return
        synchronized(lock) {
            width = b.width()
            height = b.height()
            pending?.close(); pending = null
            buffer?.recycle(); buffer = null
        }
        val old = reader
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) {
                pending?.close()
                pending = image
            }
            scheduleConvert(handler)
        }, handler)
        reader = r
        d.resize(width, height, resources.displayMetrics.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    /** Converts the newest image after the minimum gap, so the last change on screen is never dropped. */
    private fun scheduleConvert(handler: Handler) {
        synchronized(lock) {
            if (convertScheduled) return
            convertScheduled = true
        }
        val gap = if (fast) 60L else 500L
        val wait = (gap - (SystemClock.elapsedRealtime() - lastFrameAt)).coerceAtLeast(0L)
        handler.postDelayed({ convertPending() }, wait)
    }

    private fun convertPending() {
        synchronized(lock) {
            convertScheduled = false
            val image = pending ?: return
            pending = null
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val needW = width + rowPadding / pixelStride
                var buf = buffer
                if (buf == null || buf.width != needW || buf.height != height) {
                    buf?.recycle()
                    buf = Bitmap.createBitmap(needW, height, Bitmap.Config.ARGB_8888)
                    buffer = buf
                }
                plane.buffer.rewind()
                buf!!.copyPixelsFromBuffer(plane.buffer)
                lastFrameAt = SystemClock.elapsedRealtime()
            } finally {
                image.close()
            }
        }
    }

    private fun copyFrame(): Bitmap? = synchronized(lock) {
        val b = buffer ?: return null
        val cropped = if (b.width != width) Bitmap.createBitmap(b, 0, 0, width, height) else b.copy(Bitmap.Config.ARGB_8888, false)
        cropped
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("MacroLoop can see the screen")
            .setContentText("Needed to find pictures. Turn it off in MacroLoop > Set up.")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun teardown() {
        running = false
        instance = null
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely(); thread = null
        synchronized(lock) {
            pending?.close(); pending = null
            convertScheduled = false
            buffer?.recycle(); buffer = null
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
