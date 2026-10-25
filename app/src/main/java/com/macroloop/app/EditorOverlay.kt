package com.macroloop.app

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The floating editor: a round button you can drag, and a panel that opens from it.
 * Works over any app. Plain words on every button.
 */
class EditorOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    macro: Macro,
    private val onClosed: () -> Unit,
) {
    private val macroId = macro.id
    private val macroName = macro.name
    private val repeat = macro.repeat
    private val steps = macro.steps.toMutableList()
    private var dirty = false

    private var bubble: View? = null
    private var panel: LinearLayout? = null
    private var scrim: View? = null
    private var bubbleX = Ui.dp(ctx, 12)
    private var bubbleY = Ui.dp(ctx, 160)
    private var stepsText: TextView? = null
    private var confirming = false

    fun show() {
        addBubble()
        openPanel()
    }

    fun dismiss() {
        removeScrim()
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ------------------------------------------------------------ bubble

    private fun addBubble() {
        val size = Ui.dp(ctx, 56)
        val v = TextView(ctx).apply {
            text = "M"
            textSize = 22f
            setTextColor(TokenInts.onPrimary)
            gravity = Gravity.CENTER
            background = Ui.rounded(TokenInts.primary, size / 2f)
            contentDescription = "MacroLoop editor"
        }
        val lp = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        bubbleX = lp.x; bubbleY = lp.y
                        wm.updateViewLayout(v, lp)
                        if (panel != null) repositionPanel()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun togglePanel() {
        if (panel == null) openPanel() else removePanel()
    }

    // ------------------------------------------------------------ panel

    private fun openPanel() {
        removePanel()
        confirming = false
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
            background = Ui.rounded(TokenInts.surface, Ui.dp(ctx, 16).toFloat())
        }
        p.addView(label(macroName, 20f, TokenInts.text))
        val list = label("", 14f, TokenInts.textMuted).also { stepsText = it }
        p.addView(list, lp(top = 4))
        refreshSteps()

        p.addView(Ui.button(ctx, "Add a tap", TokenInts.primary, TokenInts.onPrimary) { startPickingSpot() }, lp(top = 12))
        p.addView(Ui.button(ctx, "Add a 1 second wait", TokenInts.surfaceHigh, TokenInts.text) {
            steps.add(Step(StepType.WAIT, ms = 1000))
            dirty = true
            refreshSteps()
        }, lp(top = 8))
        p.addView(Ui.button(ctx, "Remove last step", TokenInts.surfaceHigh, TokenInts.text) {
            if (steps.isNotEmpty()) {
                steps.removeAt(steps.lastIndex)
                dirty = true
                refreshSteps()
            }
        }, lp(top = 8))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.button(ctx, "Save", TokenInts.primary, TokenInts.onPrimary) { save(); toast("Saved") },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Ui.button(ctx, "Close", TokenInts.surfaceHigh, TokenInts.text) { requestClose() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) },
        )
        p.addView(row, lp(top = 8))

        val params = overlayParams(Ui.dp(ctx, 280), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY + Ui.dp(ctx, 64)
        }
        wm.addView(p, params)
        panel = p
    }

    private fun showClosePrompt() {
        val p = panel ?: return
        confirming = true
        p.removeAllViews()
        p.addView(label("Save your changes?", 20f, TokenInts.text))
        p.addView(Ui.button(ctx, "Keep editing", TokenInts.primary, TokenInts.onPrimary) { openPanel() }, lp(top = 12))
        p.addView(Ui.button(ctx, "Save and close", TokenInts.surfaceHigh, TokenInts.text) { save(); onClosed() }, lp(top = 8))
        p.addView(Ui.button(ctx, "Close without saving", TokenInts.surfaceHigh, TokenInts.danger) { onClosed() }, lp(top = 8))
    }

    private fun requestClose() {
        if (dirty) showClosePrompt() else onClosed()
    }

    private fun refreshSteps() {
        stepsText?.text = if (steps.isEmpty()) {
            "No steps yet. Tap \"Add a tap\" to start."
        } else {
            val shown = steps.takeLast(6)
            val offset = steps.size - shown.size
            (if (offset > 0) "... $offset earlier\n" else "") +
                shown.mapIndexed { i, s -> "${offset + i + 1}. ${s.describe()}" }.joinToString("\n")
        }
    }

    private fun repositionPanel() {
        val p = panel ?: return
        val lp = p.layoutParams as WindowManager.LayoutParams
        lp.x = bubbleX
        lp.y = bubbleY + Ui.dp(ctx, 64)
        wm.updateViewLayout(p, lp)
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        stepsText = null
    }

    // ------------------------------------------------------------ picking a spot

    private fun startPickingSpot() {
        removePanel()
        val layer = FrameLayout(ctx).apply { setBackgroundColor(TokenInts.scrim) }
        val hint = TextView(ctx).apply {
            text = "Tap the spot where the macro should tap"
            textSize = 20f
            setTextColor(TokenInts.text)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 32), 0, Ui.dp(ctx, 32), 0)
        }
        layer.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val cancel = Ui.button(ctx, "Cancel", TokenInts.surfaceHigh, TokenInts.text) {
            removeScrim()
            openPanel()
        }
        layer.addView(
            cancel,
            FrameLayout.LayoutParams(Ui.dp(ctx, 160), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = Ui.dp(ctx, 96) },
        )
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                steps.add(Step(StepType.CLICK, x = x, y = y))
                dirty = true
                removeScrim()
                openPanel()
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(layer, params)
        scrim = layer
    }

    private fun removeScrim() {
        scrim?.let { runCatching { wm.removeView(it) } }
        scrim = null
    }

    // ------------------------------------------------------------ helpers

    private fun save() {
        MacroRepository.save(Macro(id = macroId, name = macroName, steps = steps.toList(), repeat = repeat))
        dirty = false
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Ui.dp(ctx, top) }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )
}
