package com.macroloop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** Icons for the editor toolbar. Drawn here, so there are no image files. */
enum class Icon { TAP, SWIPE, PICTURE, PLAY, EYE, EYE_OFF, LIST, SAVE, CLOSE, BACK, PLUS, SHRINK, MORE, GEAR, CHECK, REDO, COPY, PENCIL }

class IconView(context: Context, var icon: Icon, private val color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@IconView.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = this@IconView.color
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 100f
        c.save()
        c.translate((width - 100 * s) / 2f, (height - 100 * s) / 2f)
        c.scale(s, s)
        when (icon) {
            Icon.TAP -> {
                c.drawCircle(50f, 50f, 32f, stroke)
                c.drawCircle(50f, 50f, 11f, fill)
            }
            Icon.SWIPE -> {
                c.drawCircle(22f, 72f, 7f, fill)
                c.drawLine(22f, 72f, 76f, 28f, stroke)
                val p = Path().apply {
                    moveTo(58f, 28f); lineTo(78f, 26f); lineTo(76f, 46f)
                }
                c.drawPath(p, stroke)
            }
            Icon.PICTURE -> {
                c.drawRoundRect(RectF(14f, 22f, 86f, 78f), 8f, 8f, stroke)
                val p = Path().apply {
                    moveTo(22f, 70f); lineTo(42f, 46f); lineTo(58f, 62f); lineTo(66f, 52f); lineTo(78f, 70f)
                }
                c.drawPath(p, stroke)
                c.drawCircle(68f, 38f, 6f, fill)
            }
            Icon.PLAY -> {
                val p = Path().apply { moveTo(30f, 20f); lineTo(30f, 80f); lineTo(80f, 50f); close() }
                c.drawPath(p, fill)
            }
            Icon.EYE, Icon.EYE_OFF -> {
                c.drawOval(RectF(10f, 30f, 90f, 70f), stroke)
                c.drawCircle(50f, 50f, 11f, fill)
                if (icon == Icon.EYE_OFF) c.drawLine(20f, 82f, 80f, 18f, stroke)
            }
            Icon.LIST -> {
                for (y in listOf(30f, 50f, 70f)) {
                    c.drawCircle(22f, y, 4f, fill)
                    c.drawLine(36f, y, 80f, y, stroke)
                }
            }
            Icon.SAVE -> {
                c.drawRoundRect(RectF(18f, 18f, 82f, 82f), 8f, 8f, stroke)
                c.drawRect(RectF(32f, 18f, 66f, 38f), stroke)
                c.drawRect(RectF(30f, 56f, 70f, 82f), stroke)
            }
            Icon.BACK -> {
                c.drawLine(78f, 50f, 24f, 50f, stroke)
                val p = Path().apply { moveTo(46f, 28f); lineTo(24f, 50f); lineTo(46f, 72f) }
                c.drawPath(p, stroke)
            }
            Icon.GEAR -> {
                c.drawCircle(50f, 50f, 22f, stroke)
                c.drawCircle(50f, 50f, 8f, fill)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    c.drawLine(
                        (50 + 28 * Math.cos(a)).toFloat(), (50 + 28 * Math.sin(a)).toFloat(),
                        (50 + 38 * Math.cos(a)).toFloat(), (50 + 38 * Math.sin(a)).toFloat(), stroke,
                    )
                }
            }
            Icon.CHECK -> {
                val p = Path().apply { moveTo(20f, 52f); lineTo(42f, 74f); lineTo(80f, 28f) }
                c.drawPath(p, stroke)
            }
            Icon.REDO -> {
                c.drawArc(RectF(22f, 22f, 78f, 78f), -40f, 280f, false, stroke)
                val p = Path().apply { moveTo(66f, 14f); lineTo(80f, 30f); lineTo(60f, 34f) }
                c.drawPath(p, stroke)
            }
            Icon.COPY -> {
                c.drawRoundRect(RectF(30f, 30f, 80f, 82f), 6f, 6f, stroke)
                c.drawRoundRect(RectF(18f, 16f, 62f, 66f), 6f, 6f, stroke)
            }
            Icon.PENCIL -> {
                c.drawLine(24f, 76f, 70f, 30f, stroke)
                c.drawLine(24f, 76f, 24f, 62f, stroke)
                c.drawLine(24f, 76f, 38f, 76f, stroke)
            }
            Icon.SHRINK -> {
                c.drawLine(24f, 24f, 46f, 46f, stroke)
                c.drawLine(76f, 76f, 54f, 54f, stroke)
                val a = Path().apply { moveTo(46f, 28f); lineTo(46f, 46f); lineTo(28f, 46f) }
                val b = Path().apply { moveTo(54f, 72f); lineTo(54f, 54f); lineTo(72f, 54f) }
                c.drawPath(a, stroke)
                c.drawPath(b, stroke)
            }
            Icon.MORE -> {
                c.drawCircle(50f, 24f, 8f, fill)
                c.drawCircle(50f, 50f, 8f, fill)
                c.drawCircle(50f, 76f, 8f, fill)
            }
            Icon.PLUS -> {
                c.drawLine(50f, 22f, 50f, 78f, stroke)
                c.drawLine(22f, 50f, 78f, 50f, stroke)
            }
            Icon.CLOSE -> {
                c.drawLine(26f, 26f, 74f, 74f, stroke)
                c.drawLine(74f, 26f, 26f, 74f, stroke)
            }
        }
        c.restore()
    }
}

/** A toolbar button: an icon with a short word under it. At least 48 dp, so it is easy to hit. */
class ToolButton(
    context: Context,
    icon: Icon,
    label: String,
    color: Int = TokenInts.primary,
    onClick: () -> Unit,
) : LinearLayout(context) {
    val iconView = IconView(context, icon, color)
    private val text = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(Ui.dp(context, 2), Ui.dp(context, 5), Ui.dp(context, 2), Ui.dp(context, 3))
        background = Ui.rounded(TokenInts.surfaceHigh, Ui.dp(context, 12).toFloat())
        isClickable = true
        contentDescription = label
        addView(iconView, LayoutParams(Ui.dp(context, 26), Ui.dp(context, 26)))
        text.text = label
        text.textSize = 11f
        text.setTextColor(TokenInts.text)
        text.gravity = Gravity.CENTER
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
    }

    fun setIcon(icon: Icon, label: String) {
        iconView.icon = icon
        iconView.invalidate()
        text.text = label
    }
}

/**
 * A numbered marker that sits on the real screen: a dot for a tap, two dots for a swipe,
 * a box with a resize corner for a picture. Drag to move; tap to open its settings.
 * Each marker is its own overlay window.
 */
class MarkerView(
    context: Context,
    val kind: Kind,
    var badge: String,
    private val accent: Int = TokenInts.primary,
    private val wm: WindowManager,
    val lp: WindowManager.LayoutParams,
    private val onChanged: (MarkerView) -> Unit,
    private val onTapped: (MarkerView) -> Unit,
) : View(context) {

    enum class Kind { TAP, SWIPE_START, SWIPE_END, PICTURE, CONDITION }

    private val density = context.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = accent
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TokenInts.onPrimary
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0
    private var moved = false
    private var resizing = false

    private fun handleSize() = 28f * density

    override fun onDraw(c: Canvas) {
        when (kind) {
            Kind.TAP, Kind.SWIPE_START, Kind.SWIPE_END -> {
                val r = minOf(width, height) / 2f
                body.color = if (kind == Kind.SWIPE_END) 0xCCFBBF24.toInt() else 0xCC2DD4BF.toInt()
                c.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth, body)
                c.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth, ring)
                numberPaint.textSize = r * 0.9f
                val label = if (kind == Kind.SWIPE_END) "end" else badge
                if (kind == Kind.SWIPE_END) numberPaint.textSize = r * 0.55f
                c.drawText(label, width / 2f, height / 2f + numberPaint.textSize * 0.35f, numberPaint)
            }
            Kind.PICTURE, Kind.CONDITION -> {
                body.color = (accent and 0x00FFFFFF) or 0x22000000
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), body)
                c.drawRect(1.5f * density, 1.5f * density, width - 1.5f * density, height - 1.5f * density, border)
                // number badge, top left
                body.color = accent
                val b = 26f * density
                c.drawRect(0f, 0f, b * (if (badge.length > 1) 1.6f else 1f), b, body)
                numberPaint.textSize = b * 0.62f
                c.drawText(badge, b * (if (badge.length > 1) 1.6f else 1f) / 2f, b / 2f + numberPaint.textSize * 0.35f, numberPaint)
                // resize corner, bottom right
                val hs = handleSize() * 0.7f
                val path = Path().apply {
                    moveTo(width.toFloat(), height - hs)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(width - hs, height.toFloat())
                    close()
                }
                c.drawPath(path, body)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = lp.x; startY = lp.y; startW = lp.width; startH = lp.height
                moved = false
                resizing = (kind == Kind.PICTURE || kind == Kind.CONDITION) && e.x > width - handleSize() && e.y > height - handleSize()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).toInt()
                val dy = (e.rawY - downY).toInt()
                if (abs(dx) > 10 || abs(dy) > 10) moved = true
                if (moved) {
                    if (resizing) {
                        lp.width = max(startW + dx, (48 * density).toInt())
                        lp.height = max(startH + dy, (48 * density).toInt())
                    } else {
                        lp.x = startX + dx
                        lp.y = startY + dy
                    }
                    wm.updateViewLayout(this, lp)
                    onChanged(this)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTapped(this) else onChanged(this)
            }
        }
        return true
    }
}

/** Draws a line from the start to the end of every swipe. Never takes touches. */
class LinesView(context: Context) : View(context) {
    var lines: List<FloatArray> = emptyList() // x1, y1, x2, y2 in screen pixels
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFBBF24.toInt()
        strokeWidth = 4f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        for (l in lines) c.drawLine(l[0], l[1], l[2], l[3], paint)
    }
}


/** A small round icon button (44 dp touch target) with no label. */
class IconBtn(
    context: Context,
    icon: Icon,
    color: Int = TokenInts.primary,
    sizeDp: Int = 44,
    onClick: () -> Unit,
) : android.widget.FrameLayout(context) {
    val iconView = IconView(context, icon, color)

    init {
        background = Ui.rounded(TokenInts.surfaceHigh, Ui.dp(context, sizeDp / 2).toFloat())
        isClickable = true
        addView(iconView, LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }
}

/** A scroll view that never grows taller than [maxPx], so a short list hugs its content. */
class MaxHeightScrollView(context: Context, private val maxPx: Int) : android.widget.ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}
