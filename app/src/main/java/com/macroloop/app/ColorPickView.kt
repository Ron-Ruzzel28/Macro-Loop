package com.macroloop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The picture of the area, shown big with no smoothing, so single pixels can be seen.
 * Tap or drag on it to choose a pixel; the chosen pixel has a box around it. Kept in the picture's own pixels.
 */
class ColorPickView(
    context: Context,
    private val bmp: Bitmap,
    startX: Int,
    startY: Int,
    private val onPick: (Int, Int) -> Unit,
) : View(context) {

    var px = startX.coerceIn(0, bmp.width - 1)
        private set
    var py = startY.coerceIn(0, bmp.height - 1)
        private set

    private val pic = Paint().apply { isFilterBitmap = false }
    private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 4).toFloat()
    }
    private val ringLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 2).toFloat()
    }

    private fun scale() = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
    private fun left() = (width - bmp.width * scale()) / 2f
    private fun top() = (height - bmp.height * scale()) / 2f

    fun setPixel(x: Int, y: Int) {
        px = x.coerceIn(0, bmp.width - 1)
        py = y.coerceIn(0, bmp.height - 1)
        invalidate()
    }

    fun colorHere(): Int = bmp.getPixel(px, py) or (0xFF shl 24)

    override fun onDraw(c: Canvas) {
        val s = scale()
        val l = left()
        val t = top()
        c.drawBitmap(bmp, null, RectF(l, t, l + bmp.width * s, t + bmp.height * s), pic)
        val pad = Ui.dpFixed(context, 3).toFloat()
        val r = RectF(l + px * s - pad, t + py * s - pad, l + (px + 1) * s + pad, t + (py + 1) * s + pad)
        c.drawRect(r, ringDark)
        c.drawRect(r, ringLight)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = scale()
                setPixel(((e.x - left()) / s).toInt(), ((e.y - top()) / s).toInt())
                onPick(px, py)
            }
        }
        return true
    }
}
