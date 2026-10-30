package com.macroloop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The saved picture, shown big. Drag the corners to crop it smaller, drag inside to move the crop box,
 * and (for a picture a step taps) drag the dot to choose where in the picture the tap lands.
 * Everything is kept in the picture's own pixels.
 */
class PicCropView(
    context: Context,
    private val bmp: Bitmap,
    private val showDot: Boolean,
    dotAt: PointF,
) : View(context) {

    private val crop = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    private val dot = PointF(dotAt.x.coerceIn(0f, bmp.width.toFloat()), dotAt.y.coerceIn(0f, bmp.height.toFloat()))
    private val minSide = min(8f, min(bmp.width, bmp.height).toFloat())

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TokenInts.primary
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 2).toFloat()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TokenInts.primary }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 2).toFloat()
    }
    private val pic = Paint(Paint.FILTER_BITMAP_FLAG)

    private enum class Drag { NONE, MOVE, TL, TR, BL, BR, DOT }

    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** The chosen crop, in the picture's pixels (never smaller than a few pixels). */
    fun cropRect(): Rect = Rect(
        crop.left.toInt().coerceIn(0, bmp.width - 1),
        crop.top.toInt().coerceIn(0, bmp.height - 1),
        crop.right.toInt().coerceIn(1, bmp.width),
        crop.bottom.toInt().coerceIn(1, bmp.height),
    )

    /** Where the tap lands, in the picture's pixels. */
    fun dotPoint(): PointF = PointF(dot.x, dot.y)

    fun reset() {
        crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        dot.set(bmp.width / 2f, bmp.height / 2f)
        invalidate()
    }

    private fun scale() = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
    private fun left() = (width - bmp.width * scale()) / 2f
    private fun top() = (height - bmp.height * scale()) / 2f
    private fun sx(x: Float) = left() + x * scale()
    private fun sy(y: Float) = top() + y * scale()

    override fun onDraw(c: Canvas) {
        val s = scale()
        val dst = RectF(left(), top(), left() + bmp.width * s, top() + bmp.height * s)
        c.drawBitmap(bmp, null, dst, pic)
        val r = RectF(sx(crop.left), sy(crop.top), sx(crop.right), sy(crop.bottom))
        c.drawRect(dst.left, dst.top, dst.right, r.top, dim)
        c.drawRect(dst.left, r.bottom, dst.right, dst.bottom, dim)
        c.drawRect(dst.left, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, dst.right, r.bottom, dim)
        c.drawRect(r, border)
        val hr = Ui.dpFixed(context, 7).toFloat()
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            c.drawCircle(x, y, hr, handle)
            c.drawCircle(x, y, hr, ring)
        }
        if (showDot) {
            val dx = sx(dot.x)
            val dy = sy(dot.y)
            val rr = Ui.dpFixed(context, 9).toFloat()
            c.drawCircle(dx, dy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
            c.drawCircle(dx, dy, rr, ring)
            c.drawLine(dx - rr * 1.6f, dy, dx + rr * 1.6f, dy, ring)
            c.drawLine(dx, dy - rr * 1.6f, dx, dy + rr * 1.6f, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
                drag = pick(e.x, e.y)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                val dx = (e.x - lastX) / scale()
                val dy = (e.y - lastY) / scale()
                lastX = e.x
                lastY = e.y
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                when (drag) {
                    Drag.MOVE -> {
                        val cw = crop.width()
                        val ch = crop.height()
                        val nl = (crop.left + dx).coerceIn(0f, w - cw)
                        val nt = (crop.top + dy).coerceIn(0f, h - ch)
                        crop.set(nl, nt, nl + cw, nt + ch)
                    }
                    Drag.TL -> { crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSide); crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSide) }
                    Drag.TR -> { crop.right = (crop.right + dx).coerceIn(crop.left + minSide, w); crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSide) }
                    Drag.BL -> { crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSide); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSide, h) }
                    Drag.BR -> { crop.right = (crop.right + dx).coerceIn(crop.left + minSide, w); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSide, h) }
                    Drag.DOT -> {}
                    Drag.NONE -> {}
                }
                if (drag == Drag.DOT) {
                    dot.x = (dot.x + dx).coerceIn(0f, w)
                    dot.y = (dot.y + dy).coerceIn(0f, h)
                }
                // the tap point always stays inside the crop
                if (showDot) {
                    dot.x = dot.x.coerceIn(crop.left, crop.right)
                    dot.y = dot.y.coerceIn(crop.top, crop.bottom)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun pick(x: Float, y: Float): Drag {
        val near = Ui.dpFixed(context, 28).toFloat()
        fun d(px: Float, py: Float) = hypot(x - sx(px), y - sy(py))
        if (showDot && d(dot.x, dot.y) < near) return Drag.DOT
        val corners = listOf(
            Drag.TL to d(crop.left, crop.top), Drag.TR to d(crop.right, crop.top),
            Drag.BL to d(crop.left, crop.bottom), Drag.BR to d(crop.right, crop.bottom),
        )
        val best = corners.minByOrNull { it.second }!!
        if (best.second < near) return best.first
        val inside = x >= sx(crop.left) && x <= sx(crop.right) && y >= sy(crop.top) && y <= sy(crop.bottom)
        return if (inside) Drag.MOVE else Drag.NONE
    }
}
