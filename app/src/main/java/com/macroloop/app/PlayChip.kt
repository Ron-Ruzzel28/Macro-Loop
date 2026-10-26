package com.macroloop.app

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating Play button, like Macrorify's Play Mode: a small round button over every app.
 * Tap it to open a row of round buttons: collapse, Play, Settings, Close.
 * Drag it by the first button. The macro starts only when Play is pressed.
 */
class PlayChip(
    private val ctx: Context,
    private val wm: WindowManager,
    private val macroName: String,
    private var x: Int,
    private var y: Int,
    private val onMoved: (Int, Int) -> Unit,
    private val onPlay: () -> Unit,
    private val onClose: () -> Unit,
) {
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var settings: View? = null

    private fun dp(v: Int) = Ui.dp(ctx, v)

    fun show() {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        root = row
        lp = params
        build()
        wm.addView(row, params)
    }

    fun dismiss() {
        closeSettings()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun round(icon: Icon, color: Int = TokenInts.onPrimary, fill: Int = TokenInts.primary, onClick: () -> Unit): View {
        val f = FrameLayout(ctx).apply {
            background = Ui.rounded(fill, dp(26).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        }
        f.addView(IconView(ctx, icon, color), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        return f
    }

    private fun build() {
        val row = root ?: return
        row.removeAllViews()
        val size = dp(52)
        val margin = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }

        // The first button is the handle: drag it to move, tap it to open or close the row.
        val hand = round(Icon.TAP) { }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        hand.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        x = p.x; y = p.y
                        onMoved(x, y)
                        root?.let { wm.updateViewLayout(it, p) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    expanded = !expanded
                    closeSettings()
                    build()
                }
            }
            true
        }
        row.addView(hand, margin)
        if (expanded) {
            row.addView(round(Icon.PLAY) { onPlay() }, margin)
            row.addView(round(Icon.GEAR, TokenInts.text, TokenInts.surfaceHigh) { toggleSettings() }, margin)
            row.addView(round(Icon.CLOSE, TokenInts.text, TokenInts.surfaceHigh) { onClose() }, LinearLayout.LayoutParams(size, size))
        }
    }

    // ------------------------------------------------------------ the gear: two check boxes, like Macrorify

    private fun toggleSettings() {
        if (settings != null) {
            closeSettings()
            return
        }
        val prefs = ctx.getSharedPreferences(MacroService.PREFS, Context.MODE_PRIVATE)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = Ui.rounded(TokenInts.surface, dp(16).toFloat())
        }
        fun check(label: String, key: String) {
            val c = CheckBox(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(TokenInts.text)
                isChecked = prefs.getBoolean(key, false)
                minHeight = dp(44)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            }
            box.addView(c)
        }
        check("Show Detect Location", MacroService.PREF_SHOW_DETECT)
        check("Highlight Success Detection", MacroService.PREF_HIGHLIGHT)
        box.addView(
            TextView(ctx).apply {
                text = "CLOSE"
                textSize = 14f
                setTextColor(TokenInts.primary)
                gravity = Gravity.END
                setPadding(dp(8), dp(10), dp(8), dp(8))
                isClickable = true
                setOnClickListener { closeSettings() }
            },
        )
        val p = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y + dp(64)
        }
        wm.addView(box, p)
        settings = box
    }

    private fun closeSettings() {
        settings?.let { runCatching { wm.removeView(it) } }
        settings = null
    }
}
