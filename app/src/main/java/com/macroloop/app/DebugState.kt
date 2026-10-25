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

    fun record(frame: Bitmap, res: Matcher.Result?, thresholdPct: Int, label: String) {
        val h = (frame.height * PREVIEW_WIDTH.toFloat() / frame.width).toInt()
        preview = Bitmap.createScaledBitmap(frame, PREVIEW_WIDTH, h, true)
        frameWidth = frame.width
        frameHeight = frame.height
        result = res
        this.thresholdPct = thresholdPct
        this.label = label
    }
}
