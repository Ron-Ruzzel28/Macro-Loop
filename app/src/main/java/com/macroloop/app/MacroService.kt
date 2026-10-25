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
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var runBar: View? = null
    private var runStatus: TextView? = null
    private var editor: EditorOverlay? = null

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
        closeEditor()
        val macro = MacroRepository.get(macroId) ?: return
        editor = EditorOverlay(this, wm, macro, scope) { closeEditor() }
        editor?.show()
    }

    private fun closeEditor() {
        editor?.dismiss()
        editor = null
    }

    // ---------------------------------------------------------------- running

    fun isRunning() = runJob?.isActive == true

    /** Run a saved macro from the app. Closes the editor first. */
    fun startRun(macroId: String, countdownSeconds: Int = 3) {
        val macro = MacroRepository.get(macroId) ?: return
        closeEditor()
        runMacro(macro, countdownSeconds, null)
    }

    /** Run the given macro now. [onFinished] is called when it ends, is stopped, or fails. */
    fun runMacro(macro: Macro, countdownSeconds: Int, onFinished: (() -> Unit)?) {
        stopRun()
        showRunBar()
        runJob = scope.launch {
            val human = humanTiming()
            try {
                for (s in countdownSeconds downTo 1) {
                    setStatus("${macro.name}: starting in $s s")
                    delay(1000)
                }
                for (round in 1..macro.repeat) {
                    for ((i, step) in macro.steps.withIndex()) {
                        setStatus("${macro.name}\nRound $round of ${macro.repeat}, step ${i + 1} of ${macro.steps.size}")
                        val problem = runStep(macro.name, i, step, human)
                        if (problem != null) {
                            finish(problem)
                            return@launch
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

    /** Runs one step with its delay, repeat and wait-next settings. Returns a message if the macro must stop. */
    private suspend fun runStep(macroName: String, index: Int, step: Step, human: Boolean): String? {
        if (step.type == StepType.WAIT) {
            delay(waitTime(step.ms, human))
            return null
        }
        if (step.delayMs > 0) delay(step.delayMs)
        if (step.type != StepType.CLICK_IMAGE && step.conditions.isNotEmpty()) {
            when (val gate = waitForConditions(macroName, index, step)) {
                Gate.Open -> Unit
                Gate.Skip -> return null
                is Gate.Fail -> return gate.message
            }
        }
        repeat(step.repeatCount) {
            when (step.type) {
                StepType.CLICK -> {
                    val ok = tap(step.x, step.y, human, step.holdMs, step.randomizePx)
                    if (!ok) return "Stopped: the tap in step ${index + 1} was cancelled by Android."
                }
                StepType.SWIPE -> {
                    val ok = swipe(step, human)
                    if (!ok) return "Stopped: the swipe in step ${index + 1} was cancelled by Android."
                }
                StepType.CLICK_IMAGE -> when (val outcome = runPictureStep(macroName, index, step, human)) {
                    PictureOutcome.Done, PictureOutcome.Skipped -> Unit
                    is PictureOutcome.Failed -> return outcome.message
                }
                StepType.WAIT -> Unit
            }
        }
        val after = step.waitNextMs + if (human) Random.nextLong(50, 250) else 0L
        if (after > 0) delay(after)
        return null
    }


    // ---------------------------------------------------------------- conditions

    private sealed class Gate {
        data object Open : Gate()
        data object Skip : Gate()
        data class Fail(val message: String) : Gate()
    }

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
    private suspend fun waitForConditions(macroName: String, index: Int, step: Step): Gate {
        val capture = CaptureService.instance
            ?: return Gate.Fail("Stopped: screen capture is off. Open MacroLoop, tap Set up, and turn it on.")
        val templates = loadConditionTemplates(step)
            ?: return Gate.Fail("Stopped: a condition in step ${index + 1} has no picture yet. Edit the macro, tap its box and capture it.")
        capture.fast = true
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var why = ""
            while (true) {
                val frame = CaptureService.grab()
                if (frame != null) {
                    val check = checkConditions(frame, step, templates)
                    if (check.ok) return Gate.Open
                    why = check.why
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep ${index + 1}: waiting (${elapsed / 1000} s)" + if (why.isNotEmpty()) "\n$why" else "")
                delay(150)
            }
            return if (step.skipIfMissing) Gate.Skip
            else Gate.Fail("Stopped: step ${index + 1} waited ${step.timeoutMs / 1000} s, but $why.")
        } finally {
            capture.fast = false
        }
    }

    private sealed class PictureOutcome {
        data object Done : PictureOutcome()
        data object Skipped : PictureOutcome()
        data class Failed(val message: String) : PictureOutcome()
    }

    /** Looks for the picture until it is found and steady, or until the time is up. */
    private suspend fun runPictureStep(macroName: String, index: Int, step: Step, human: Boolean): PictureOutcome {
        val template = TemplateStore.load(step.templateId)
            ?: return PictureOutcome.Failed("Stopped: step ${index + 1} has no picture yet. Edit the macro, tap the box and capture it.")
        val capture = CaptureService.instance
            ?: return PictureOutcome.Failed("Stopped: screen capture is off. Open MacroLoop, tap Set up, and turn it on.")
        val condTemplates = loadConditionTemplates(step)
            ?: return PictureOutcome.Failed("Stopped: a condition in step ${index + 1} has no picture yet. Edit the macro, tap its box and capture it.")
        capture.fast = true
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var condWhy = ""
            var bestScore = 0.0
            var last: Matcher.Result? = null
            var lastCount = 0
            var steady = 0
            while (true) {
                val frame = CaptureService.grab()
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
                        return PictureOutcome.Failed("Stopped: the picture in step ${index + 1} is too plain to find. Pick a bigger area with more detail.")
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
                            return if (allOk) PictureOutcome.Done
                            else PictureOutcome.Failed("Stopped: a tap on the picture was cancelled by Android.")
                        }
                    } else {
                        steady = 0
                    }
                    last = res
                    lastCount = hits.size
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep ${index + 1}: looking for \"${step.label}\" (${elapsed / 1000} s)")
                delay(120)
            }
            return if (step.skipIfMissing) PictureOutcome.Skipped
            else if (condWhy.isNotEmpty() && bestScore >= step.threshold / 100.0) PictureOutcome.Failed(
                "Stopped: found \"${step.label}\" but $condWhy for ${step.timeoutMs / 1000} s.",
            )
            else PictureOutcome.Failed(
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
