package com.macroloop.app

/**
 * The run rules, in one place, with no Android in it, so they can be checked by the self-test.
 *
 * The rules:
 *  - a disabled step is skipped, with its branches, and does not change "did the step before work";
 *  - a step with conditions waits for them (only a picture condition can change over time) up to its time limit;
 *  - a step that works is TRUE: its On success steps run, then the list goes on;
 *  - a picture or condition that never shows up is FALSE: its On fail steps run and the list goes on;
 *    with no On fail steps the step is skipped if "skip" is chosen, otherwise the macro stops;
 *  - setup trouble (capture off, a missing picture file, a cancelled tap) always stops the macro;
 *  - Stop ends the macro from anywhere, even inside a branch;
 *  - Go to jumps to a step of the main list, and the run carries on from there;
 *  - a jump limit ends a loop that never ends.
 */
sealed class Outcome {
    data object Ok : Outcome()

    /** The picture or condition did not show up. A branch or "skip" can handle this. */
    data class Missing(val message: String) : Outcome()

    /** A problem no branch should hide. */
    data class Fatal(val message: String) : Outcome()
}

sealed class Flow {
    data object Next : Flow()
    data class Stop(val message: String) : Flow()
    data class Jump(val targetId: String) : Flow()
}

/** One look at one picture condition: [holds] already takes "must appear / must not appear" into account. */
class ImageCheck(val holds: Boolean, val note: String = "")

/** What the engine needs from the phone. The real one taps and looks at the screen; the test one is scripted. */
interface StepHost {
    /** Does the step's own action once: tap, swipe, find and tap a picture, a device action... */
    suspend fun perform(step: Step, where: String): Outcome

    /** Looks once for a picture condition, on a fresh frame. */
    suspend fun imageHolds(c: Condition): ImageCheck

    suspend fun pause(ms: Long)

    /** A clock in milliseconds that only goes forward. */
    fun now(): Long

    fun status(text: String)

    /** A number from 0 to 99, for Random True. */
    fun roll(): Int

    /** A wait time with a little human-like variation (or the same time if that is off). */
    fun humanWait(ms: Long): Long
}

class RunState {
    val onceUsed = HashSet<String>()
    var jumps = 0
}

private class StepResult(val flow: Flow, val worked: Boolean)

class FlowEngine(private val host: StepHost, private val maxJumps: Int = 5000) {

    /**
     * @param startList the list to run first. Null means the whole macro. A Go to link can jump to any
     *        step of the macro's main list; the run then carries on from there.
     * @return null when the run finished, otherwise the reason it stopped.
     */
    suspend fun run(macro: Macro, startList: List<Step>? = null, rounds: Int = macro.repeat): String? {
        val state = RunState()
        val root = macro.steps
        for (round in 1..rounds) {
            var cur: List<Step> = startList ?: root
            var i = 0
            var prevOk = true
            while (i < cur.size) {
                host.status("${macro.name}\nRound $round of $rounds, step ${i + 1} of ${cur.size}")
                val r = runStep(macro.name, (i + 1).toString(), cur[i], prevOk, state)
                prevOk = r.worked
                when (val f = r.flow) {
                    Flow.Next -> i++
                    is Flow.Stop -> return f.message
                    is Flow.Jump -> {
                        val at = root.indexOfFirst { it.id == f.targetId }
                        if (at < 0) return "Stopped: a Go to link points to a step that no longer exists."
                        if (++state.jumps > maxJumps) {
                            return "Stopped: too many jumps. A Go to link may be looping with nothing to stop it."
                        }
                        cur = root
                        i = at
                        prevOk = true
                    }
                }
            }
        }
        return null
    }

    private suspend fun runList(macroName: String, list: List<Step>, prefix: String, state: RunState): Flow {
        var prevOk = true
        for ((i, step) in list.withIndex()) {
            val where = "$prefix #${i + 1}"
            host.status("$macroName\nStep $where of ${list.size}")
            val r = runStep(macroName, where, step, prevOk, state)
            prevOk = r.worked
            if (r.flow !is Flow.Next) return r.flow
        }
        return Flow.Next
    }

    private suspend fun runStep(macroName: String, where: String, step: Step, prevOk: Boolean, state: RunState): StepResult {
        if (!step.enabled) return StepResult(Flow.Next, prevOk)
        when (step.type) {
            StepType.WAIT -> {
                host.pause(host.humanWait(step.ms))
                return StepResult(Flow.Next, true)
            }
            StepType.STOP_MACRO -> return StepResult(Flow.Stop("Stopped by a Stop step ($where)."), true)
            StepType.GOTO -> return StepResult(
                if (step.targetId.isEmpty()) Flow.Stop("Stopped: the Go to step $where has no target. Edit it and choose one.")
                else Flow.Jump(step.targetId),
                true,
            )
            else -> Unit
        }
        if (step.delayMs > 0) host.pause(step.delayMs)

        var outcome: Outcome = Outcome.Ok
        if (step.conditions.isNotEmpty()) outcome = waitConditions(step, where, prevOk, state)
        if (outcome is Outcome.Ok) {
            for (r in 1..step.repeatCount) {
                outcome = host.perform(step, where)
                if (outcome !is Outcome.Ok) break
            }
        }

        return when (val o = outcome) {
            Outcome.Ok -> {
                val after = step.waitNextMs
                if (after > 0) host.pause(host.humanWait(after))
                val flow = if (step.onSuccess.isNotEmpty()) runList(macroName, step.onSuccess, "$where on success", state) else Flow.Next
                StepResult(flow, true)
            }
            is Outcome.Fatal -> StepResult(Flow.Stop(o.message), false)
            is Outcome.Missing -> when {
                step.onFail.isNotEmpty() -> StepResult(runList(macroName, step.onFail, "$where on fail", state), false)
                step.skipIfMissing -> StepResult(Flow.Next, false)
                else -> StepResult(Flow.Stop(o.message), false)
            }
        }
    }

    /** Waits until the step's conditions hold. Only picture conditions can change while waiting. */
    private suspend fun waitConditions(step: Step, where: String, prevOk: Boolean, state: RunState): Outcome {
        val rolls = step.conditions.map { if (it.kind == ConditionKind.RANDOM) host.roll() else 0 }
        val hasImage = step.conditions.any { it.kind == ConditionKind.IMAGE }
        val started = host.now()
        var why = ""
        while (true) {
            var holds = 0
            val notes = ArrayList<String>()
            step.conditions.forEachIndexed { k, c ->
                val ok: Boolean
                val note: String
                when (c.kind) {
                    ConditionKind.IMAGE -> host.imageHolds(c).let { ok = it.holds; note = it.note }
                    ConditionKind.ACTION_RESULT -> {
                        ok = prevOk == c.resultOk
                        note = if (c.resultOk) "the step before did not work" else "the step before worked"
                    }
                    ConditionKind.RANDOM -> {
                        ok = rolls[k] < c.percent
                        note = "the ${c.percent}% chance did not come up"
                    }
                    ConditionKind.ONCE -> {
                        ok = (step.id + "#" + k) !in state.onceUsed
                        note = "it already ran once"
                    }
                }
                if (ok) holds++ else notes.add(note)
            }
            val pass = if (step.anyOf) holds > 0 else holds == step.conditions.size
            if (pass) {
                step.conditions.forEachIndexed { k, c -> if (c.kind == ConditionKind.ONCE) state.onceUsed.add(step.id + "#" + k) }
                return Outcome.Ok
            }
            why = notes.joinToString(" and ")
            if (!hasImage) return Outcome.Missing("Stopped: step $where did not run, because $why.")
            if (host.now() - started >= step.timeoutMs) break
            host.status("Step $where: waiting\n$why")
            host.pause(150)
        }
        return Outcome.Missing("Stopped: step $where waited ${step.timeoutMs / 1000} s, but $why.")
    }
}
