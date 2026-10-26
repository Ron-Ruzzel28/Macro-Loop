package com.macroloop.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The editor, in the style of an on-screen toolbar:
 *  - a toolbar docked at the top of the screen, over whatever app is behind it,
 *  - numbered markers placed on the real screen (a dot for a tap, two dots for a swipe,
 *    a box for a picture),
 *  - tap a marker to open that step's settings in a sheet at the bottom.
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
    private val root = macro.steps.toMutableList()
    /** The list being edited: the main list, or a branch opened from a step. */
    private var steps: MutableList<Step> = root

    private class Ctx(val parent: MutableList<Step>, val index: Int, val onSuccess: Boolean)

    private val stack = ArrayList<Ctx>()
    private var backButton: ToolButton? = null
    private var dirty = false
    private var markersShown = true

    private val bounds = wm.currentWindowMetrics.bounds
    private val screenW = bounds.width()
    private val screenH = bounds.height()

    private var toolbar: View? = null
    private var infoText: TextView? = null
    private var eyeButton: ToolButton? = null
    private var linesView: LinesView? = null
    private val markers = mutableListOf<MarkerView>()
    private val owner = HashMap<MarkerView, Pair<Int, Int>>() // step index, condition index (-1 = the step itself)
    private var sheet: View? = null
    private var sheetIndex = -1
    private var sheetTab = 0
    private var dialog: View? = null
    private val results = HashMap<Int, String>()

    private fun dp(v: Int) = Ui.dp(ctx, v)

    // ================================================================ lifecycle

    fun show() {
        addLines()
        addToolbar()
        enterListMode()
    }

    fun dismiss() {
        closeSheet()
        closeDialog()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        removeMatchBox()
        clearMarkers()
        linesView?.let { runCatching { wm.removeView(it) } }
        linesView = null
        toolbar?.let { runCatching { wm.removeView(it) } }
        toolbar = null
        CaptureService.instance?.fast = false
    }

    // ================================================================ toolbar

    private fun addToolbar() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = Ui.rounded(0xF01A1D24.toInt(), dp(16).toFloat())
        }
        fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(ToolButton(ctx, Icon.TAP, "Tap") { addTap() }, weight())
        row1.addView(ToolButton(ctx, Icon.SWIPE, "Swipe") { addSwipe() }, weight())
        row1.addView(ToolButton(ctx, Icon.PICTURE, "Picture") { addPicture() }, weight())
        row1.addView(ToolButton(ctx, Icon.PLAY, "Test run", TokenInts.success) { testRun() }, weight())
        val eye = ToolButton(ctx, Icon.EYE, "Markers") { toggleMarkers() }
        eyeButton = eye
        row1.addView(eye, weight())
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = TextView(ctx).apply {
            textSize = 14f
            setTextColor(TokenInts.text)
            maxLines = 2
            setPadding(dp(8), 0, dp(4), 0)
        }
        infoText = info
        row2.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        val back = ToolButton(ctx, Icon.BACK, "Back") { exitBranch() }
        back.visibility = View.GONE
        backButton = back
        row2.addView(back, weight())
        row2.addView(ToolButton(ctx, Icon.LIST, "List") { enterListMode() }, weight())
        row2.addView(ToolButton(ctx, Icon.SAVE, "Save") { save(); toast("Saved") }, weight())
        row2.addView(ToolButton(ctx, Icon.CLOSE, "Close", TokenInts.danger) { requestClose() }, weight())
        root.addView(row2)
        updateInfo()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(26)
        }
        wm.addView(root, params)
        toolbar = root
    }

    private fun updateInfo() {
        val n = steps.size
        val where = if (stack.isEmpty()) macroName else {
            val sb = StringBuilder(macroName)
            for (c in stack) {
                sb.append(" > step ${c.index + 1} ").append(if (c.onSuccess) "on success" else "on fail")
            }
            sb.toString()
        }
        infoText?.text = "$where\n${if (n == 1) "1 step" else "$n steps"}${if (dirty) " (not saved)" else ""}"
        backButton?.visibility = if (stack.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The whole macro with every open branch written back in. */
    private fun snapshotRoot(): List<Step> {
        var cur: List<Step> = steps.toList()
        for (ctx in stack.asReversed()) {
            val p = ctx.parent.toMutableList()
            p[ctx.index] = if (ctx.onSuccess) p[ctx.index].copy(onSuccess = cur) else p[ctx.index].copy(onFail = cur)
            cur = p
        }
        return cur
    }

    private fun flat(list: List<Step>): List<Step> = list.flatMap { listOf(it) + flat(it.onSuccess) + flat(it.onFail) }

    private fun enterBranch(i: Int, onSuccess: Boolean, silent: Boolean = false) {
        if (i !in steps.indices) return
        closeSheet()
        val s = steps[i]
        stack.add(Ctx(steps, i, onSuccess))
        steps = (if (onSuccess) s.onSuccess else s.onFail).toMutableList()
        results.clear()
        condResults.clear()
        if (silent) return
        rebuildMarkers()
        updateInfo()
        toast(
            if (onSuccess) "Steps here run after step ${i + 1} works."
            else "Steps here run when step ${i + 1} never finds what it needs.",
        )
    }

    private fun exitBranch(silent: Boolean = false) {
        if (stack.isEmpty()) return
        closeSheet()
        val ctx = stack.removeAt(stack.lastIndex)
        val p = ctx.parent
        p[ctx.index] = if (ctx.onSuccess) p[ctx.index].copy(onSuccess = steps.toList()) else p[ctx.index].copy(onFail = steps.toList())
        steps = p
        results.clear()
        condResults.clear()
        if (silent) return
        rebuildMarkers()
        updateInfo()
    }

    // ================================================================ adding steps

    private fun addTap() {
        val off = (steps.size % 6) * dp(36)
        addStep(Step(StepType.CLICK, x = screenW / 2 + off - dp(90), y = screenH / 2 + off - dp(90)))
        changed()
        toast("Drag the dot to the spot. Tap it for settings.")
    }

    private fun addSwipe() {
        addStep(Step(StepType.SWIPE, x = screenW / 2, y = (screenH * 0.65).toInt(), x2 = screenW / 2, y2 = (screenH * 0.35).toInt()))
        changed()
        toast("Drag both dots. Tap one for settings.")
    }

    private fun addPicture() {
        if (!CaptureService.running) {
            showCaptureNeeded()
            return
        }
        if (!Matcher.ensureLoaded()) {
            toast("The picture finder could not start.")
            return
        }
        val n = steps.count { it.type == StepType.CLICK_IMAGE } + 1
        val w = (screenW * 0.38).toInt()
        val h = (w * 0.55).toInt()
        addStep(
            Step(
                type = StepType.CLICK_IMAGE,
                label = "Picture $n",
                boxX = (screenW - w) / 2, boxY = (screenH - h) / 2, boxW = w, boxH = h,
                capW = screenW, capH = screenH,
            ),
        )
        changed()
        toast("Move the box over the picture, then tap it.")
    }

    private var insertAt: Int? = null

    private fun addStep(st: Step) {
        val at = insertAt
        if (at != null && at in 0..steps.size) steps.add(at, st) else steps.add(st)
        insertAt = null
    }

    private fun changed() {
        dirty = true
        rebuildMarkers()
        updateInfo()
    }

    // ================================================================ markers

    private fun clearMarkers() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        owner.clear()
    }

    private fun addLines() {
        val v = LinesView(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(v, params)
        linesView = v
    }

    private fun updateLines() {
        linesView?.lines = steps.filter { it.type == StepType.SWIPE }
            .map { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.x2.toFloat(), it.y2.toFloat()) }
    }

    private fun letter(j: Int): String = ('a' + j).toString()

    private fun rebuildMarkers() {
        clearMarkers()
        val dot = dp(52)
        steps.forEachIndexed { i, s ->
            val n = (i + 1).toString()
            when (s.type) {
                StepType.CLICK -> addMarker(MarkerView.Kind.TAP, n, s.x - dot / 2, s.y - dot / 2, dot, dot, i)
                StepType.SWIPE -> {
                    addMarker(MarkerView.Kind.SWIPE_START, n, s.x - dot / 2, s.y - dot / 2, dot, dot, i)
                    addMarker(MarkerView.Kind.SWIPE_END, n, s.x2 - dot / 2, s.y2 - dot / 2, dot, dot, i)
                }
                StepType.CLICK_IMAGE -> addMarker(MarkerView.Kind.PICTURE, n, s.boxX, s.boxY, s.boxW, s.boxH, i)
                StepType.WAIT, StepType.STOP_MACRO -> Unit
            }
            s.conditions.forEachIndexed { j, c ->
                val accent = if (c.mustAppear) TokenInts.warning else TokenInts.danger
                addMarker(
                    MarkerView.Kind.CONDITION, n + letter(j),
                    c.boxX, c.boxY, c.boxW.coerceAtLeast(dp(48)), c.boxH.coerceAtLeast(dp(48)),
                    i, j, accent,
                )
            }
        }
        updateLines()
        applyVisibility(true)
    }

    private fun addMarker(
        kind: MarkerView.Kind, badge: String, left: Int, top: Int, w: Int, h: Int,
        stepIndex: Int, condIndex: Int = -1, accent: Int = TokenInts.primary,
    ) {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top
        }
        val v = MarkerView(ctx, kind, badge, accent, wm, lp, { markerChanged(it) }, { tapped ->
            val o = owner[tapped]
            if (o != null) {
                if (o.second >= 0) openConditionSheet(o.first, o.second) else openSheet(o.first)
            }
        })
        wm.addView(v, lp)
        markers.add(v)
        owner[v] = stepIndex to condIndex
    }

    private fun markerChanged(v: MarkerView) {
        val (i, j) = owner[v] ?: return
        if (i !in steps.indices) return
        val s = steps[i]
        val cx = v.lp.x + v.lp.width / 2
        val cy = v.lp.y + v.lp.height / 2
        steps[i] = when (v.kind) {
            MarkerView.Kind.TAP, MarkerView.Kind.SWIPE_START -> s.copy(x = cx, y = cy)
            MarkerView.Kind.SWIPE_END -> s.copy(x2 = cx, y2 = cy)
            MarkerView.Kind.PICTURE -> s.copy(boxX = v.lp.x, boxY = v.lp.y, boxW = v.lp.width, boxH = v.lp.height)
            MarkerView.Kind.CONDITION -> s.copy(
                conditions = s.conditions.mapIndexed { k, c ->
                    if (k == j) c.copy(boxX = v.lp.x, boxY = v.lp.y, boxW = v.lp.width, boxH = v.lp.height) else c
                },
            )
        }
        dirty = true
        updateLines()
        updateInfo()
    }

    private fun toggleMarkers() {
        markersShown = !markersShown
        eyeButton?.setIcon(if (markersShown) Icon.EYE else Icon.EYE_OFF, if (markersShown) "Markers" else "Hidden")
        applyVisibility(true)
    }

    /** Shows or hides every editor window. The list and the markers are two modes of the same editor. */
    private fun applyVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        val toolbarOn = visible && !listMode
        val panelOn = visible && listMode
        toolbar?.visibility = if (toolbarOn) View.VISIBLE else View.INVISIBLE
        setTouchable(toolbar, toolbarOn)
        panel?.visibility = if (panelOn) View.VISIBLE else View.INVISIBLE
        setTouchable(panel, panelOn)
        sheet?.visibility = v
        dialog?.visibility = v
        val mv = if (toolbarOn && markersShown) View.VISIBLE else View.INVISIBLE
        markers.forEach { it.visibility = mv }
        linesView?.visibility = mv
    }

    /** A hidden window must also stop taking touches, or it would block the app underneath. */
    private fun setTouchable(v: View?, touchable: Boolean) {
        val lp = v?.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val has = lp.flags and flag != 0
        if (touchable && has) {
            lp.flags = lp.flags and flag.inv()
            wm.updateViewLayout(v, lp)
        } else if (!touchable && !has) {
            lp.flags = lp.flags or flag
            wm.updateViewLayout(v, lp)
        }
    }

    /** Hides the editor, waits for the screen to redraw, runs [block], and shows the editor again. */
    private suspend fun <T> withEditorHidden(block: () -> T): T {
        applyVisibility(false)
        CaptureService.instance?.fast = true
        delay(420)
        val r = block()
        CaptureService.instance?.fast = false
        applyVisibility(true)
        return r
    }

    // ================================================================ step settings sheet

    private fun openSheet(index: Int, tab: Int = 0) {
        if (index !in steps.indices) return
        val s0 = steps[index]
        if (s0.type == StepType.CLICK_IMAGE && s0.templateId.isEmpty()) {
            captureTemplate(index) { openSheet(index, tab) }
            return
        }
        closeSheet()
        sheetIndex = index
        sheetTab = tab
        val s = steps[index]

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(56))
            background = GradientDrawable().apply {
                setColor(0xF51A1D24.toInt())
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = when (s.type) {
            StepType.CLICK -> "Step ${index + 1}: Tap"
            StepType.SWIPE -> "Step ${index + 1}: Swipe"
            StepType.CLICK_IMAGE -> "Step ${index + 1}: Find a picture"
            StepType.WAIT -> "Step ${index + 1}: Wait"
            StepType.STOP_MACRO -> "Step ${index + 1}: Stop the macro"
        }
        header.addView(label(title, 20f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(ctx, "Done", TokenInts.primary, TokenInts.onPrimary) { closeSheet() })
        root.addView(header)

        if (s.type != StepType.WAIT && s.type != StepType.STOP_MACRO) {
            val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("General", "Action").forEachIndexed { t, name ->
                val on = t == tab
                tabs.addView(
                    Ui.button(ctx, name, if (on) TokenInts.surfaceHigh else Color.TRANSPARENT, if (on) TokenInts.primary else TokenInts.textMuted) {
                        openSheet(index, t)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(8), dp(6), 0) },
                )
            }
            root.addView(tabs)
        }

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (tab == 0) buildGeneral(body, index) else buildAction(body, index)
        val scroll = ScrollView(ctx).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        root.addView(
            Ui.button(ctx, "Delete this step", TokenInts.surfaceHigh, TokenInts.danger) {
                steps.removeAt(index)
                closeSheet()
                changed()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )

        addSheetWindow(root)
    }

    private fun addSheetWindow(root: View, heightFrac: Double = 0.56) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenH * heightFrac).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(root, params)
        sheet = root
    }

    private fun closeSheet() {
        sheet?.let { runCatching { wm.removeView(it) } }
        sheet = null
        sheetIndex = -1
    }

    private fun edit(i: Int, f: (Step) -> Step) {
        if (i !in steps.indices) return
        steps[i] = f(steps[i])
        dirty = true
        updateInfo()
    }

    private fun buildGeneralBase(body: LinearLayout, i: Int) {
        val s = steps[i]
        when (s.type) {
            StepType.CLICK -> {
                body.addView(label("The macro taps at (${s.x}, ${s.y}).", 16f, TokenInts.text), lp(8))
                body.addView(label("Drag the numbered dot on the screen to move it.", 14f, TokenInts.textMuted), lp(4))
            }
            StepType.SWIPE -> {
                body.addView(label("From (${s.x}, ${s.y}) to (${s.x2}, ${s.y2}).", 16f, TokenInts.text), lp(8))
                body.addView(label("Drag the two dots to move them. Longer swipes are slower.", 14f, TokenInts.textMuted), lp(4))
                body.addView(
                    stepper("How long", { "${steps[i].swipeMs} ms" },
                        { edit(i) { it.copy(swipeMs = (it.swipeMs - 50).coerceAtLeast(100)) } },
                        { edit(i) { it.copy(swipeMs = (it.swipeMs + 50).coerceAtMost(3000)) } }),
                    lp(12),
                )
            }
            StepType.CLICK_IMAGE -> buildPictureGeneral(body, i)
            StepType.STOP_MACRO -> {
                body.addView(label("When the macro gets here, it stops.", 16f, TokenInts.text), lp(8))
            }
            StepType.WAIT -> {
                body.addView(
                    stepper("Wait", { "${steps[i].ms / 1000.0} s" },
                        { edit(i) { it.copy(ms = (it.ms - 500).coerceAtLeast(500)) } },
                        { edit(i) { it.copy(ms = (it.ms + 500).coerceAtMost(60_000)) } }),
                    lp(12),
                )
            }
        }
    }

    private fun buildGeneral(body: LinearLayout, i: Int) {
        buildGeneralBase(body, i)
        if (steps[i].type != StepType.WAIT && steps[i].type != StepType.STOP_MACRO) {
            buildConditions(body, i)
            buildBranches(body, i)
        }
    }

    /** "Only run this step when..." : pictures that must (or must not) be on screen. */
    private fun buildConditions(body: LinearLayout, i: Int) {
        val s = steps[i]
        body.addView(label("Only run this step when...", 16f, TokenInts.text).apply { setPadding(0, dp(8), 0, 0) }, lp(16))
        if (s.conditions.isEmpty()) {
            body.addView(label("Nothing yet. A condition is a picture that must be on screen, or must not be.", 14f, TokenInts.textMuted), lp(4))
        } else {
            if (s.conditions.size > 1) {
                body.addView(toggle("When there are several", { if (steps[i].anyOf) "Any one is enough" else "All must be true" }) {
                    edit(i) { it.copy(anyOf = !it.anyOf) }
                }, lp(8))
            }
            s.conditions.forEachIndexed { j, c ->
                val text = "${letter(j)}.  ${c.label}: " + (if (c.mustAppear) "must be on screen" else "must NOT be on screen") + " (${c.threshold}%)"
                body.addView(Ui.button(ctx, text, TokenInts.surfaceHigh, TokenInts.text) { openConditionSheet(i, j) }, lp(6))
            }
        }
        body.addView(Ui.button(ctx, "Add a condition", TokenInts.surfaceHigh, TokenInts.primary) { addCondition(i) }, lp(8))
        if (s.conditions.isNotEmpty() && s.type != StepType.CLICK_IMAGE) {
            body.addView(
                stepper("Wait for it up to", { "${steps[i].timeoutMs / 1000} s" },
                    { edit(i) { it.copy(timeoutMs = (it.timeoutMs - 1000).coerceAtLeast(1000)) } },
                    { edit(i) { it.copy(timeoutMs = (it.timeoutMs + 1000).coerceAtMost(120_000)) } }),
                lp(8),
            )
            body.addView(toggle("If it never happens", { if (steps[i].skipIfMissing) "Skip this step" else "Stop the macro" }) {
                edit(i) { it.copy(skipIfMissing = !it.skipIfMissing) }
            }, lp(8))
        }
    }

    /** "After this step": the On success and On fail lists. */
    private fun buildBranches(body: LinearLayout, i: Int) {
        val s = steps[i]
        body.addView(label("After this step...", 16f, TokenInts.text).apply { setPadding(0, dp(8), 0, 0) }, lp(16))
        body.addView(
            label(
                "On success runs when the step worked. On fail runs when its picture or conditions never showed up. " +
                    "With nothing in On fail, the macro stops (or skips, if you chose that).",
                14f, TokenInts.textMuted,
            ),
            lp(4),
        )
        fun count(n: Int) = if (n == 0) "nothing yet" else if (n == 1) "1 step" else "$n steps"
        body.addView(Ui.button(ctx, "On success: ${count(s.onSuccess.size)}   >", TokenInts.surfaceHigh, TokenInts.success) { enterBranch(i, true) }, lp(8))
        body.addView(Ui.button(ctx, "On fail: ${count(s.onFail.size)}   >", TokenInts.surfaceHigh, TokenInts.warning) { enterBranch(i, false) }, lp(8))
    }

    private fun addCondition(i: Int) {
        if (!CaptureService.running) {
            showCaptureNeeded()
            return
        }
        if (!Matcher.ensureLoaded()) {
            toast("The picture finder could not start.")
            return
        }
        val n = steps[i].conditions.size
        val w = (screenW * 0.30).toInt()
        val h = (w * 0.5).toInt()
        val c = Condition(
            label = "Condition ${i + 1}${letter(n)}",
            boxX = ((screenW - w) / 2 + n * dp(24)).coerceAtLeast(0),
            boxY = ((screenH - h) / 3 + n * dp(24)).coerceAtLeast(0),
            boxW = w, boxH = h, capW = screenW, capH = screenH,
        )
        edit(i) { it.copy(conditions = it.conditions + c) }
        closeSheet()
        rebuildMarkers()
        toast("Move the box over what must be on screen, then tap it.")
    }

    private fun editCond(i: Int, j: Int, f: (Condition) -> Condition) {
        edit(i) { st -> st.copy(conditions = st.conditions.mapIndexed { k, c -> if (k == j) f(c) else c }) }
    }

    private fun openConditionSheet(i: Int, j: Int) {
        if (i !in steps.indices || j !in steps[i].conditions.indices) return
        if (steps[i].conditions[j].templateId.isEmpty()) {
            captureConditionTemplate(i, j) { openConditionSheet(i, j) }
            return
        }
        closeSheet()
        sheetIndex = i
        val c = steps[i].conditions[j]
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(56))
            background = GradientDrawable().apply {
                setColor(0xF51A1D24.toInt())
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Step ${i + 1}: condition ${letter(j)}", 20f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(ctx, "Done", TokenInts.primary, TokenInts.onPrimary) { closeSheet(); openSheet(i, 0) })
        root.addView(header)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        TemplateStore.load(c.templateId)?.let { bmp ->
            val preview = ImageView(ctx).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = dp(80)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(TokenInts.surfaceHigh)
            }
            body.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        condResults[i * 100 + j]?.let {
            body.addView(label(it, 16f, if (it.startsWith("On screen") || it.startsWith("Not on screen, as")) TokenInts.success else TokenInts.warning), lp(8))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.button(ctx, "Capture again", TokenInts.surfaceHigh, TokenInts.text) { captureConditionTemplate(i, j) { openConditionSheet(i, j) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Ui.button(ctx, "Test now", TokenInts.primary, TokenInts.onPrimary) { testCondition(i, j) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
        )
        body.addView(row, lp(8))
        body.addView(toggle("This picture must be", { if (steps[i].conditions[j].mustAppear) "On screen" else "Not on screen" }) {
            editCond(i, j) { it.copy(mustAppear = !it.mustAppear) }
            rebuildMarkers()
        }, lp(12))
        body.addView(
            stepper("How exact?", { "${steps[i].conditions[j].threshold}%" },
                { editCond(i, j) { it.copy(threshold = (it.threshold - 1).coerceAtLeast(70)) } },
                { editCond(i, j) { it.copy(threshold = (it.threshold + 1).coerceAtMost(99)) } }),
            lp(8),
        )
        body.addView(toggle("Where to look", { if (steps[i].conditions[j].wholeScreen) "Whole screen" else "Near the box" }) {
            editCond(i, j) { it.copy(wholeScreen = !it.wholeScreen) }
        }, lp(8))
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        root.addView(
            Ui.button(ctx, "Delete this condition", TokenInts.surfaceHigh, TokenInts.danger) {
                edit(i) { st -> st.copy(conditions = st.conditions.filterIndexed { k, _ -> k != j }) }
                closeSheet()
                rebuildMarkers()
                openSheet(i, 0)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        addSheetWindow(root)
    }

    private val condResults = HashMap<Int, String>()

    private fun captureConditionTemplate(i: Int, j: Int, then: () -> Unit) {
        scope.launch {
            val frame = withEditorHidden { CaptureService.grab() }
            if (frame == null) {
                toast("Could not see the screen. Try again.")
                return@launch
            }
            val c = steps[i].conditions[j]
            val x = c.boxX.coerceIn(0, frame.width - 2)
            val y = c.boxY.coerceIn(0, frame.height - 2)
            val w = c.boxW.coerceIn(2, frame.width - x)
            val h = c.boxH.coerceIn(2, frame.height - y)
            val newId = TemplateStore.save(Bitmap.createBitmap(frame, x, y, w, h))
            editCond(i, j) { it.copy(templateId = newId, capW = frame.width, capH = frame.height) }
            condResults.remove(i * 100 + j)
            then()
        }
    }

    private fun testCondition(i: Int, j: Int) {
        scope.launch {
            val c = steps[i].conditions[j]
            val template = TemplateStore.load(c.templateId)
            if (template == null) {
                toast("Capture the picture first.")
                return@launch
            }
            val frame = withEditorHidden { CaptureService.grab() }
            if (frame == null) {
                toast("Could not see the screen. Try again.")
                return@launch
            }
            val region = if (c.wholeScreen) null
            else Matcher.nearRegion(c.boxX, c.boxY, c.boxW, c.boxH, 1.0, frame.width, frame.height)
            val res = withContext(Dispatchers.Default) { Matcher.find(frame, template, region, c.threshold) }
            DebugState.record(frame, res, c.threshold, c.label)
            if (!res.tooPlain) showMatchBoxes(listOf(res))
            val pass = if (c.mustAppear) res.found else !res.found
            condResults[i * 100 + j] = when {
                res.tooPlain -> "This picture is too plain to find. Use a bigger box with more detail."
                res.found && c.mustAppear -> "On screen, ${(res.score * 100).toInt()}%. The condition holds."
                res.found -> "On screen, ${(res.score * 100).toInt()}%. The condition does NOT hold."
                c.mustAppear -> "Not on screen (best ${(res.score * 100).toInt()}%). The condition does NOT hold."
                else -> "Not on screen, as wanted (best ${(res.score * 100).toInt()}%). The condition holds."
            }.let { if (pass || res.tooPlain) it else it }
            if (listMode) {
                applyVisibility(false)
                toast(condResults[i * 100 + j] ?: "")
                delay(2500)
                removeMatchBox()
                applyVisibility(true)
            } else {
                openConditionSheet(i, j)
                delay(2500)
                removeMatchBox()
            }
        }
    }

    private fun buildPictureGeneral(body: LinearLayout, i: Int) {
        val s = steps[i]
        val bmp = TemplateStore.load(s.templateId)
        if (bmp != null) {
            val preview = ImageView(ctx).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = dp(90)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(TokenInts.surfaceHigh)
            }
            body.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        results[i]?.let {
            body.addView(label(it, 16f, if (it.startsWith("Found")) TokenInts.success else TokenInts.warning), lp(8))
        }
        body.addView(label("Move the box over the picture, then capture it again if you moved it.", 14f, TokenInts.textMuted), lp(8))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.button(ctx, "Capture again", TokenInts.surfaceHigh, TokenInts.text) {
                captureTemplate(i) { openSheet(i, 0) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Ui.button(ctx, "Test now", TokenInts.primary, TokenInts.onPrimary) { testPicture(i) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
        )
        body.addView(row, lp(8))

        body.addView(
            stepper("How exact?", { "${steps[i].threshold}%" },
                { edit(i) { it.copy(threshold = (it.threshold - 1).coerceAtLeast(70)) } },
                { edit(i) { it.copy(threshold = (it.threshold + 1).coerceAtMost(99)) } }),
            lp(12),
        )
        body.addView(toggle("Where to look", { if (steps[i].wholeScreen) "Whole screen" else "Near the box" }) {
            edit(i) { it.copy(wholeScreen = !it.wholeScreen) }
        }, lp(8))
        body.addView(toggle("Picture size", { if (steps[i].flexScale) "Allow a little bigger or smaller" else "Same size only" }) {
            edit(i) { it.copy(flexScale = !it.flexScale) }
        }, lp(8))
        body.addView(toggle("If it shows up more than once", { if (steps[i].matchAll) "Tap every one, top to bottom" else "Tap the best match only" }) {
            edit(i) { it.copy(matchAll = !it.matchAll) }
        }, lp(8))
        body.addView(
            stepper("Look for", { "${steps[i].timeoutMs / 1000} s" },
                { edit(i) { it.copy(timeoutMs = (it.timeoutMs - 1000).coerceAtLeast(1000)) } },
                { edit(i) { it.copy(timeoutMs = (it.timeoutMs + 1000).coerceAtMost(120_000)) } }),
            lp(8),
        )
        body.addView(toggle("If it is not found", { if (steps[i].skipIfMissing) "Skip this step" else "Stop the macro" }) {
            edit(i) { it.copy(skipIfMissing = !it.skipIfMissing) }
        }, lp(8))
    }

    private fun buildAction(body: LinearLayout, i: Int) {
        body.addView(label("What happens each time this step runs.", 14f, TokenInts.textMuted), lp(8))
        body.addView(
            stepper("Hold the tap", { if (steps[i].holdMs == 0L) "quick" else "${steps[i].holdMs} ms" },
                { edit(i) { it.copy(holdMs = (it.holdMs - 10).coerceAtLeast(0)) } },
                { edit(i) { it.copy(holdMs = (it.holdMs + 10).coerceAtMost(3000)) } }),
            lp(8),
        )
        body.addView(
            stepper("Repeat", { "${steps[i].repeatCount}x" },
                { edit(i) { it.copy(repeatCount = (it.repeatCount - 1).coerceAtLeast(1)) } },
                { edit(i) { it.copy(repeatCount = (it.repeatCount + 1).coerceAtMost(99)) } }),
            lp(8),
        )
        body.addView(
            stepper("Wait before", { "${steps[i].delayMs} ms" },
                { edit(i) { it.copy(delayMs = (it.delayMs - 100).coerceAtLeast(0)) } },
                { edit(i) { it.copy(delayMs = (it.delayMs + 100).coerceAtMost(30_000)) } }),
            lp(8),
        )
        body.addView(
            stepper("Wait after", { "${steps[i].waitNextMs} ms" },
                { edit(i) { it.copy(waitNextMs = (it.waitNextMs - 100).coerceAtLeast(0)) } },
                { edit(i) { it.copy(waitNextMs = (it.waitNextMs + 100).coerceAtMost(30_000)) } }),
            lp(8),
        )
        body.addView(
            stepper("Random offset", { "${steps[i].randomizePx} px" },
                { edit(i) { it.copy(randomizePx = (it.randomizePx - 1).coerceAtLeast(0)) } },
                { edit(i) { it.copy(randomizePx = (it.randomizePx + 1).coerceAtMost(50)) } }),
            lp(8),
        )
    }

    // ================================================================ steps list

    private fun openStepsList() {
        closeSheet()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(56))
            background = GradientDrawable().apply {
                setColor(0xF51A1D24.toInt())
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("All steps", 20f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(ctx, "Done", TokenInts.primary, TokenInts.onPrimary) { closeSheet() })
        root.addView(header)
        val adders = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        adders.addView(
            Ui.button(ctx, "Add a wait", TokenInts.surfaceHigh, TokenInts.text) {
                steps.add(Step(StepType.WAIT, ms = 1000))
                changed()
                openStepsList()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        adders.addView(
            Ui.button(ctx, "Add a stop", TokenInts.surfaceHigh, TokenInts.text) {
                steps.add(Step(StepType.STOP_MACRO))
                changed()
                openStepsList()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
        )
        root.addView(adders, lp(8))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (steps.isEmpty()) {
            body.addView(label("No steps yet. Use the buttons at the top: Tap, Swipe or Picture.", 16f, TokenInts.textMuted), lp(12))
        }
        steps.forEachIndexed { i, s ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(TokenInts.surfaceHigh, dp(12).toFloat())
                setPadding(dp(10), dp(8), dp(6), dp(8))
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                label("${i + 1}", 16f, TokenInts.onPrimary).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(TokenInts.primary, dp(8).toFloat())
                },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) },
            )
            if (s.type == StepType.CLICK_IMAGE) thumb(s.templateId, 56)?.let { top.addView(it, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(8) }) }
            val text = label(s.describe(), 14f, TokenInts.text).apply {
                isClickable = true
                setOnClickListener { openSheet(i) }
            }
            top.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun small(t: String, color: Int, f: () -> Unit) = Ui.button(ctx, t, Color.TRANSPARENT, color) { f() }.apply { minWidth = dp(40); setPadding(dp(6), dp(8), dp(6), dp(8)) }
            top.addView(small("▲", TokenInts.text) { move(i, -1) })
            top.addView(small("▼", TokenInts.text) { move(i, 1) })
            top.addView(small("✕", TokenInts.danger) { steps.removeAt(i); changed(); openStepsList() })
            card.addView(top)

            if (s.conditions.isNotEmpty()) {
                card.addView(
                    label(if (s.conditions.size == 1) "Only when" else if (s.anyOf) "Only when ANY of these" else "Only when ALL of these", 12f, TokenInts.success)
                        .apply { setPadding(dp(38), dp(6), 0, dp(2)) },
                )
                s.conditions.forEachIndexed { j, c ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(38), dp(2), 0, dp(2))
                        isClickable = true
                        setOnClickListener { openConditionSheet(i, j) }
                    }
                    thumb(c.templateId, 40)?.let { row.addView(it, LinearLayout.LayoutParams(dp(48), dp(30)).apply { marginEnd = dp(8) }) }
                    row.addView(
                        label("${letter(j)}. ${c.label} " + (if (c.mustAppear) "is on screen" else "is NOT on screen") + " (${c.threshold}%)", 13f, TokenInts.textMuted),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    card.addView(row)
                }
            }
            if (s.onSuccess.isNotEmpty() || s.onFail.isNotEmpty()) {
                val br = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(38), dp(4), 0, 0)
                }
                if (s.onSuccess.isNotEmpty()) {
                    br.addView(
                        Ui.button(ctx, "On success: ${s.onSuccess.size}  >", TokenInts.surface, TokenInts.success) { enterBranch(i, true) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
                    )
                }
                if (s.onFail.isNotEmpty()) {
                    br.addView(
                        Ui.button(ctx, "On fail: ${s.onFail.size}  >", TokenInts.surface, TokenInts.warning) { enterBranch(i, false) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                card.addView(br)
            }
            body.addView(card, lp(8))
        }
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addSheetWindow(root, 0.72)
    }

    private fun thumb(templateId: String, @Suppress("UNUSED_PARAMETER") maxDp: Int): ImageView? {
        val bmp = TemplateStore.load(templateId) ?: return null
        return ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(TokenInts.surface)
        }
    }

    private fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in steps.indices) return
        val t = steps[i]; steps[i] = steps[j]; steps[j] = t
        results.clear()
        changed()
        openStepsList()
    }

    // ================================================================ pictures

    private fun captureTemplate(index: Int, then: () -> Unit) {
        scope.launch {
            val frame = withEditorHidden { CaptureService.grab() }
            if (frame == null) {
                toast("Could not see the screen. Try again.")
                return@launch
            }
            val s = steps[index]
            val x = s.boxX.coerceIn(0, frame.width - 2)
            val y = s.boxY.coerceIn(0, frame.height - 2)
            val w = s.boxW.coerceIn(2, frame.width - x)
            val h = s.boxH.coerceIn(2, frame.height - y)
            val crop = Bitmap.createBitmap(frame, x, y, w, h)
            val newId = TemplateStore.save(crop)
            edit(index) { it.copy(templateId = newId, capW = frame.width, capH = frame.height) }
            results.remove(index)
            then()
        }
    }

    private fun testPicture(index: Int) {
        scope.launch {
            val s = steps[index]
            val template = TemplateStore.load(s.templateId)
            if (template == null) {
                toast("Capture the picture first.")
                return@launch
            }
            val frame = withEditorHidden { CaptureService.grab() }
            if (frame == null) {
                toast("Could not see the screen. Try again.")
                return@launch
            }
            val region = if (s.wholeScreen) null
            else Matcher.nearRegion(s.boxX, s.boxY, s.boxW, s.boxH, 1.0, frame.width, frame.height)
            val hits = withContext(Dispatchers.Default) {
                if (s.matchAll) Matcher.findAll(frame, template, region, s.threshold)
                else listOf(Matcher.find(frame, template, region, s.threshold, 1.0, s.flexScale))
            }
            val res = hits.firstOrNull() ?: Matcher.Result(false, 0.0, 0, 0, 0, 0)
            DebugState.record(frame, res, s.threshold, s.label, hits.drop(1))
            if (!res.tooPlain) showMatchBoxes(if (hits.isEmpty()) listOf(res) else hits)
            results[index] = when {
                res.tooPlain -> "This picture is too plain to find. Use a bigger box with more detail."
                s.matchAll && hits.isNotEmpty() -> "Found ${hits.size} (best ${(res.score * 100).toInt()}%) in ${res.ms} ms"
                res.found -> "Found, ${(res.score * 100).toInt()}% in ${res.ms} ms"
                else -> "Not found. Best match was ${(res.score * 100).toInt()}%, needs ${s.threshold}% (${res.ms} ms)."
            }
            if (listMode) {
                applyVisibility(false)
                toast(results[index] ?: "")
                delay(2500)
                removeMatchBox()
                applyVisibility(true)
            } else {
                openSheet(index, 0)
                delay(2500)
                removeMatchBox()
            }
        }
    }

    private val matchBoxes = mutableListOf<View>()

    private fun showMatchBoxes(list: List<Matcher.Result>) {
        removeMatchBox()
        for (res in list) {
            val color = if (res.found) TokenInts.success else TokenInts.warning
            val v = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(4), color)
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
            matchBoxes.add(v)
        }
    }

    private fun removeMatchBox() {
        matchBoxes.forEach { runCatching { wm.removeView(it) } }
        matchBoxes.clear()
    }

    // ================================================================ test run, save, close

    private fun testRun() {
        if (snapshotRoot().isEmpty()) {
            toast("Add a step first.")
            return
        }
        if (flat(snapshotRoot()).any { st -> (st.type == StepType.CLICK_IMAGE && st.templateId.isEmpty()) || st.conditions.any { it.templateId.isEmpty() } }) {
            toast("A picture or condition has no picture yet. Tap its box to capture it.")
            return
        }
        if (!CaptureService.running && flat(snapshotRoot()).any { it.type == StepType.CLICK_IMAGE || it.conditions.isNotEmpty() }) {
            showCaptureNeeded()
            return
        }
        val svc = MacroService.instance ?: return
        closeSheet()
        applyVisibility(false)
        svc.runMacro(Macro(macroId, macroName, snapshotRoot(), repeat = 1), 2) {
            applyVisibility(true)
        }
    }

    private fun save() {
        MacroRepository.save(Macro(id = macroId, name = macroName, steps = snapshotRoot(), repeat = repeat))
        dirty = false
        updateInfo()
    }

    private fun requestClose() {
        if (!dirty) {
            onClosed()
            return
        }
        showDialog { box ->
            box.addView(label("Save your changes?", 20f, TokenInts.text))
            box.addView(Ui.button(ctx, "Keep editing", TokenInts.primary, TokenInts.onPrimary) { closeDialog() }, lp(12))
            box.addView(Ui.button(ctx, "Save and close", TokenInts.surfaceHigh, TokenInts.text) { save(); onClosed() }, lp(8))
            box.addView(Ui.button(ctx, "Close without saving", TokenInts.surfaceHigh, TokenInts.danger) { onClosed() }, lp(8))
        }
    }

    private fun showCaptureNeeded() {
        showDialog { box ->
            box.addView(label("Allow screen capture", 20f, TokenInts.text))
            box.addView(label("MacroLoop needs to see the screen to find pictures. Android will ask you once.", 14f, TokenInts.textMuted), lp(4))
            box.addView(Ui.button(ctx, "Allow screen capture", TokenInts.primary, TokenInts.onPrimary) {
                closeDialog()
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_ASK_CAPTURE, true),
                )
            }, lp(12))
            box.addView(Ui.button(ctx, "Back", TokenInts.surfaceHigh, TokenInts.text) { closeDialog() }, lp(8))
        }
    }


    // ================================================================ The Abstract list (main editor view)

    private var listMode = true
    private var panel: View? = null
    private var panelScroll: ScrollView? = null
    private var panelScrollY = 0

    private class Row(val text: String, val color: Int = TokenInts.text, val action: (() -> Unit)? = null)

    private fun enterListMode() {
        closeSheet()
        closeDialog()
        removeMatchBox()
        listMode = true
        refreshPanel()
    }

    private fun enterMarkerMode(msg: String?) {
        closeSheet()
        closeDialog()
        listMode = false
        rebuildMarkers()
        applyVisibility(true)
        updateInfo()
        if (msg != null) toast(msg)
    }

    private fun currentPath(): List<Pair<Int, Boolean>> = stack.map { it.index to it.onSuccess }

    /** Makes the list at [path] the one being edited. */
    private fun goTo(path: List<Pair<Int, Boolean>>) {
        while (stack.isNotEmpty()) exitBranch(silent = true)
        for ((i, ok) in path) enterBranch(i, ok, silent = true)
    }

    private fun listChanged() {
        dirty = true
        rebuildMarkers()
        updateInfo()
        refreshPanel()
    }

    private fun countAll(list: List<Step>): Int = list.size + list.sumOf { countAll(it.onSuccess) + countAll(it.onFail) }

    private fun weightLp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun refreshPanel() {
        panelScroll?.let { panelScrollY = it.scrollY }
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelScroll = null
        val whole = snapshotRoot()

        val rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = Ui.rounded(0xFF0F1115.toInt(), dp(20).toFloat())
        }
        rootView.addView(label(macroName, 22f, TokenInts.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val n = countAll(whole)
        rootView.addView(
            label((if (n == 1) "1 step" else "$n steps") + (if (dirty) "  (not saved)" else ""), 13f, TokenInts.textMuted),
            lp(0),
        )
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ToolButton(ctx, Icon.PLAY, "Test run", TokenInts.success) { testRun() }, weightLp())
        buttons.addView(ToolButton(ctx, Icon.PLUS, "Add") { openAddPicker(emptyList(), null) }, weightLp())
        buttons.addView(ToolButton(ctx, Icon.EYE, "On screen") { enterMarkerMode("Drag the markers. Tap List to come back.") }, weightLp())
        buttons.addView(ToolButton(ctx, Icon.SAVE, "Save") { save(); refreshPanel(); toast("Saved") }, weightLp())
        buttons.addView(ToolButton(ctx, Icon.CLOSE, "Close", TokenInts.danger) { requestClose() }, weightLp())
        rootView.addView(buttons, lp(8))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (whole.isEmpty()) {
            body.addView(label("No steps yet. Press Add to make the first one.", 17f, TokenInts.textMuted), lp(16))
        } else {
            renderSteps(body, whole, emptyList(), 0, "")
        }
        val scroll = ScrollView(ctx).apply {
            addView(body)
            isVerticalScrollBarEnabled = true
        }
        panelScroll = scroll
        rootView.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenH * 0.93).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(28)
        }
        wm.addView(rootView, params)
        panel = rootView
        scroll.post { scroll.scrollTo(0, panelScrollY) }
        applyVisibility(true)
    }

    private fun renderSteps(box: LinearLayout, list: List<Step>, path: List<Pair<Int, Boolean>>, depth: Int, prefix: String) {
        list.forEachIndexed { i, s ->
            val num = if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}"
            box.addView(buildCard(s, path, i, num), lp(8))
            if (s.onSuccess.isNotEmpty()) addBranch(box, s.onSuccess, path + (i to true), depth, num, true)
            if (s.onFail.isNotEmpty()) addBranch(box, s.onFail, path + (i to false), depth, num, false)
        }
    }

    private fun addBranch(box: LinearLayout, list: List<Step>, path: List<Pair<Int, Boolean>>, depth: Int, num: String, ifTrue: Boolean) {
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(6))
            background = Ui.rounded(if (ifTrue) 0x264ADE80 else 0x26FBBF24, dp(16).toFloat())
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openAddPicker(path, null) }
        }
        head.addView(
            label(if (ifTrue) "If true" else "If false", 18f, if (ifTrue) TokenInts.success else TokenInts.warning)
                .apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
        )
        head.addView(
            label(if (ifTrue) "   step $num worked, then:" else "   step $num never found what it needs, then:", 13f, TokenInts.textMuted),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(label("+", 22f, TokenInts.primary).apply { setPadding(dp(12), 0, dp(8), 0) })
        group.addView(head)
        renderSteps(group, list, path, depth + 1, num)
        val lpGroup = lp(6).apply { marginStart = dp(16) }
        box.addView(group, lpGroup)
    }

    private fun headline(s: Step): String = when (s.type) {
        StepType.CLICK -> "Tap  (${s.x}, ${s.y})"
        StepType.SWIPE -> "Swipe  (${s.x}, ${s.y}) to (${s.x2}, ${s.y2})"
        StepType.WAIT -> "Wait  ${s.ms / 1000.0} s"
        StepType.CLICK_IMAGE -> "Tap the picture  \"${s.label}\""
        StepType.STOP_MACRO -> "Stop the macro"
    }

    private fun subline(s: Step): String {
        val parts = ArrayList<String>()
        if (s.type == StepType.CLICK || s.type == StepType.SWIPE || s.type == StepType.CLICK_IMAGE) {
            parts.add("x${s.repeatCount}")
            parts.add("delay ${s.delayMs} / ${s.waitNextMs} ms")
            if (s.holdMs > 0 && s.type != StepType.SWIPE) parts.add("hold ${s.holdMs} ms")
        }
        if (!s.enabled) parts.add("OFF")
        return parts.joinToString("   ·   ")
    }

    private fun circle(text: String, fill: Int, textColor: Int, sizeDp: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sizeDp * 0.42f
        setTextColor(textColor)
        gravity = Gravity.CENTER
        background = Ui.rounded(fill, dp(sizeDp) / 2f)
    }

    private fun chip(text: String, color: Int, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(TokenInts.onPrimary)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = Ui.rounded(color, dp(20).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** One line of the logic tree: a round mark, the picture, and what has to be true. */
    private fun logicRow(templateId: String, text: String, ok: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(circle(if (ok) "✓" else "✕", if (ok) TokenInts.success else TokenInts.danger, TokenInts.onPrimary, 34), LinearLayout.LayoutParams(dp(34), dp(34)))
        thumb(templateId, 64)?.let { row.addView(it, LinearLayout.LayoutParams(dp(76), dp(44)).apply { marginStart = dp(10) }) }
        row.addView(label(text, 16f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        return row
    }

    private fun buildCard(s: Step, path: List<Pair<Int, Boolean>>, i: Int, num: String): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = Ui.rounded(if (s.enabled) 0xFF242833.toInt() else 0xFF1A1D24.toInt(), dp(16).toFloat())
            alpha = if (s.enabled) 1f else 0.55f
            isClickable = true
            setOnClickListener { openCardMenu(path, i) }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = when (s.type) {
            StepType.CLICK -> "●"
            StepType.SWIPE -> "➜"
            StepType.WAIT -> "⏱"
            StepType.CLICK_IMAGE -> "▣"
            StepType.STOP_MACRO -> "■"
        }
        top.addView(circle(glyph, TokenInts.primary, TokenInts.onPrimary, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(headline(s), 18f, TokenInts.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val sub = subline(s)
        if (sub.isNotEmpty()) col.addView(label(sub, 12f, TokenInts.textMuted))
        top.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        top.addView(
            label(num, 14f, TokenInts.onPrimary).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = Ui.rounded(TokenInts.primary, dp(8).toFloat())
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) },
        )
        card.addView(top)

        // the logic tree: this step's own picture, then the extra conditions
        val tree = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), 0, 0)
        }
        var hasTree = false
        if (s.type == StepType.CLICK_IMAGE) {
            tree.addView(logicRow(s.templateId, "Picture appears  ${s.threshold}%" + if (s.wholeScreen) "  (whole screen)" else "", true) { openCardMenu(path, i) })
            hasTree = true
        }
        s.conditions.forEachIndexed { j, c ->
            if (hasTree || j > 0) {
                val allTrue = j == 0 || !s.anyOf
                val chipRow = LinearLayout(ctx).apply { setPadding(dp(46), dp(2), 0, dp(2)) }
                chipRow.addView(
                    chip(if (allTrue) "All true" else "Any true", TokenInts.success) {
                        goTo(path)
                        edit(i) { it.copy(anyOf = !it.anyOf) }
                        listChanged()
                    },
                )
                tree.addView(chipRow)
            }
            tree.addView(
                logicRow(
                    c.templateId,
                    (if (c.mustAppear) "Picture appears  " else "Picture is NOT on screen  ") + "${c.threshold}%",
                    c.mustAppear,
                ) { openConditionMenu(path, i, j) },
            )
            hasTree = true
        }
        if (hasTree) card.addView(tree)
        return card
    }

    // ---------------------------------------------------------------- big menus

    private fun showBigMenu(title: String, rows: List<Row>) {
        closeDialog()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
            background = Ui.rounded(0xFF1A1D24.toInt(), dp(20).toFloat())
        }
        box.addView(label(title, 18f, TokenInts.text).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(2), dp(14), dp(10))
        })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows) {
            val act = r.action
            list.addView(
                TextView(ctx).apply {
                    text = r.text
                    textSize = if (act == null) 14f else 20f
                    setTextColor(if (act == null) TokenInts.textMuted else r.color)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(if (act == null) 34 else 60)
                    setPadding(dp(14), dp(if (act == null) 10 else 6), dp(14), dp(6))
                    if (act != null) {
                        isClickable = true
                        setOnClickListener { act() }
                    }
                },
            )
        }
        list.addView(
            TextView(ctx).apply {
                text = "Cancel"
                textSize = 20f
                setTextColor(TokenInts.textMuted)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(60)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { closeDialog() }
            },
        )
        box.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val h = minOf((screenH * 0.8).toInt(), dp(60 * rows.size + 150))
        val params = WindowManager.LayoutParams(
            (screenW * 0.92).toInt(), h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(box, params)
        dialog = box
    }

    private fun showEditDialog(title: String, build: (LinearLayout) -> Unit) {
        showDialog { box ->
            box.addView(label(title, 20f, TokenInts.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            build(box)
            box.addView(Ui.button(ctx, "Done", TokenInts.primary, TokenInts.onPrimary) {
                closeDialog()
                listChanged()
            }, lp(16))
        }
    }

    private fun openAddPicker(target: List<Pair<Int, Boolean>>, insertIndex: Int?) {
        fun go(after: () -> Unit) {
            closeDialog()
            goTo(target)
            insertAt = insertIndex
            after()
        }
        showBigMenu(
            "Add a step",
            listOf(
                Row("Gestures"),
                Row("Tap a spot") { go { addTap(); enterMarkerMode("Drag the dot to the spot. Tap List to come back.") } },
                Row("Swipe") { go { addSwipe(); enterMarkerMode("Drag both dots. Tap List to come back.") } },
                Row("Picture"),
                Row("Find and tap a picture") {
                    go {
                        if (!CaptureService.running) {
                            insertAt = null
                            showCaptureNeeded()
                        } else {
                            addPicture()
                            enterMarkerMode("Move the box over the picture, tap it to capture. Tap List to come back.")
                        }
                    }
                },
                Row("Macro"),
                Row("Wait 1 second") { go { addStep(Step(StepType.WAIT, ms = 1000)); listChanged() } },
                Row("Stop the macro", TokenInts.danger) { go { addStep(Step(StepType.STOP_MACRO)); listChanged() } },
            ),
        )
    }

    private fun openCardMenu(path: List<Pair<Int, Boolean>>, i: Int) {
        goTo(path)
        if (i !in steps.indices) return
        val s = steps[i]
        val rows = ArrayList<Row>()
        val movable = s.type == StepType.CLICK || s.type == StepType.SWIPE || s.type == StepType.CLICK_IMAGE
        val logical = s.type != StepType.WAIT && s.type != StepType.STOP_MACRO

        if (movable) rows.add(Row("Move on screen") { enterMarkerMode("Drag it. Tap List to come back.") })
        if (s.type == StepType.CLICK_IMAGE) {
            rows.add(Row("Change picture") { enterMarkerMode("Move the box over the new picture, tap it, then Capture again.") })
            rows.add(Row("Edit picture settings") { editPictureDialog(i) })
            rows.add(Row("Test now") { closeDialog(); testPicture(i) })
        }
        if (s.type == StepType.WAIT) rows.add(Row("Edit wait time") { editWaitDialog(i) })
        if (s.type == StepType.SWIPE) rows.add(Row("Edit swipe speed") { editSwipeDialog(i) })
        if (movable) {
            rows.add(Row("Edit delay") { editDelayDialog(i) })
            if (s.type != StepType.SWIPE) rows.add(Row("Edit tap style") { editTapDialog(i) })
        }
        if (logical) {
            rows.add(Row("Add condition: picture is there") { addConditionFromList(path, i, true) })
            rows.add(Row("Add condition: picture is gone") { addConditionFromList(path, i, false) })
            rows.add(Row("Add step if true", TokenInts.success) { openAddPicker(path + (i to true), null) })
            rows.add(Row("Add step if false", TokenInts.warning) { openAddPicker(path + (i to false), null) })
        }
        rows.add(Row("Add step above") { openAddPicker(path, i) })
        rows.add(Row("Move up") { closeDialog(); if (i > 0) { val t = steps[i]; steps[i] = steps[i - 1]; steps[i - 1] = t; listChanged() } })
        rows.add(Row("Move down") { closeDialog(); if (i < steps.size - 1) { val t = steps[i]; steps[i] = steps[i + 1]; steps[i + 1] = t; listChanged() } })
        rows.add(Row("Copy") { closeDialog(); steps.add(i + 1, steps[i]); listChanged() })
        rows.add(Row(if (s.enabled) "Disable" else "Enable") { closeDialog(); edit(i) { it.copy(enabled = !it.enabled) }; listChanged() })
        rows.add(Row("Delete", TokenInts.danger) { closeDialog(); steps.removeAt(i); listChanged() })
        showBigMenu(headline(s), rows)
    }

    private fun openConditionMenu(path: List<Pair<Int, Boolean>>, i: Int, j: Int) {
        goTo(path)
        if (i !in steps.indices || j !in steps[i].conditions.indices) return
        val c = steps[i].conditions[j]
        showBigMenu(
            "Condition ${i + 1}${letter(j)}",
            listOf(
                Row("Move on screen / change picture") { enterMarkerMode("Move the box, tap it, then Capture again.") },
                Row("Test now") { closeDialog(); testCondition(i, j) },
                Row(if (c.mustAppear) "Change to: must NOT be on screen" else "Change to: must be on screen") {
                    closeDialog()
                    editCond(i, j) { it.copy(mustAppear = !it.mustAppear) }
                    listChanged()
                },
                Row("Edit how exact") {
                    showEditDialog("How exact?") { box ->
                        box.addView(
                            stepper("Match", { "${steps[i].conditions[j].threshold}%" },
                                { editCond(i, j) { it.copy(threshold = (it.threshold - 1).coerceAtLeast(70)) } },
                                { editCond(i, j) { it.copy(threshold = (it.threshold + 1).coerceAtMost(99)) } }),
                            lp(12),
                        )
                    }
                },
                Row("Delete condition", TokenInts.danger) {
                    closeDialog()
                    edit(i) { st -> st.copy(conditions = st.conditions.filterIndexed { k, _ -> k != j }) }
                    listChanged()
                },
            ),
        )
    }

    private fun addConditionFromList(path: List<Pair<Int, Boolean>>, i: Int, mustAppear: Boolean) {
        closeDialog()
        goTo(path)
        if (!CaptureService.running) {
            showCaptureNeeded()
            return
        }
        if (!Matcher.ensureLoaded()) {
            toast("The picture finder could not start.")
            return
        }
        val n = steps[i].conditions.size
        val w = (screenW * 0.30).toInt()
        val h = (w * 0.5).toInt()
        val c = Condition(
            label = "Condition ${i + 1}${letter(n)}",
            mustAppear = mustAppear,
            boxX = ((screenW - w) / 2 + n * dp(24)).coerceAtLeast(0),
            boxY = ((screenH - h) / 3 + n * dp(24)).coerceAtLeast(0),
            boxW = w, boxH = h, capW = screenW, capH = screenH,
        )
        edit(i) { it.copy(conditions = it.conditions + c) }
        enterMarkerMode("Move the box over it, then tap the box to capture it. Tap List to come back.")
    }

    // ---------------------------------------------------------------- edit dialogs (big steppers)

    private fun editDelayDialog(i: Int) = showEditDialog("Edit delay") { box ->
        box.addView(
            stepper("Wait before", { "${steps[i].delayMs} ms" },
                { edit(i) { it.copy(delayMs = (it.delayMs - 100).coerceAtLeast(0)) } },
                { edit(i) { it.copy(delayMs = (it.delayMs + 100).coerceAtMost(30_000)) } }),
            lp(12),
        )
        box.addView(
            stepper("Wait after", { "${steps[i].waitNextMs} ms" },
                { edit(i) { it.copy(waitNextMs = (it.waitNextMs - 100).coerceAtLeast(0)) } },
                { edit(i) { it.copy(waitNextMs = (it.waitNextMs + 100).coerceAtMost(30_000)) } }),
            lp(10),
        )
    }

    private fun editTapDialog(i: Int) = showEditDialog("Edit tap style") { box ->
        box.addView(
            stepper("Hold", { if (steps[i].holdMs == 0L) "quick" else "${steps[i].holdMs} ms" },
                { edit(i) { it.copy(holdMs = (it.holdMs - 10).coerceAtLeast(0)) } },
                { edit(i) { it.copy(holdMs = (it.holdMs + 10).coerceAtMost(3000)) } }),
            lp(12),
        )
        box.addView(
            stepper("Repeat", { "${steps[i].repeatCount}x" },
                { edit(i) { it.copy(repeatCount = (it.repeatCount - 1).coerceAtLeast(1)) } },
                { edit(i) { it.copy(repeatCount = (it.repeatCount + 1).coerceAtMost(99)) } }),
            lp(10),
        )
        box.addView(
            stepper("Random offset", { "${steps[i].randomizePx} px" },
                { edit(i) { it.copy(randomizePx = (it.randomizePx - 1).coerceAtLeast(0)) } },
                { edit(i) { it.copy(randomizePx = (it.randomizePx + 1).coerceAtMost(50)) } }),
            lp(10),
        )
    }

    private fun editWaitDialog(i: Int) = showEditDialog("Edit wait time") { box ->
        box.addView(
            stepper("Wait", { "${steps[i].ms / 1000.0} s" },
                { edit(i) { it.copy(ms = (it.ms - 500).coerceAtLeast(500)) } },
                { edit(i) { it.copy(ms = (it.ms + 500).coerceAtMost(60_000)) } }),
            lp(12),
        )
    }

    private fun editSwipeDialog(i: Int) = showEditDialog("Edit swipe speed") { box ->
        box.addView(
            stepper("How long", { "${steps[i].swipeMs} ms" },
                { edit(i) { it.copy(swipeMs = (it.swipeMs - 50).coerceAtLeast(100)) } },
                { edit(i) { it.copy(swipeMs = (it.swipeMs + 50).coerceAtMost(3000)) } }),
            lp(12),
        )
        box.addView(
            stepper("Repeat", { "${steps[i].repeatCount}x" },
                { edit(i) { it.copy(repeatCount = (it.repeatCount - 1).coerceAtLeast(1)) } },
                { edit(i) { it.copy(repeatCount = (it.repeatCount + 1).coerceAtMost(99)) } }),
            lp(10),
        )
    }

    private fun editPictureDialog(i: Int) = showEditDialog("Edit picture settings") { box ->
        box.addView(
            stepper("How exact?", { "${steps[i].threshold}%" },
                { edit(i) { it.copy(threshold = (it.threshold - 1).coerceAtLeast(70)) } },
                { edit(i) { it.copy(threshold = (it.threshold + 1).coerceAtMost(99)) } }),
            lp(12),
        )
        box.addView(
            stepper("Look for", { "${steps[i].timeoutMs / 1000} s" },
                { edit(i) { it.copy(timeoutMs = (it.timeoutMs - 1000).coerceAtLeast(1000)) } },
                { edit(i) { it.copy(timeoutMs = (it.timeoutMs + 1000).coerceAtMost(120_000)) } }),
            lp(10),
        )
        box.addView(toggle("Where to look", { if (steps[i].wholeScreen) "Whole screen" else "Near the box" }) {
            edit(i) { it.copy(wholeScreen = !it.wholeScreen) }
        }, lp(10))
        box.addView(toggle("Picture size", { if (steps[i].flexScale) "Allow a little bigger or smaller" else "Same size only" }) {
            edit(i) { it.copy(flexScale = !it.flexScale) }
        }, lp(10))
        box.addView(toggle("If it shows up more than once", { if (steps[i].matchAll) "Tap every one" else "Tap the best match only" }) {
            edit(i) { it.copy(matchAll = !it.matchAll) }
        }, lp(10))
        box.addView(toggle("If it is not found", { if (steps[i].skipIfMissing) "Skip this step" else "Stop the macro" }) {
            edit(i) { it.copy(skipIfMissing = !it.skipIfMissing) }
        }, lp(10))
    }

    // ================================================================ small ui helpers

    private fun showDialog(build: (LinearLayout) -> Unit) {
        closeDialog()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = Ui.rounded(TokenInts.surface, dp(20).toFloat())
        }
        build(box)
        val params = WindowManager.LayoutParams(
            (screenW * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(box, params)
        dialog = box
    }

    private fun closeDialog() {
        dialog?.let { runCatching { wm.removeView(it) } }
        dialog = null
    }

    private fun stepper(title: String, value: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title, 16f, TokenInts.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = label(value(), 16f, TokenInts.primary).apply { gravity = Gravity.CENTER }
        val small = { t: String, f: () -> Unit ->
            Ui.button(ctx, t, TokenInts.surfaceHigh, TokenInts.text) { f(); valueView.text = value() }.apply { minWidth = dp(48) }
        }
        row.addView(small("-", onMinus), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        row.addView(valueView, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(small("+", onPlus), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        return row
    }

    private fun toggle(title: String, value: () -> String, onToggle: () -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 14f, TokenInts.textMuted))
        val b = Ui.button(ctx, value(), TokenInts.surfaceHigh, TokenInts.text) {}
        b.setOnClickListener { onToggle(); b.text = value() }
        box.addView(b, lp(2))
        return box
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun label(text: String, size: Float, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = size * 1.2f
        setTextColor(color)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
}
