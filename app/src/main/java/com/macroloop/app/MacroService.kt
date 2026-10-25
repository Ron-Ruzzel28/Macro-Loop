package com.macroloop.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Accessibility service. It does three jobs:
 *  1. performs taps for the macro runner,
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

    fun startRun(macroId: String, countdownSeconds: Int = 3) {
        val macro = MacroRepository.get(macroId) ?: return
        stopRun()
        closeEditor()
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
                        when (step.type) {
                            StepType.CLICK -> {
                                val ok = tap(step.x, step.y, human)
                                if (!ok) {
                                    finish("Stopped: the tap in step ${i + 1} was cancelled by Android.")
                                    return@launch
                                }
                                delay(if (human) Random.nextLong(150, 350) else 100)
                            }
                            StepType.WAIT -> delay(waitTime(step.ms, human))
                            StepType.CLICK_IMAGE -> {
                                when (val outcome = runPictureStep(macro.name, i, step, human)) {
                                    PictureOutcome.Done, PictureOutcome.Skipped ->
                                        delay(if (human) Random.nextLong(150, 350) else 100)
                                    is PictureOutcome.Failed -> {
                                        finish(outcome.message)
                                        return@launch
                                    }
                                }
                            }
                        }
                    }
                }
                finish("Finished: ${macro.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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


    private sealed class PictureOutcome {
        data object Done : PictureOutcome()
        data object Skipped : PictureOutcome()
        data class Failed(val message: String) : PictureOutcome()
    }

    /** Looks for the picture until it is found and steady, or until the time is up. */
    private suspend fun runPictureStep(macroName: String, index: Int, step: Step, human: Boolean): PictureOutcome {
        val template = TemplateStore.load(step.templateId)
            ?: return PictureOutcome.Failed("Stopped: the picture for step ${index + 1} is missing. Edit the macro and add it again.")
        val capture = CaptureService.instance
            ?: return PictureOutcome.Failed("Stopped: screen capture is off. Open MacroLoop, tap Set up, and turn it on.")
        capture.fast = true
        try {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var bestScore = 0.0
            var last: Matcher.Result? = null
            var steady = 0
            while (true) {
                val frame = CaptureService.grab()
                if (frame != null) {
                    val scale = if (step.capW > 0) frame.width.toDouble() / step.capW else 1.0
                    val region = if (step.wholeScreen) null
                    else Matcher.nearRegion(step.boxX, step.boxY, step.boxW, step.boxH, scale, frame.width, frame.height)
                    val res = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        Matcher.find(frame, template, region, step.threshold, scale)
                    }
                    DebugState.record(frame, res, step.threshold, step.label)
                    if (res.tooPlain) {
                        return PictureOutcome.Failed("Stopped: the picture in step ${index + 1} is too plain to find. Pick a bigger area with more detail.")
                    }
                    bestScore = maxOf(bestScore, res.score)
                    if (res.found) {
                        val prev = last
                        steady = if (prev != null && prev.found &&
                            kotlin.math.abs(prev.x - res.x) <= 8 && kotlin.math.abs(prev.y - res.y) <= 8
                        ) steady + 1 else 1
                        if (steady >= 2) {
                            val ok = tap(res.centerX, res.centerY, human)
                            return if (ok) PictureOutcome.Done
                            else PictureOutcome.Failed("Stopped: the tap on the picture was cancelled by Android.")
                        }
                    } else {
                        steady = 0
                    }
                    last = res
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= step.timeoutMs) break
                setStatus("$macroName\nStep ${index + 1}: looking for \"${step.label}\" (${elapsed / 1000} s)")
                delay(120)
            }
            return if (step.skipIfMissing) PictureOutcome.Skipped
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

    private suspend fun tap(x: Int, y: Int, human: Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            val jitter = if (human) 3 else 0
            val px = (x + if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0).coerceAtLeast(0)
            val py = (y + if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0).coerceAtLeast(0)
            val hold = if (human) Random.nextLong(50, 95) else 60L
            val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, hold))
                .build()
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
        bar.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) })

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
