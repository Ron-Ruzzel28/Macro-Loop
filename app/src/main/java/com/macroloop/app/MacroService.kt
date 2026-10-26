package com.macroloop.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Accessibility service. It does three jobs:
 *  1. performs taps and swipes for the macro runner,
 *  2. hosts the floating editor,
 *  3. shows the small run bar with the Stop button.
 * Overlays use TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps" permission is needed.
 */
class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null
            private set

        const val PREFS = "macroloop"
        const val PREF_HUMAN = "human_timing"
        private const val DEFAULT_HOLD_MS = 60L
        private const val MAX_JUMPS = 5000
        private const val START_SETTLE_MS = 350L
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var runBar: View? = null
    private var runStatus: TextView? = null
    private var editor: EditorOverlay? = null
    private var playPanel: View? = null
    private var playMacroId: String? = null
    private var playX = 24
    private var playY = 220

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        MacroRepository.init(applicationContext)
        TemplateStore.init(applicationContext)
        Matcher.ensureLoaded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopRun()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        stopRun()
        closeEditor()
        closePlayPanel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- editor

    fun startEditor(macroId: String) {
        stopRun()
        closePlayPanel()
        closeEditor()
        val macro = MacroRepository.get(macroId) ?: return
        editor = EditorOverlay(this, wm, macro, scope) { closeEditor() }
        editor?.show()
    }

    private fun closeEditor() {
        editor?.dismiss()
        editor = null
    }

    private fun toastText(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- running

    fun isRunning() = runJob?.isActive == true

    /**
     * Opens the floating Play button for a saved macro. The macro does NOT start until the
     * user presses Play, so there is time to open the game or app first.
     */
    fun showPlayPanel(macroId: String) {
        val macro = MacroRepository.get(macroId) ?: return
        closeEditor()
        stopRun()
        val firstTime = playPanel == null
        removePlayPanel()
        playMacroId = macroId

        // A small round Play button with a tiny X. Drag it anywhere; tap it to start.
        val size = dp(76)
        val box = android.widget.FrameLayout(this)
        val circle = android.widget.FrameLayout(this).apply {
            background = Ui.rounded(TokenInts.primary, dp(30).toFloat())
            contentDescription = "Play ${macro.name}"
        }
        circle.addView(IconView(this, Icon.PLAY, TokenInts.onPrimary), android.widget.FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        box.addView(circle, android.widget.FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.START))
        val x = TextView(this).apply {
            text = "x"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(TokenInts.text)
            background = Ui.rounded(TokenInts.surfaceHigh, dp(13).toFloat())
            isClickable = true
            setOnClickListener { closePlayPanel() }
        }
        box.addView(x, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = playX
            this.y = playY
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        circle.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        playX = lp.x; playY = lp.y
                        wm.updateViewLayout(box, lp)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> if (!moved) startFromPlayPanel()
            }
            true
        }
        wm.addView(box, lp)
        playPanel = box
        if (firstTime) Toast.makeText(this, "${macro.name} is ready. Open your app, then tap the Play button.", Toast.LENGTH_LONG).show()
    }

    private fun startFromPlayPanel() {
        val id = playMacroId ?: return
        val macro = MacroRepository.get(id) ?: return
        if (macro.steps.isEmpty()) {
            Toast.makeText(this, "This macro has no steps yet. Open the editor and add some.", Toast.LENGTH_LONG).show()
            return
        }
        if (needsCapture(macro.steps) && !CaptureService.running) {
            Toast.makeText(this, "Screen capture is off. Open MacroLoop and press Run again.", Toast.LENGTH_LONG).show()
            return
        }
        removePlayPanel()
        runMacro(macro, onFinished = {
            // Back to the Play button when the run ends or is stopped, unless a new run started.
            // The job is still "active" while this runs, so look again a moment later.
            scope.launch {
                delay(150)
                if (playMacroId == id && !isRunning()) showPlayPanel(id)
            }
        })
    }

    private fun closePlayPanel() {
        playMacroId = null
        removePlayPanel()
    }

    private fun removePlayPanel() {
        playPanel?.let { runCatching { wm.removeView(it) } }
        playPanel = null
    }

    private fun needsCapture(list: List<Step>): Boolean = list.any {
        it.type == StepType.CLICK_IMAGE || it.conditions.isNotEmpty() || needsCapture(it.onSuccess) || needsCapture(it.onFail)
    }

    /**
     * Runs a macro right away (no countdown).
     * @param startList the list to run first. Null means the whole macro. A Go to step can jump
     *        to any step of the main list, and the run then carries on from there.
     * @param rounds how many times to run it (the macro's repeat count for a full run).
     * [onFinished] is called when it ends, is stopped, or fails.
     */
    fun runMacro(
        macro: Macro,
        onFinished: (() -> Unit)? = null,
        startList: List<Step>? = null,
        rounds: Int = macro.repeat,
    ) {
        stopRun()
        showRunBar()
        runJob = scope.launch {
            val human = humanTiming()
            try {
                // No countdown. A short pause lets Android finish hiding the editor or Play button,
                // so the first tap does not land on a window that is still there.
                setStatus("${macro.name}: starting")
                delay(START_SETTLE_MS)
                val root = macro.steps
                var jumps = 0
                for (round in 1..rounds) {
                    var cur: List<Step> = startList ?: root
                    var i = 0
                    while (i < cur.size) {
                        setStatus("${macro.name}\nRound $round of $rounds, step ${i + 1} of ${cur.size}")
                        when (val flow = runStep(macro.name, (i + 1).toString(), cur[i], human)) {
                            Flow.Next -> i++
                            is Flow.Stop -> {
                                finish(flow.message)
                                return@launch
                            }
                            is Flow.Jump -> {
                                val at = root.indexOfFirst { it.id == flow.targetId }
                                if (at < 0) {
                                    finish("Stopped: a Go to link points to a step that no longer exists.")
                                    return@launch
                                }
                                if (++jumps > MAX_JUMPS) {
                                    finish("Stopped: too many jumps. A Go to link may be looping with nothing to stop it.")
                                    return@launch
                                }
                                cur = root
                                i = at
                            }
                        }
                    }
                }
                finish("Finished: ${macro.name}")
            } catch (e: CancellationException) {
                throw e
            } finally {
                onFinished?.invoke()
            }
        }
    }

    /** Test one step on its own (no branches, no jumps). */
    fun testStep(macro: Macro, step: Step, onFinished: (() -> Unit)?) {
        val alone = step.copy(onSuccess = emptyList(), onFail = emptyList(), enabled = true)
        runMacro(Macro(macro.id, macro.name, macro.steps, 1), onFinished, startList = listOf(alone), rounds = 1)
    }

    /** Test from one step to the end of its list, then on through any Go to link. */
    fun testFrom(macro: Macro, list: List<Step>, index: Int, onFinished: (() -> Unit)?) {
        runMacro(Macro(macro.id, macro.name, macro.steps, 1), onFinished, startList = list.drop(index), rounds = 1)
    }

    fun stopRun() {
        runJob?.cancel()
        runJob = null
        removeRunBar()
    }

    private suspend fun finish(message: String) {
        setStatus(message)
        delay(2500)
        removeRunBar()
    }

    /** What happened when a step tried to do its job. */
    private sealed class Outcome {
        data object Ok : Outcome()

        /** The picture or condition did not show up in time. A branch or "skip" can handle this. */
        data class Missing(val message: String) : Outcome()

        /** A problem no branch should hide: setup trouble, a cancelled tap, a too-plain picture. */
        data class Fatal(val message: String) : Outcome()
    }

    /** What to do after a step: go on, stop the whole macro, or jump to another step. */
    private sealed class Flow {
        data object Next : Flow()
        data class Stop(val message: String) : Flow()
        data class Jump(val targetId: String) : Flow()
    }

    /** Runs a list of steps (a branch). A Stop or a Jump ends the branch and is passed up. */
    private suspend fun runList(macroName: String, list: List<Step>, human: Boolean, prefix: String): Flow {
        for ((i, step) in list.withIndex()) {
            val where = "$prefix #${i + 1}"
            setStatus("$macroName\nStep $where of ${list.size}")
            val flow = runStep(macroName, where, step, human)
            if (flow !is Flow.Next) return flow
        }
        return Flow.Next
    }

    /**
     * Runs one step with its delay, repeat and wait-after settings, then its branch.
     * The rules:
     *  - a disabled step is skipped, with its branches;
     *  - a picture or condition that never shows up is "false": On fail runs if it has steps,
     *    otherwise the step is skipped if "skip" is set, otherwise the macro stops;
     *  - a step that works is "true": On success runs after it;
     *  - setup trouble (capture off, a missing picture file, a cancelled tap) always stops the macro.
     */
    private suspend fun runStep(macroName: String, where: String, step: Step, human: Boolean): Flow {
        if (!step.enabled) return Flow.Next
        when (step.type) {
            StepType.WAIT -> {
                delay(waitTime(step.ms, human))
                return Flow.Next
            }
            StepType.STOP_MACRO -> return Flow.Stop("Stopped by a Stop step ($where).")
            StepType.GOTO -> return if (step.targetId.isEmpty()) Flow.Stop("Stopped: the Go to step $where has no target. Edit it and choose one.")
            else Flow.Jump(step.targetId)
            else -> Unit
        }
        if (step.delayMs > 0) delay(step.delayMs)

        var outcome: Outcome = Outcome.Ok
        if (step.type != StepType.CLICK_IMAGE && step.conditions.isNotEmpty()) {
            outcome = waitForConditions(macroName, where, step)
        }
        if (outcome is Outcome.Ok) {
            for (r in 1..step.repeatCount) {
                outcome = when (step.type) {
                    StepType.CLICK ->
                        if (tap(step.x, step.y, human, step.holdMs, step.randomizePx)) Outcome.Ok
                        else Outcome.Fatal("Stopped: the tap in step $where was cancelled by Android.")
                    StepType.SWIPE ->
                        if (swipe(step, human)) Outcome.Ok
                        else Outcome.Fatal("Stopped: the swipe in step $where was cancelled by Android.")
                    StepType.CLICK_IMAGE -> runPictureStep(macroName, where, step, human)
                    else -> Outcome.Ok
                }
                if (outcome !is Outcome.Ok) break
            }
        }

        return when (val o = outcome) {
            Outcome.Ok -> {
                val after = step.waitNextMs + if (human) Random.nextLong(50, 250) else 0L
                if (after > 0) delay(after)
                if (step.onSuccess.isNotEmpty()) runList(macroName, step.onSuccess, human, "$where on success") else Flow.Next
            }
            is Outcome.Fatal -> Flow.Stop(o.message)
            is Outcome.Missing -> when {
                step.onFail.isNotEmpty() -> runList(macroName, step.onFail, human, "$where on fail")
                step.skipIfMissing -> Flow.Next
                else -> Flow.Stop(o.message)
            }
        }
    }


    // ---------------------------------------------------------------- conditions

    private class CondCheck(val ok: Boolean, val why: String)

    private fun loadConditionTemplates(step: Step): Map<String, Bitmap>? {
        val out = HashMap<String, Bitmap>()
        for (c in step.conditions) {
            out[c.templateId] = TemplateStore.load(c.templateId) ?: return null
        }
        return out
    }

    /** Looks at one frame and says whether the step's conditions hold. */
    private suspend fun checkConditions(frame: Bitmap, step: Step, templates: Map<String, Bitmap>): CondCheck =
        withContext(Dispatchers.Default) {
            val notes = ArrayList<String>()
            var okCount = 0
            for (c in step.conditions) {
                val t = templates[c.templateId] ?: continue
                val scale = if (c.capW > 0) frame.width.toDouble() / c.capW else 1.0
                val region = if (c.wholeScreen) null
                else Matcher.nearRegion(c.boxX, c.boxY, c.boxW, c.boxH, scale, frame.width, frame.height)
                val r = Matcher.find(frame, t, region, c.threshold, scale)
                val ok = if (c.mustAppear) r.found else !r.found
                if (ok) okCount++
                else notes.add(
                    if (c.mustAppear) "\"${c.label}\" was not on screen (best ${(r.score * 100).toInt()}%)"
                    else "\"${c.label}\" was still on screen",
                )
            }
            val pass = if (step.anyOf) okCount > 0 else okCount == step.conditions.size
            CondCheck(pass, notes.joinToString(" and "))
        }

    /** Waits until the step's conditions hold, up to the step's time limit. */
    private suspend fun waitForConditions(macroName: String, where: String, step: Step): Outcome {
        val capture = CaptureService.instance
            ?: return Outcome.Fatal("Stopped: screen capture is off. Open MacroLoop, tap Set up, and turn it on.")
        val templates = loadConditionTemplates(step)
            ?: return Outcome.Fatal("Stopped: a condition in step $where has no picture yet. Edit the macro, tap its box and capture it.")
        capture.fast = true
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var why = ""
            var first = true
            while (true) {
                val frame = if (first) CaptureService.grabFresh(startedAt) else CaptureService.grab()
                first = false
                if (frame != null) {
                    val check = checkConditions(frame, step, templates)
                    if (check.ok) return Outcome.Ok
                    why = check.why
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep $where: waiting (${elapsed / 1000} s)" + if (why.isNotEmpty()) "\n$why" else "")
                delay(150)
            }
            return Outcome.Missing("Stopped: step $where waited ${step.timeoutMs / 1000} s, but $why.")
        } finally {
            capture.fast = false
        }
    }

    /** Looks for the picture until it is found and steady, or until the time is up. */
    private suspend fun runPictureStep(macroName: String, where: String, step: Step, human: Boolean): Outcome {
        val template = TemplateStore.load(step.templateId)
            ?: return Outcome.Fatal("Stopped: step $where has no picture yet. Edit the macro, tap the box and capture it.")
        val capture = CaptureService.instance
            ?: return Outcome.Fatal("Stopped: screen capture is off. Open MacroLoop, tap Set up, and turn it on.")
        val condTemplates = loadConditionTemplates(step)
            ?: return Outcome.Fatal("Stopped: a condition in step $where has no picture yet. Edit the macro, tap its box and capture it.")
        capture.fast = true
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var condWhy = ""
            var bestScore = 0.0
            var last: Matcher.Result? = null
            var lastCount = 0
            var steady = 0
            var first = true
            while (true) {
                val frame = if (first) CaptureService.grabFresh(startedAt) else CaptureService.grab()
                first = false
                if (frame != null) {
                    val scale = if (step.capW > 0) frame.width.toDouble() / step.capW else 1.0
                    val region = if (step.wholeScreen) null
                    else Matcher.nearRegion(step.boxX, step.boxY, step.boxW, step.boxH, scale, frame.width, frame.height)
                    val hits: List<Matcher.Result> = withContext(Dispatchers.Default) {
                        if (step.matchAll) Matcher.findAll(frame, template, region, step.threshold, scale)
                        else listOf(Matcher.find(frame, template, region, step.threshold, scale, step.flexScale))
                    }
                    val res = hits.firstOrNull() ?: Matcher.Result(false, 0.0, 0, 0, 0, 0)
                    DebugState.record(frame, res, step.threshold, step.label, hits.drop(1))
                    if (res.tooPlain) {
                        return Outcome.Fatal("Stopped: the picture in step $where is too plain to find. Pick a bigger area with more detail.")
                    }
                    bestScore = maxOf(bestScore, res.score)
                    var condOk = true
                    if (res.found && step.conditions.isNotEmpty()) {
                        val ck = checkConditions(frame, step, condTemplates)
                        condOk = ck.ok
                        if (!condOk) condWhy = ck.why
                    }
                    if (res.found && condOk) {
                        val prev = last
                        steady = if (prev != null && prev.found && lastCount == hits.size &&
                            kotlin.math.abs(prev.x - res.x) <= 8 && kotlin.math.abs(prev.y - res.y) <= 8
                        ) steady + 1 else 1
                        if (steady >= 2) {
                            var allOk = true
                            for ((n, hit) in hits.withIndex()) {
                                if (n > 0) delay(if (human) Random.nextLong(150, 350) else 120)
                                if (!tap(hit.centerX, hit.centerY, human, step.holdMs, step.randomizePx)) allOk = false
                            }
                            return if (allOk) Outcome.Ok
                            else Outcome.Fatal("Stopped: a tap on the picture was cancelled by Android.")
                        }
                    } else {
                        steady = 0
                    }
                    last = res
                    lastCount = hits.size
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep $where: looking for \"${step.label}\" (${elapsed / 1000} s)")
                delay(120)
            }
            return Outcome.Missing(
                if (condWhy.isNotEmpty() && bestScore >= step.threshold / 100.0)
                    "Stopped: found \"${step.label}\" but $condWhy for ${step.timeoutMs / 1000} s."
                else
                    "Stopped: couldn't find \"${step.label}\" for ${step.timeoutMs / 1000} s. " +
                        "Best match was ${(bestScore * 100).toInt()}%, needed ${step.threshold}%.",
            )
        } finally {
            capture.fast = false
        }
    }

    private fun humanTiming(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HUMAN, true)

    private fun waitTime(ms: Long, human: Boolean): Long =
        if (!human) ms else (ms * Random.nextDouble(0.95, 1.10)).toLong() + Random.nextLong(0, 80)

    private fun jitter(range: Int): Int = if (range > 0) Random.nextInt(-range, range + 1) else 0

    private suspend fun tap(x: Int, y: Int, human: Boolean, holdMs: Long, randomizePx: Int): Boolean {
        val spread = maxOf(randomizePx, if (human) 3 else 0)
        val px = (x + jitter(spread)).coerceAtLeast(0)
        val py = (y + jitter(spread)).coerceAtLeast(0)
        val hold = when {
            holdMs > 0 -> holdMs
            human -> Random.nextLong(50, 95)
            else -> DEFAULT_HOLD_MS
        }
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, hold))
    }

    private suspend fun swipe(step: Step, human: Boolean): Boolean {
        val spread = maxOf(step.randomizePx, if (human) 3 else 0)
        val path = Path().apply {
            moveTo((step.x + jitter(spread)).coerceAtLeast(0).toFloat(), (step.y + jitter(spread)).coerceAtLeast(0).toFloat())
            lineTo((step.x2 + jitter(spread)).coerceAtLeast(0).toFloat(), (step.y2 + jitter(spread)).coerceAtLeast(0).toFloat())
        }
        val duration = if (human) (step.swipeMs * Random.nextDouble(0.9, 1.15)).toLong() else step.swipeMs
        return dispatch(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(50)))
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ---------------------------------------------------------------- run bar

    private fun showRunBar() {
        removeRunBar()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = Ui.rounded(TokenInts.surfaceHigh, dp(16).toFloat())
        }
        val status = TextView(this).apply {
            setTextColor(TokenInts.text)
            textSize = 14f
            maxWidth = dp(240)
        }
        val stop = Ui.button(this, "Stop", TokenInts.danger, TokenInts.onDanger) { stopRun() }
        bar.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(stop, LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginStart = dp(12) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        wm.addView(bar, lp)
        runBar = bar
        runStatus = status
    }

    private fun setStatus(text: String) {
        runStatus?.text = text
    }

    private fun removeRunBar() {
        runBar?.let { runCatching { wm.removeView(it) } }
        runBar = null
        runStatus = null
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
