package com.macroloop.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 *  1. performs taps, swipes and device actions for the macro runner,
 *  2. hosts the floating editor and the floating Play button,
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
        const val PREF_SHOW_DETECT = "show_detect"
        const val PREF_HIGHLIGHT = "highlight_success"
        private const val DEFAULT_HOLD_MS = 60L
        private const val START_SETTLE_MS = 350L
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var runBar: View? = null
    private var runStatus: TextView? = null
    private var editor: EditorOverlay? = null
    private var playChip: PlayChip? = null
    private var playMacroId: String? = null
    private var playX = 24
    private var playY = 260

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

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopRun()
        closeEditor()
        closePlayChip()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- editor and play button

    /** Opens the round editor button for a macro. Tapping it opens the menu, then the list. */
    fun startEditor(macroId: String) {
        stopRun()
        closePlayChip()
        closeEditor()
        val macro = MacroRepository.get(macroId) ?: return
        editor = EditorOverlay(this, wm, macro, scope) { closeEditor() }
        editor?.show()
    }

    private fun closeEditor() {
        editor?.dismiss()
        editor = null
    }

    /**
     * Opens the floating Play button for a saved macro. The macro does NOT start until Play is pressed,
     * so there is time to open the game or app first.
     */
    fun showPlayChip(macroId: String) {
        val macro = MacroRepository.get(macroId) ?: return
        closeEditor()
        stopRun()
        val first = playChip == null
        playChip?.dismiss()
        playMacroId = macroId
        playChip = PlayChip(this, wm, macro.name, playX, playY, { x, y -> playX = x; playY = y }, { startFromPlayChip() }, { closePlayChip() }).also { it.show() }
        if (first) Toast.makeText(this, "${macro.name} is ready. Open your app, then press Play.", Toast.LENGTH_LONG).show()
    }

    private fun startFromPlayChip() {
        val id = playMacroId ?: return
        val macro = MacroRepository.get(id) ?: return
        playChip?.dismiss()
        playChip = null
        runMacro(macro, onFinished = {
            // Back to the Play button when the run ends or is stopped, unless a new run started.
            scope.launch {
                delay(150)
                if (playMacroId == id && !isRunning()) showPlayChip(id)
            }
        })
    }

    private fun closePlayChip() {
        playMacroId = null
        playChip?.dismiss()
        playChip = null
    }

    // ---------------------------------------------------------------- running

    fun isRunning() = runJob?.isActive == true

    private fun allSteps(list: List<Step>): List<Step> = list.flatMap { listOf(it) + allSteps(it.onSuccess) + allSteps(it.onFail) }

    /** True if the steps look at the screen (a picture step or a picture condition). */
    fun needsCapture(list: List<Step>): Boolean = allSteps(list).any { s ->
        s.type == StepType.CLICK_IMAGE || s.conditions.any { it.kind == ConditionKind.IMAGE }
    }

    /** Checks the whole macro before it starts, so a setup problem is said at once and not after a long wait. */
    private fun preflight(steps: List<Step>): String? {
        val all = allSteps(steps)
        if (needsCapture(steps) && !CaptureService.running) {
            return "Screen capture is off. Open MacroLoop and press Edit Mode or Play Mode again, then tap Start."
        }
        for (s in all) {
            if (s.enabled && s.type == StepType.CLICK_IMAGE && TemplateStore.load(s.templateId) == null) {
                return "A picture step has no picture yet. Edit the macro and capture it."
            }
            for (c in s.conditions) {
                if (s.enabled && c.kind == ConditionKind.IMAGE && TemplateStore.load(c.templateId) == null) {
                    return "A condition has no picture yet. Edit the macro and capture it."
                }
            }
        }
        return null
    }

    /**
     * Runs a macro right away (no countdown).
     * @param startList the list to run first. Null means the whole macro.
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
            val capture = CaptureService.instance
            try {
                val problem = preflight(macro.steps)
                if (problem != null) {
                    finish("Stopped: $problem")
                    return@launch
                }
                capture?.fast = true
                // A short pause lets Android finish hiding the editor or the Play button,
                // so the first tap does not land on a window that is still there.
                setStatus("${macro.name}: starting")
                delay(START_SETTLE_MS)
                val end = FlowEngine(RunHost(macro.name)).run(macro, startList, rounds)
                finish(end ?: "Finished: ${macro.name}")
            } catch (e: CancellationException) {
                throw e
            } finally {
                capture?.fast = false
                onFinished?.invoke()
            }
        }
    }

    /** Test one step on its own: its action only, with no conditions, branches or jumps. */
    fun testStep(macro: Macro, step: Step, onFinished: (() -> Unit)?) {
        val alone = step.copy(onSuccess = emptyList(), onFail = emptyList(), conditions = emptyList(), enabled = true)
        runMacro(macro, onFinished, startList = listOf(alone), rounds = 1)
    }

    /** Test from one step to the end of its list, then on through any Go to link. */
    fun testFrom(macro: Macro, list: List<Step>, index: Int, onFinished: (() -> Unit)?) {
        runMacro(macro, onFinished, startList = list.drop(index), rounds = 1)
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

    // ---------------------------------------------------------------- the host the engine runs on

    private inner class RunHost(private val macroName: String) : StepHost {
        private val human = humanTiming()
        private val templates = HashMap<String, Bitmap?>()

        private fun template(id: String): Bitmap? = templates.getOrPut(id) { TemplateStore.load(id) }

        override suspend fun perform(step: Step, where: String): Outcome = when (step.type) {
            StepType.CLICK -> clickAt(step, step.x + step.offsetX, step.y + step.offsetY, where)
            StepType.SWIPE ->
                if (swipe(step, human)) Outcome.Ok else Outcome.Fatal("Stopped: the swipe in step $where was cancelled by Android.")
            StepType.CLICK_IMAGE -> runPictureStep(where, step)
            StepType.PRESS_BACK -> global(GLOBAL_ACTION_BACK, "Press Back", where)
            StepType.PRESS_HOME -> global(GLOBAL_ACTION_HOME, "Press Home", where)
            StepType.OPEN_RECENT -> global(GLOBAL_ACTION_RECENTS, "Open Recent", where)
            StepType.OPEN_NOTIFICATION -> global(GLOBAL_ACTION_NOTIFICATIONS, "Open Notification", where)
            StepType.OPEN_QUICKSETTING -> global(GLOBAL_ACTION_QUICK_SETTINGS, "Open Quicksetting", where)
            StepType.LOCK_SCREEN -> global(GLOBAL_ACTION_LOCK_SCREEN, "Lock Screen", where)
            StepType.TOAST -> {
                Toast.makeText(this@MacroService, step.text.ifBlank { "MacroLoop" }, Toast.LENGTH_SHORT).show()
                Outcome.Ok
            }
            StepType.OPEN_APP -> openApp(step.text, where)
            else -> Outcome.Ok
        }

        override suspend fun imageHolds(c: Condition): ImageCheck {
            val t = template(c.templateId) ?: return ImageCheck(false, "\"${c.label}\" has no picture")
            val frame = CaptureService.grabFresh(SystemClock.elapsedRealtime() - 120, 250)
                ?: return ImageCheck(false, "\"${c.label}\" could not be checked, the screen is not visible")
            val scale = if (c.capW > 0) frame.width.toDouble() / c.capW else 1.0
            val region = if (c.wholeScreen) null
            else Matcher.nearRegion(c.boxX, c.boxY, c.boxW, c.boxH, scale, frame.width, frame.height)
            val r = withContext(Dispatchers.Default) { Matcher.find(frame, t, region, c.threshold, scale) }
            DebugState.record(frame, r, c.threshold, c.label)
            val holds = r.found == c.mustAppear
            val note = if (c.mustAppear) "\"${c.label}\" was not on screen (best ${(r.score * 100).toInt()}%)"
            else "\"${c.label}\" was still on screen"
            return ImageCheck(holds, note)
        }

        override suspend fun pause(ms: Long) = delay(ms)

        override fun now(): Long = SystemClock.elapsedRealtime()

        override fun status(text: String) = setStatus(text)

        override fun roll(): Int = Random.nextInt(100)

        override fun humanWait(ms: Long): Long =
            if (!human) ms else (ms * Random.nextDouble(0.95, 1.10)).toLong() + Random.nextLong(0, 80)

        private suspend fun clickAt(step: Step, x: Int, y: Int, where: String): Outcome {
            for (n in 1..step.clicks) {
                if (!tap(x, y, human, step.holdMs, step.randomizePx)) {
                    return Outcome.Fatal("Stopped: the tap in step $where was cancelled by Android.")
                }
                if (n < step.clicks) delay(if (human) Random.nextLong(70, 150) else 90)
            }
            return Outcome.Ok
        }

        private fun global(action: Int, what: String, where: String): Outcome =
            if (performGlobalAction(action)) Outcome.Ok else Outcome.Fatal("Stopped: Android would not do \"$what\" in step $where.")

        private fun openApp(pkg: String, where: String): Outcome {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?: return Outcome.Fatal("Stopped: the app in step $where is not installed.")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { startActivity(launch) }
                .fold({ Outcome.Ok }, { Outcome.Fatal("Stopped: Android would not open the app in step $where.") })
        }

        /** Looks for the picture until it is found and steady, or until the time is up. */
        private suspend fun runPictureStep(where: String, step: Step): Outcome {
            val t = template(step.templateId)
                ?: return Outcome.Fatal("Stopped: step $where has no picture yet. Edit the macro and capture it.")
            val startedAt = SystemClock.elapsedRealtime()
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
                        if (step.matchAll) Matcher.findAll(frame, t, region, step.threshold, scale)
                        else listOf(Matcher.find(frame, t, region, step.threshold, scale, step.flexScale))
                    }
                    val res = hits.firstOrNull() ?: Matcher.Result(false, 0.0, 0, 0, 0, 0)
                    DebugState.record(frame, res, step.threshold, step.label, hits.drop(1))
                    if (res.tooPlain) {
                        return Outcome.Fatal("Stopped: the picture in step $where is too plain to find. Pick a bigger area with more detail.")
                    }
                    bestScore = maxOf(bestScore, res.score)
                    if (res.found) {
                        val prev = last
                        steady = if (prev != null && prev.found && lastCount == hits.size &&
                            kotlin.math.abs(prev.x - res.x) <= 8 && kotlin.math.abs(prev.y - res.y) <= 8
                        ) steady + 1 else 1
                        if (steady >= 2) {
                            flash(hits, true)
                            for ((n, hit) in hits.withIndex()) {
                                if (n > 0) delay(if (human) Random.nextLong(150, 350) else 120)
                                val o = clickAt(step, hit.centerX + step.offsetX, hit.centerY + step.offsetY, where)
                                if (o !is Outcome.Ok) return o
                            }
                            return Outcome.Ok
                        }
                    } else {
                        steady = 0
                    }
                    last = res
                    lastCount = hits.size
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep $where: looking for \"${step.label.ifBlank { "the picture" }}\" (${elapsed / 1000} s)")
                delay(120)
            }
            flash(listOfNotNull(last), false)
            return Outcome.Missing(
                "Stopped: couldn't find \"${step.label.ifBlank { "the picture" }}\" for ${step.timeoutMs / 1000} s. " +
                    "Best match was ${(bestScore * 100).toInt()}%, needed ${step.threshold}%.",
            )
        }
    }

    // ---------------------------------------------------------------- debug boxes

    /** Draws a box where the picture was found, if "Show Detect Location" is on in the Play button's gear. */
    private fun flash(hits: List<Matcher.Result>, success: Boolean) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_SHOW_DETECT, false)) return
        if (success && !prefs.getBoolean(PREF_HIGHLIGHT, false) && false) return
        val color = if (success) TokenInts.success else TokenInts.warning
        val views = ArrayList<View>()
        for (r in hits) {
            if (r.w <= 0) continue
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (success && prefs.getBoolean(PREF_HIGHLIGHT, false)) (color and 0x00FFFFFF) or 0x55000000 else Color.TRANSPARENT)
                    setStroke(Ui.dp(this@MacroService, 3), color)
                }
            }
            val lp = WindowManager.LayoutParams(
                r.w, r.h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = r.x
                y = r.y
            }
            runCatching { wm.addView(v, lp) }.onSuccess { views.add(v) }
        }
        if (views.isEmpty()) return
        scope.launch {
            delay(450)
            views.forEach { runCatching { wm.removeView(it) } }
        }
    }

    // ---------------------------------------------------------------- gestures

    private fun humanTiming(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HUMAN, true)

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
