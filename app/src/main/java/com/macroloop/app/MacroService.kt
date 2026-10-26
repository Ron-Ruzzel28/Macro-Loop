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
import kotlinx.coroutines.async
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
        private const val HOLD_SEGMENT_MS = 350L
        private const val FINGER_GAP_PX = 170
        private const val START_SETTLE_MS = 350L
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var runBar: View? = null
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
        License.init(applicationContext)
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
    /** True if the licence allows running. If not, says why on screen. */
    private fun licensed(): Boolean {
        if (License.allowed(this)) return true
        Toast.makeText(this, License.message.ifBlank { "MacroLoop is locked. Open the app for details." }, Toast.LENGTH_LONG).show()
        return false
    }

    fun startEditor(macroId: String) {
        if (!licensed()) return
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
        if (!licensed()) return
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
        s.type == StepType.CLICK_IMAGE || s.holdUntil != null || s.conditions.any { it.kind == ConditionKind.IMAGE }
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
            val hold = s.holdUntil
            if (s.enabled && hold != null && TemplateStore.load(hold.templateId) == null) {
                return "A hold-until has no picture yet. Edit the macro and capture it."
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
            var dog: Job? = null
            try {
                if (!License.allowed(this@MacroService)) {
                    finish("Stopped: " + License.message)
                    return@launch
                }
                // Refresh the licence in the background when the last answer is getting old.
                if (License.ageMs(this@MacroService) > License.STALE_MS) {
                    launch(Dispatchers.IO) { License.checkIn(this@MacroService) }
                }
                // While a macro runs, look again every few minutes, so a switch-off or an ended trial stops it.
                dog = launch {
                    while (true) {
                        delay(5 * 60_000L)
                        if (!License.allowed(this@MacroService)) {
                            finish("Stopped: " + License.message)
                            runJob?.cancel()
                            break
                        }
                    }
                }
                val problem = preflight(macro.steps)
                if (problem != null) {
                    finish("Stopped: $problem")
                    return@launch
                }
                capture?.fast = true
                // A short pause lets Android finish hiding the editor or the Play button,
                // so the first tap does not land on a window that is still there.
                delay(START_SETTLE_MS)
                val end = FlowEngine(RunHost(macro.name)).run(macro, startList, rounds)
                finish(end ?: "Finished: ${macro.name}")
            } catch (e: CancellationException) {
                throw e
            } finally {
                dog?.cancel()
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

    /** Ends a run: the stop button goes away at once and the result is shown for a moment as a message. */
    private fun finish(message: String) {
        removeRunBar()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

        // No step-by-step report on screen: it would cover what the macro is looking at and tapping.
        override fun status(text: String) = Unit

        override fun roll(): Int = Random.nextInt(100)

        override fun humanWait(ms: Long): Long =
            if (!human) ms else (ms * Random.nextDouble(0.95, 1.10)).toLong() + Random.nextLong(0, 80)

        private suspend fun clickAt(step: Step, x: Int, y: Int, where: String): Outcome {
            if (step.holdUntil != null) return holdUntilTap(step, x, y, where)
            for (n in 1..step.clicks) {
                if (!tapOnce(x, y, step, human)) {
                    return Outcome.Fatal("Stopped: the tap in step $where was cancelled by Android.")
                }
                if (n < step.clicks) {
                    val gap = step.clickGapMs.coerceAtLeast(20)
                    delay(if (human) (gap * Random.nextDouble(0.85, 1.2)).toLong() else gap)
                }
            }
            return Outcome.Ok
        }

        /**
         * Presses and holds, looks at the screen while holding, and lets go as soon as the picture appears
         * (or disappears). If it never does within the longest hold, lets go and the step counts as FALSE.
         * Android lets a press be carried on by sending the next piece of it right after the last one.
         */
        private suspend fun holdUntilTap(step: Step, x: Int, y: Int, where: String): Outcome {
            val until = step.holdUntil ?: return Outcome.Ok
            val spread = maxOf(step.randomizePx, if (human) 3 else 0)
            val paths = fingerXs(x, step.twoFingers).map { px ->
                Path().apply { moveTo((px + jitter(spread)).coerceAtLeast(0).toFloat(), (y + jitter(spread)).coerceAtLeast(0).toFloat()) }
            }
            var strokes = paths.map { GestureDescription.StrokeDescription(it, 0, HOLD_SEGMENT_MS, true) }
            if (!dispatchAll(strokes)) return Outcome.Fatal("Stopped: the hold in step $where was cancelled by Android.")
            val started = SystemClock.elapsedRealtime()
            while (true) {
                val next = strokes.mapIndexed { i, st -> st.continueStroke(paths[i], 0, HOLD_SEGMENT_MS, true) }
                var holds = false
                // Look at the screen while the next piece of the press is going, so there is no gap.
                val sent = kotlinx.coroutines.coroutineScope {
                    val look = async { imageHolds(until).holds }
                    val ok = dispatchAll(next)
                    holds = look.await()
                    ok
                }
                if (!sent) return Outcome.Fatal("Stopped: the hold in step $where was cancelled by Android.")
                strokes = next
                val over = SystemClock.elapsedRealtime() - started >= step.holdMaxMs
                if (holds || over) {
                    val last = strokes.mapIndexed { i, st -> st.continueStroke(paths[i], 0, 40, false) }
                    dispatchAll(last)
                    return if (holds) Outcome.Ok else Outcome.Missing(
                        "Stopped: held for ${step.holdMaxMs / 1000} s in step $where, but \"${until.label.ifBlank { "the picture" }}\" " +
                            "never ${if (until.mustAppear) "appeared" else "disappeared"}.",
                    )
                }
            }
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

    /** The x of each finger: one in the middle, or two a little apart. */
    private fun fingerXs(x: Int, two: Boolean): List<Int> =
        if (two) listOf((x - FINGER_GAP_PX / 2).coerceAtLeast(0), x + FINGER_GAP_PX / 2) else listOf(x)

    /** One tap, with the step's hold time, random offset and fingers. */
    private suspend fun tapOnce(x: Int, y: Int, step: Step, human: Boolean): Boolean {
        val spread = maxOf(step.randomizePx, if (human) 3 else 0)
        val hold = when {
            step.holdMs > 0 -> if (human) (step.holdMs * Random.nextDouble(0.95, 1.08)).toLong() else step.holdMs
            human -> Random.nextLong(50, 95)
            else -> DEFAULT_HOLD_MS
        }.coerceIn(20L, 59_000L)
        val strokes = fingerXs(x, step.twoFingers).map { px ->
            val path = Path().apply { moveTo((px + jitter(spread)).coerceAtLeast(0).toFloat(), (y + jitter(spread)).coerceAtLeast(0).toFloat()) }
            GestureDescription.StrokeDescription(path, 0, hold)
        }
        return dispatchAll(strokes)
    }

    private suspend fun swipe(step: Step, human: Boolean): Boolean {
        val spread = maxOf(step.randomizePx, if (human) 3 else 0)
        val path = Path().apply {
            moveTo((step.x + jitter(spread)).coerceAtLeast(0).toFloat(), (step.y + jitter(spread)).coerceAtLeast(0).toFloat())
            lineTo((step.x2 + jitter(spread)).coerceAtLeast(0).toFloat(), (step.y2 + jitter(spread)).coerceAtLeast(0).toFloat())
        }
        val duration = if (human) (step.swipeMs * Random.nextDouble(0.9, 1.15)).toLong() else step.swipeMs
        return dispatchAll(listOf(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(50))))
    }

    /** Sends one or more strokes at the same time (one finger or two) and waits until they finish. */
    private suspend fun dispatchAll(strokes: List<GestureDescription.StrokeDescription>): Boolean =
        suspendCancellableCoroutine { cont ->
            val builder = GestureDescription.Builder()
            strokes.forEach { builder.addStroke(it) }
            val accepted = dispatchGesture(
                builder.build(),
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

    /**
     * While a macro runs, the only thing on screen is one small round Stop button, where the Play button was.
     * Drag it out of the way if it covers something; tap it to stop. There is no step-by-step report.
     */
    private fun showRunBar() {
        removeRunBar()
        val size = dp(44)
        val btn = android.widget.FrameLayout(this).apply {
            background = Ui.rounded(0xD9F87171.toInt(), size / 2f)
            alpha = 0.9f
            contentDescription = "Stop the macro"
        }
        btn.addView(
            View(this).apply { background = Ui.rounded(0xFF2B0A0A.toInt(), dp(3).toFloat()) },
            android.widget.FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER),
        )
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = playX
            y = playY
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        btn.setOnTouchListener { _, e ->
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
                        playX = lp.x
                        playY = lp.y
                        runCatching { wm.updateViewLayout(btn, lp) }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> if (!moved) {
                    stopRun()
                    Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        wm.addView(btn, lp)
        runBar = btn
    }

    private fun removeRunBar() {
        runBar?.let { runCatching { wm.removeView(it) } }
        runBar = null
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
