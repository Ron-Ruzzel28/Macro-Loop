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

        /** A private copy of the newest frame, or null if none yet. Asking for the screen also wakes the capture if it was resting. */
        fun grab(): Bitmap? = instance?.also { it.needed() }?.copyFrame()

        /**
         * Like [grab], but waits (up to [maxWaitMs]) for a frame taken after [sinceMs]
         * (SystemClock.elapsedRealtime). After a tap the screen changes; without this, the first look
         * could still see the screen from before the tap. A still screen never sends a new frame, so
         * the wait is capped and the last frame is used.
         */
        suspend fun grabFresh(sinceMs: Long, maxWaitMs: Long = 300): Bitmap? {
            val svc = instance ?: return null
            svc.needed()
            if (svc.lastFrameAt < svc.resumedAt) waitFrame(svc.resumedAt, RESUME_WAIT_MS)
            val limit = SystemClock.elapsedRealtime() + maxWaitMs
            while (svc.lastFrameAt < sinceMs && SystemClock.elapsedRealtime() < limit) {
                kotlinx.coroutines.delay(20)
            }
            return svc.copyFrame()
        }

        /** When the newest frame was taken (SystemClock.elapsedRealtime). */
        fun frameAt(): Long = instance?.lastFrameAt ?: 0L

        private const val IDLE_PAUSE_MS = 6000L
        private const val RESUME_WAIT_MS = 700L

        /** Waits (up to [maxWaitMs]) until a frame newer than [sinceMs] exists. A still screen sends none, so the wait is capped. */
        suspend fun waitFrame(sinceMs: Long, maxWaitMs: Long) {
            val svc = instance ?: return
            svc.needed()
            val limit = SystemClock.elapsedRealtime() + maxWaitMs
            while (svc.lastFrameAt < sinceMs && SystemClock.elapsedRealtime() < limit) kotlinx.coroutines.delay(10)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    /** Set to true while a macro or test is looking at the screen, for faster frames. */
    @Volatile
    var fast = false

    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
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

        val size = realSize()
        width = size.first
        height = size.second
        val dpi = resources.displayMetrics.densityDpi

        val t = HandlerThread("capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        handlerRef = handler

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // Android (or the user, from the status bar) ended the capture. Say so, and how to turn it back on.
                teardown()
                stopSelf()
                MacroScheduler.notify(applicationContext, "Screen capture stopped", "Tap here to turn it back on. Macros that look at the screen need it.", askCapture = true)
            }
        }
        projCallback = cb
        proj.registerCallback(cb, handler)

        reader = newReader(handler)
        val r = reader!!

        display = proj.createVirtualDisplay(
            "macroloop",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
        instance = this
        running = true
        lastNeededAt = SystemClock.elapsedRealtime()
        paused = false
        handler.postDelayed(idleTick, 2000)
        return START_NOT_STICKY
    }

    private var handlerRef: Handler? = null

    // ---- resting: with nothing looking at the screen, the mirroring is switched off, so other apps and games do not lag
    @Volatile private var lastNeededAt = 0L
    @Volatile private var paused = false
    @Volatile private var resumedAt = 0L

    private val idleTick = object : Runnable {
        override fun run() {
            if (!paused && !fast && SystemClock.elapsedRealtime() - lastNeededAt > IDLE_PAUSE_MS) pauseNow()
            handlerRef?.postDelayed(this, 2000)
        }
    }

    /** Something is about to look at the screen: wake the mirroring if it was resting. */
    fun needed() {
        lastNeededAt = SystemClock.elapsedRealtime()
        if (paused) resumeNow()
    }

    @Synchronized
    private fun pauseNow() {
        if (paused) return
        paused = true
        runCatching { display?.surface = null }
    }

    @Synchronized
    private fun resumeNow() {
        if (!paused) return
        paused = false
        resumedAt = SystemClock.elapsedRealtime()
        runCatching { display?.surface = reader?.surface }
    }

    /** The screen's real size in the way it is held right now. */
    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        val d = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * A reader for the current size. A picture that arrives from a reader that is no longer the current one
     * is thrown away, and a reader that is already closed can never crash the app.
     */
    private fun newReader(handler: Handler): ImageReader {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val image = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            var keep = false
            synchronized(lock) {
                if (ir === reader) {
                    pending?.let { runCatching { it.close() } }
                    pending = image
                    keep = true
                }
            }
            if (keep) scheduleConvert(handler) else runCatching { image.close() }
        }, handler)
        return r
    }

    @Volatile
    private var settleUntil = 0L

    /** When the phone is turned, the captured picture must turn with it, or every position would be wrong. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val handler = handlerRef ?: return
        // On the capture thread, so it can never run in the middle of reading a picture.
        handler.post { resizeCapture(handler) }
    }

    private fun resizeCapture(handler: Handler) {
        val d = display ?: return
        val size = realSize()
        if (size.first == width && size.second == height) return
        val old = reader
        synchronized(lock) {
            width = size.first
            height = size.second
            buffer?.recycle(); buffer = null
        }
        val r = newReader(handler)
        synchronized(lock) {
            reader = r                                   // from here, pictures from the old reader are thrown away
            pending?.let { runCatching { it.close() } }
            pending = null
        }
        settleUntil = SystemClock.elapsedRealtime() + 450   // the first pictures after a turn can be blank
        runCatching { d.resize(width, height, resources.displayMetrics.densityDpi) }
        if (!paused) runCatching { d.surface = r.surface }
        old?.setOnImageAvailableListener(null, null)
        runCatching { old?.close() }
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
            if (pending == null) return
            val wait = settleUntil - SystemClock.elapsedRealtime()
            if (wait > 0) {                                  // just turned: look again a little later
                convertScheduled = true
                handlerRef?.postDelayed({ convertPending() }, wait)
                return
            }
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
            } catch (e: Exception) {
                // a picture that went bad (the reader was closed, the size just changed): skip it
            } finally {
                runCatching { image.close() }
            }
        }
    }

    private fun copyFrame(): Bitmap? = synchronized(lock) {
        if (lastFrameAt < resumedAt) return null        // woken from rest and no new picture yet: the old one may be out of date
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
        val r = reader
        r?.setOnImageAvailableListener(null, null)
        synchronized(lock) {
            reader = null
            pending?.let { runCatching { it.close() } }
            pending = null
            convertScheduled = false
        }
        handlerRef?.removeCallbacks(idleTick)
        runCatching { display?.release() }; display = null
        runCatching { r?.close() }
        projCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projCallback = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely(); thread = null
        handlerRef = null
        synchronized(lock) {
            buffer?.recycle(); buffer = null
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
