package com.macroloop.app

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The last thing the picture finder looked at. Shown on the "Picture test" screen. */
object DebugState {
    private const val PREVIEW_WIDTH = 540

    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var frameWidth by mutableStateOf(0)
        private set
    var frameHeight by mutableStateOf(0)
        private set
    var result by mutableStateOf<Matcher.Result?>(null)
        private set
    var thresholdPct by mutableStateOf(90)
        private set
    var label by mutableStateOf("")
        private set
    var extra by mutableStateOf<List<Matcher.Result>>(emptyList())
        private set

    private var lastAt = 0L

    fun record(frame: Bitmap, res: Matcher.Result?, thresholdPct: Int, label: String, others: List<Matcher.Result> = emptyList()) {
        // A scaled copy of the screen is slow, and a fast macro looks many times a second: keep only now and then.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAt < 700 && res?.found != true) return
        lastAt = now
        extra = others
        val h = (frame.height * PREVIEW_WIDTH.toFloat() / frame.width).toInt()
        preview = Bitmap.createScaledBitmap(frame, PREVIEW_WIDTH, h, true)
        frameWidth = frame.width
        frameHeight = frame.height
        result = res
        this.thresholdPct = thresholdPct
        this.label = label
    }
}
