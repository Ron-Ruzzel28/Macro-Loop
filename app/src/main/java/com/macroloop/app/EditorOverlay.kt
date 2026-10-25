package com.macroloop.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The floating editor: a round button you can drag, and a panel that opens from it.
 * Works over any app. Plain words on every button.
 */
class EditorOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    macro: Macro,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val macroId = macro.id
    private val macroName = macro.name
    private val repeat = macro.repeat
    private val steps = macro.steps.toMutableList()
    private var dirty = false

    private var bubble: View? = null
    private var panel: LinearLayout? = null
    private var fullLayer: View? = null     // pick-a-spot layer or crop layer
    private var matchBox: View? = null
    private var bubbleX = Ui.dp(ctx, 12)
    private var bubbleY = Ui.dp(ctx, 160)
    private var stepsText: TextView? = null

    /** Settings for the picture step being made. */
    private class Draft(
        val shot: Bitmap,
        val crop: Bitmap,
        val box: Rect,
        var threshold: Int = 90,
        var whole: Boolean = false,
        var timeoutSec: Int = 10,
        var skip: Boolean = false,
    )

    private var draft: Draft? = null
    private var resultText = ""

    fun show() {
        addBubble()
        openPanel()
    }

    fun dismiss() {
        removeFullLayer()
        removeMatchBox()
        removePanel()
        CaptureService.instance?.fast = false
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

    // ------------------------------------------------------------ main panel

    private fun showPanel(widthDp: Int = 280, build: (LinearLayout) -> Unit) {
        removePanel()
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
            background = Ui.rounded(TokenInts.surface, Ui.dp(ctx, 16).toFloat())
        }
        build(p)
        val params = overlayParams(Ui.dp(ctx, widthDp), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY + Ui.dp(ctx, 64)
        }
        wm.addView(p, params)
        panel = p
    }

    private fun openPanel() {
        showPanel { p ->
            p.addView(label(macroName, 20f, TokenInts.text))
            val list = label("", 14f, TokenInts.textMuted).also { stepsText = it }
            p.addView(list, lp(top = 4))
            refreshSteps()

            p.addView(Ui.button(ctx, "Find and tap a picture", TokenInts.primary, TokenInts.onPrimary) { startPictureFlow() }, lp(top = 12))
            p.addView(Ui.button(ctx, "Add a tap", TokenInts.surfaceHigh, TokenInts.text) { startPickingSpot() }, lp(top = 8))
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
        }
    }

    private fun showClosePrompt() {
        showPanel { p ->
            p.addView(label("Save your changes?", 20f, TokenInts.text))
            p.addView(Ui.button(ctx, "Keep editing", TokenInts.primary, TokenInts.onPrimary) { openPanel() }, lp(top = 12))
            p.addView(Ui.button(ctx, "Save and close", TokenInts.surfaceHigh, TokenInts.text) { save(); onClosed() }, lp(top = 8))
            p.addView(Ui.button(ctx, "Close without saving", TokenInts.surfaceHigh, TokenInts.danger) { onClosed() }, lp(top = 8))
        }
    }

    private fun requestClose() {
        if (dirty) showClosePrompt() else onClosed()
    }

    private fun refreshSteps() {
        stepsText?.text = if (steps.isEmpty()) {
            "No steps yet. Tap \"Find and tap a picture\" or \"Add a tap\" to start."
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
            removeFullLayer()
            openPanel()
        }
        layer.addView(
            cancel,
            FrameLayout.LayoutParams(Ui.dp(ctx, 160), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = Ui.dp(ctx, 96) },
        )
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                steps.add(Step(StepType.CLICK, x = e.rawX.toInt(), y = e.rawY.toInt()))
                dirty = true
                removeFullLayer()
                openPanel()
            }
            true
        }
        addFullLayer(layer)
    }

    // ------------------------------------------------------------ picture step

    private fun startPictureFlow() {
        if (!CaptureService.running) {
            showCaptureNeeded()
            return
        }
        if (!Matcher.ensureLoaded()) {
            toast("The picture finder could not start.")
            return
        }
        hideUi()
        scope.launch {
            CaptureService.instance?.fast = true
            delay(450)
            val shot = CaptureService.grab()
            CaptureService.instance?.fast = false
            showUi()
            if (shot == null) {
                toast("Could not see the screen. Try again.")
            } else {
                showCrop(shot)
            }
        }
    }

    private fun showCaptureNeeded() {
        showPanel { p ->
            p.addView(label("Allow screen capture", 20f, TokenInts.text))
            p.addView(
                label("MacroLoop needs to see the screen to find pictures. Android will ask you once.", 14f, TokenInts.textMuted),
                lp(top = 4),
            )
            p.addView(Ui.button(ctx, "Allow screen capture", TokenInts.primary, TokenInts.onPrimary) {
                removePanel()
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_ASK_CAPTURE, true),
                )
            }, lp(top = 12))
            p.addView(Ui.button(ctx, "Back", TokenInts.surfaceHigh, TokenInts.text) { openPanel() }, lp(top = 8))
        }
    }

    private fun showCrop(shot: Bitmap) {
        removePanel()
        val layer = FrameLayout(ctx)
        val cropView = CropView(ctx, shot)
        layer.addView(cropView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // The hint and buttons live in one bar. It sits at the top when the box is in the
        // lower half of the screen, and at the bottom otherwise, so it never covers the box.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
            background = Ui.rounded(TokenInts.surface, Ui.dp(ctx, 16).toFloat())
        }
        val hint = TextView(ctx).apply {
            text = "Drag the box around the picture to find. Drag a corner to resize."
            textSize = 16f
            setTextColor(TokenInts.text)
            setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        }
        bar.addView(hint)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.button(ctx, "Use this picture", TokenInts.primary, TokenInts.onPrimary) {
                val r = cropView.selection()
                if (r.width() < 24 || r.height() < 24) {
                    toast("Pick a bigger area.")
                } else {
                    val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                    draft = Draft(shot, crop, r)
                    resultText = ""
                    removeFullLayer()
                    showPictureSettings()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Ui.button(ctx, "Cancel", TokenInts.surfaceHigh, TokenInts.text) {
                removeFullLayer()
                openPanel()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) },
        )
        bar.addView(row)

        fun barParams(atTop: Boolean) = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            if (atTop) Gravity.TOP else Gravity.BOTTOM,
        ).apply {
            if (atTop) setMargins(Ui.dp(ctx, 16), Ui.dp(ctx, 48), Ui.dp(ctx, 16), 0)
            else setMargins(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 96))
        }
        var barAtTop = false
        layer.addView(bar, barParams(false))
        cropView.onBoxChanged = { b ->
            val wantTop = b.centerY() > shot.height / 2f
            if (wantTop != barAtTop) {
                barAtTop = wantTop
                bar.layoutParams = barParams(wantTop)
            }
        }
        addFullLayer(layer)
    }

    private fun showPictureSettings() {
        val d = draft ?: return
        showPanel(widthDp = 320) { p ->
            p.addView(label("Find and tap a picture", 20f, TokenInts.text))
            val preview = ImageView(ctx).apply {
                setImageBitmap(d.crop)
                adjustViewBounds = true
                maxHeight = Ui.dp(ctx, 90)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(TokenInts.surfaceHigh)
            }
            p.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) })

            if (resultText.isNotEmpty()) {
                val color = if (resultText.startsWith("Found")) TokenInts.success else TokenInts.warning
                p.addView(label(resultText, 16f, color), lp(top = 8))
            }

            p.addView(stepper("How exact?", { "${d.threshold}%" },
                { d.threshold = (d.threshold - 1).coerceAtLeast(70) },
                { d.threshold = (d.threshold + 1).coerceAtMost(99) }), lp(top = 8))
            p.addView(toggle("Where to look", { if (d.whole) "Whole screen" else "Near where I picked it" }, { d.whole = !d.whole }), lp(top = 4))
            p.addView(stepper("If not found after", { "${d.timeoutSec} s" },
                { d.timeoutSec = (d.timeoutSec - 1).coerceAtLeast(1) },
                { d.timeoutSec = (d.timeoutSec + 1).coerceAtMost(120) }), lp(top = 4))
            p.addView(toggle("If not found", { if (d.skip) "Skip this step" else "Stop the macro" }, { d.skip = !d.skip }), lp(top = 4))

            p.addView(Ui.button(ctx, "Test now", TokenInts.primary, TokenInts.onPrimary) { testNow() }, lp(top = 12))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                Ui.button(ctx, "Save step", TokenInts.surfaceHigh, TokenInts.text) { savePictureStep() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                Ui.button(ctx, "Cancel", TokenInts.surfaceHigh, TokenInts.danger) {
                    draft = null
                    resultText = ""
                    openPanel()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) },
            )
            p.addView(row, lp(top = 8))
        }
    }

    private fun testNow() {
        val d = draft ?: return
        hideUi()
        scope.launch {
            CaptureService.instance?.fast = true
            delay(450)
            val frame = CaptureService.grab()
            CaptureService.instance?.fast = false
            if (frame == null) {
                resultText = "Could not see the screen. Try again."
                showUi()
                showPictureSettings()
                return@launch
            }
            val region = if (d.whole) null
            else Matcher.nearRegion(d.box.left, d.box.top, d.box.width(), d.box.height(), 1.0, frame.width, frame.height)
            val res = withContext(Dispatchers.Default) { Matcher.find(frame, d.crop, region, d.threshold) }
            DebugState.record(frame, res, d.threshold, "New picture")
            if (!res.tooPlain) showMatchBox(res)
            delay(2500)
            removeMatchBox()
            showUi()
            resultText = when {
                res.tooPlain -> "This picture is too plain to find. Pick a bigger area with more detail."
                res.found -> "Found, ${(res.score * 100).toInt()}%"
                else -> "Not found. Best match was ${(res.score * 100).toInt()}%, needs ${d.threshold}%."
            }
            showPictureSettings()
        }
    }

    private fun savePictureStep() {
        val d = draft ?: return
        val id = TemplateStore.save(d.crop)
        val n = steps.count { it.type == StepType.CLICK_IMAGE } + 1
        steps.add(
            Step(
                type = StepType.CLICK_IMAGE,
                templateId = id,
                label = "Picture $n",
                threshold = d.threshold,
                wholeScreen = d.whole,
                timeoutMs = d.timeoutSec * 1000L,
                skipIfMissing = d.skip,
                boxX = d.box.left, boxY = d.box.top, boxW = d.box.width(), boxH = d.box.height(),
                capW = d.shot.width, capH = d.shot.height,
            ),
        )
        dirty = true
        draft = null
        resultText = ""
        openPanel()
    }

    private fun showMatchBox(res: Matcher.Result) {
        removeMatchBox()
        val color = if (res.found) TokenInts.success else TokenInts.warning
        val v = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(Ui.dp(ctx, 4), color)
            }
        }
        val params = WindowManager.LayoutParams(
            res.w, res.h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = res.x
            y = res.y
        }
        wm.addView(v, params)
        matchBox = v
    }

    private fun removeMatchBox() {
        matchBox?.let { runCatching { wm.removeView(it) } }
        matchBox = null
    }

    // ------------------------------------------------------------ small ui helpers

    private fun hideUi() {
        bubble?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
    }

    private fun showUi() {
        bubble?.visibility = View.VISIBLE
        panel?.visibility = View.VISIBLE
    }

    private fun stepper(title: String, value: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title, 16f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = label(value(), 16f, TokenInts.primary).apply { gravity = Gravity.CENTER }
        val small = { t: String, f: () -> Unit ->
            Ui.button(ctx, t, TokenInts.surfaceHigh, TokenInts.text) { f(); valueView.text = value() }.apply { minWidth = Ui.dp(ctx, 48) }
        }
        row.addView(small("-", onMinus), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 48)))
        row.addView(valueView, LinearLayout.LayoutParams(Ui.dp(ctx, 64), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(small("+", onPlus), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 48)))
        return row
    }

    private fun toggle(title: String, value: () -> String, onToggle: () -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 14f, TokenInts.textMuted))
        val b = Ui.button(ctx, value(), TokenInts.surfaceHigh, TokenInts.text) {}
        b.setOnClickListener { onToggle(); b.text = value() }
        box.addView(b, lp(top = 2))
        return box
    }

    private fun addFullLayer(layer: View) {
        removeFullLayer()
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
        fullLayer = layer
    }

    private fun removeFullLayer() {
        fullLayer?.let { runCatching { wm.removeView(it) } }
        fullLayer = null
    }

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
