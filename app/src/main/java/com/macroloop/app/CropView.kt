package com.macroloop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Shows a frozen screenshot full screen. You drag a box around the thing to find.
 * Drag a corner to resize, drag inside to move. The box is kept in screenshot pixels.
 */
class CropView(context: Context, private val shot: Bitmap) : View(context) {

    private val box = RectF(
        shot.width * 0.30f, shot.height * 0.45f,
        shot.width * 0.70f, shot.height * 0.45f + shot.height * 0.08f,
    )
    private val dim = Paint().apply { color = TokenInts.scrim }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TokenInts.primary
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 3).toFloat()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TokenInts.primary }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpFixed(context, 2).toFloat()
    }
    private val src = Rect(0, 0, shot.width, shot.height)
    private val minSize = 24f

    private enum class Drag { NONE, MOVE, TL, TR, BL, BR }

    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** Called whenever the box moves, with the box in screenshot pixels. */
    var onBoxChanged: ((RectF) -> Unit)? = null

    /** The chosen box in screenshot pixels. */
    fun selection(): Rect = Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())

    private fun sx() = width.toFloat() / shot.width
    private fun sy() = height.toFloat() / shot.height

    override fun onDraw(c: Canvas) {
        c.drawBitmap(shot, src, Rect(0, 0, width, height), null)
        val r = RectF(box.left * sx(), box.top * sy(), box.right * sx(), box.bottom * sy())
        // dim everything outside the box
        c.drawRect(0f, 0f, width.toFloat(), r.top, dim)
        c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
        c.drawRect(r, border)
        val hr = Ui.dpFixed(context, 11).toFloat()
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            c.drawCircle(x, y, hr, handle)
            c.drawCircle(x, y, hr, handleRing)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                drag = hit(e.x, e.y)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / sx()
                val dy = (e.y - lastY) / sy()
                lastX = e.x
                lastY = e.y
                apply(dx, dy)
                onBoxChanged?.invoke(box)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun hit(x: Float, y: Float): Drag {
        val r = RectF(box.left * sx(), box.top * sy(), box.right * sx(), box.bottom * sy())
        val reach = Ui.dpFixed(context, 28).toFloat()
        fun near(px: Float, py: Float) = kotlin.math.abs(x - px) < reach && kotlin.math.abs(y - py) < reach
        return when {
            near(r.left, r.top) -> Drag.TL
            near(r.right, r.top) -> Drag.TR
            near(r.left, r.bottom) -> Drag.BL
            near(r.right, r.bottom) -> Drag.BR
            r.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun apply(dx: Float, dy: Float) {
        val w = shot.width.toFloat()
        val h = shot.height.toFloat()
        when (drag) {
            Drag.MOVE -> {
                val bw = box.width()
                val bh = box.height()
                val l = (box.left + dx).coerceIn(0f, w - bw)
                val t = (box.top + dy).coerceIn(0f, h - bh)
                box.set(l, t, l + bw, t + bh)
            }
            Drag.TL -> {
                box.left = (box.left + dx).coerceIn(0f, box.right - minSize)
                box.top = (box.top + dy).coerceIn(0f, box.bottom - minSize)
            }
            Drag.TR -> {
                box.right = (box.right + dx).coerceIn(box.left + minSize, w)
                box.top = (box.top + dy).coerceIn(0f, box.bottom - minSize)
            }
            Drag.BL -> {
                box.left = (box.left + dx).coerceIn(0f, box.right - minSize)
                box.bottom = (box.bottom + dy).coerceIn(box.top + minSize, h)
            }
            Drag.BR -> {
                box.right = (box.right + dx).coerceIn(box.left + minSize, w)
                box.bottom = (box.bottom + dy).coerceIn(box.top + minSize, h)
            }
            Drag.NONE -> Unit
        }
    }
}
