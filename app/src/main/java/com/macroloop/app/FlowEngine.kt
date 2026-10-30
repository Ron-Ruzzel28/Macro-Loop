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
 *  - a jump limit ends a loop that never ends;
 *  - a Run step link runs another step of the macro (with its own conditions and branches) and then the
 *    list carries on from the link. A step marked "only when linked" is skipped in the normal order.
 *    A link to a deleted step, or a link that leads back to itself, stops the macro with a clear message.
 *  - counters: Add / Reset steps change a named number, and a Counter condition looks at it. A counter that is
 *    "kept" is loaded from the phone at the start of a run and saved each time it changes;
 *  - watchers: while the macro waits or between steps, about once a second each watcher's conditions are looked
 *    at once, and when they hold its If true steps run (then the macro goes on where it was);
 *  - stuck rescue: when the screen has not changed for the set time, the rescue steps run (no steps = stop).
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

    /** A line for the run's log: what happened, or why a step did nothing. Repeats are folded by the host. */
    fun note(text: String) {}

    /** Nothing was done in this pass of the list: wait for the screen to change (a moment at most) before looking again. */
    suspend fun idle() { pause(20) }

    /** A new look at a step's conditions begins: forget the colours that "changes" conditions compare against. */
    fun resetBaselines() {}

    /** The engine gives the host something to call now and then while the host waits on its own (for a picture). */
    fun setWaitHook(hook: (suspend () -> Unit)?) {}

    /** Has the screen changed since the last time this was asked? Null when it cannot be told. */
    suspend fun screenChanged(): Boolean? = null

    /** A counter that is kept between runs. */
    fun counterLoad(macroId: String, name: String): Int = 0

    fun counterSave(macroId: String, name: String, value: Int) {}

    /** A number from 0 to 99, for Random True. */
    fun roll(): Int

    /** A wait time with a little human-like variation (or the same time if that is off). */
    fun humanWait(ms: Long): Long
}

class RunState {
    val onceUsed = HashSet<String>()
    var jumps = 0
    var byId: Map<String, Step> = emptyMap()   // every step of the macro, branches included, by id
    val active = ArrayList<String>()            // linked steps being run right now
    val counters = HashMap<String, Int>()
    var keep: Set<String> = emptySet()          // names of counters kept between runs
    var macro: Macro? = null
    var inGuard = false                         // a watcher or the stuck rescue is running right now
    var lastGuardAt = -1_000_000L
    val firedAt = HashMap<String, Long>()       // when each watcher last ran its steps
    var lastChangeAt = 0L                       // when the screen last changed
    var rescues = 0                             // stuck rescues in a row, with no change in between
    var linkTargets: Set<String> = emptySet()   // steps that some Run step link points to
    var scan = false                            // Scan mode: look once, skip what is not there, loop the list
    var acted = false                           // something was done in this pass of the list
}

/** Thrown from inside a watcher or rescue to end whatever is waiting and carry out a Stop or Go to. */
private class FlowSignal(val flow: Flow) : RuntimeException(null, null, false, false)

private class StepResult(val flow: Flow, val worked: Boolean)

class FlowEngine(private val host: StepHost, private val maxJumps: Int = 5000) {
    /** How many times the list went round in the last run. */
    var roundsDone = 0
        private set

    private companion object {
        const val MAX_LINK_DEPTH = 20
        const val GUARD_EVERY_MS = 1000L
        const val WATCH_COOLDOWN_MS = 1500L
        const val MAX_RESCUES = 5
    }


    /**
     * @param startList the list to run first. Null means the whole macro. A Go to link can jump to any
     *        step of the macro's main list; the run then carries on from there.
     * @return null when the run finished, otherwise the reason it stopped.
     */
    suspend fun run(macro: Macro, startList: List<Step>? = null, rounds: Int = macro.repeat): String? {
        val state = RunState()
        val root = macro.steps
        state.macro = macro
        state.scan = macro.scan
        state.byId = collect(root + macro.watchers + macro.stuck + macro.jobs.flatMap { it.steps })
        state.linkTargets = state.byId.values.filter { it.type == StepType.LINK && it.targetId.isNotEmpty() }.map { it.targetId }.toSet()
        state.keep = macro.counters.filter { it.keep }.map { it.name }.toSet()
        for (name in state.keep) state.counters[name] = host.counterLoad(macro.id, name)
        state.lastChangeAt = host.now()
        host.setWaitHook { guards(state, stuckToo = true) }
        try {
            return runRounds(macro, startList, rounds, state, root)
        } finally {
            host.setWaitHook(null)
        }
    }

    private suspend fun runRounds(macro: Macro, startList: List<Step>?, rounds: Int, state: RunState, root: List<Step>): String? {
        // Every trip through the list counts as one loop. A rounds number of 0 means until stopped.
        // In Scan mode a trip where nothing was done waits for the screen to change, then the list starts again.
        var done = 0
        var round = 0
        while (rounds <= 0 || done < rounds) {
            round++
            state.acted = false
            var cur: List<Step> = startList ?: root
            var i = 0
            var prevOk = true
            while (i < cur.size) {
                host.status("${macro.name}\nRound $round of $rounds, step ${i + 1} of ${cur.size}")
                val r = try {
                    guards(state, stuckToo = true)
                    runStep(macro.name, (i + 1).toString(), cur[i], prevOk, state)
                } catch (e: FlowSignal) {
                    StepResult(e.flow, false)
                }
                prevOk = r.worked
                when (val f = r.flow) {
                    Flow.Next -> i++
                    is Flow.Stop -> return f.message
                    is Flow.Jump -> {
                        state.acted = true
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
            if (state.scan && !state.acted) host.idle()
            done++
            roundsDone = done
        }
        return null
    }

    private fun collect(list: List<Step>): Map<String, Step> {
        val out = HashMap<String, Step>()
        fun walk(l: List<Step>) {
            for (s in l) {
                out[s.id] = s
                walk(s.onSuccess)
                walk(s.onFail)
            }
        }
        walk(list)
        return out
    }

    private fun gotoFlow(step: Step, where: String): Flow =
        if (step.targetId.isEmpty()) Flow.Stop("Stopped: the Go to step $where has no target. Edit it and choose one.") else Flow.Jump(step.targetId)

    private suspend fun runLink(macroName: String, where: String, step: Step, prevOk: Boolean, state: RunState): StepResult {
        if (step.targetId.isEmpty()) {
            return StepResult(Flow.Stop("Stopped: the link in step $where has no step chosen. Edit it and pick one."), false)
        }
        val target = state.byId[step.targetId]
            ?: return StepResult(Flow.Stop("Stopped: the link in step $where points to a step that was deleted. Edit it and pick another."), false)
        if (step.targetId in state.active || state.active.size >= MAX_LINK_DEPTH) {
            return StepResult(Flow.Stop("Stopped: the link in step $where leads back to a step that is already running (a link loop)."), false)
        }
        state.active.add(step.targetId)
        try {
            return runStep(macroName, "$where link", target, prevOk, state, viaLink = true)
        } finally {
            state.active.removeAt(state.active.lastIndex)
        }
    }

    /** Runs a whole job, then the caller carries on. A Stop or Go to inside the job ends or moves the whole macro. */
    private suspend fun runJobStep(macroName: String, where: String, step: Step, state: RunState): Outcome {
        val job = state.macro?.jobs?.firstOrNull { it.id == step.targetId }
            ?: return Outcome.Missing("step $where runs a job that was deleted or never chosen")
        val key = "job:" + job.id
        if (key in state.active || state.active.size >= MAX_LINK_DEPTH) {
            throw FlowSignal(Flow.Stop("Stopped: the job \"${job.name}\" runs itself (a loop). Change a Run job step inside it."))
        }
        state.active.add(key)
        try {
            val f = runList(macroName, job.steps, "job ${job.name}", state)
            if (f !is Flow.Next) throw FlowSignal(f)
        } finally {
            state.active.removeAt(state.active.lastIndex)
        }
        return Outcome.Ok
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

    private suspend fun runStep(
        macroName: String, where: String, step: Step, prevOk: Boolean, state: RunState, viaLink: Boolean = false,
    ): StepResult {
        if (!step.enabled) {
            host.note("step $where is turned off, so it was skipped")
            return StepResult(Flow.Next, prevOk)
        }
        // A step marked "only when linked" is skipped in the normal order only if a link really points to it.
        // With no link, skipping it would mean it never runs at all.
        if (step.linkedOnly && !viaLink && step.id in state.linkTargets) {
            host.note("step $where is set to \"run only when linked\", so it was skipped")
            return StepResult(Flow.Next, prevOk)
        }
        when (step.type) {
            StepType.LINK -> return runLink(macroName, where, step, prevOk, state)
            StepType.WAIT -> {
                waitWatching(host.humanWait(step.ms), state)
                state.acted = true
                return StepResult(Flow.Next, true)
            }
            StepType.COUNTER_ADD, StepType.COUNTER_RESET -> {
                val name = step.text
                if (name.isBlank()) return StepResult(Flow.Stop("Stopped: the counter step $where has no counter chosen. Edit it and pick one."), false)
                val v = if (step.type == StepType.COUNTER_RESET) 0 else (state.counters[name] ?: 0) + step.amount
                state.counters[name] = v
                state.acted = true
                if (name in state.keep) state.macro?.let { host.counterSave(it.id, name, v) }
                return StepResult(Flow.Next, true)
            }
            StepType.WATCH -> return StepResult(Flow.Next, prevOk)
            // Stop and Go to may have conditions: then they act only when the conditions hold.
            StepType.STOP_MACRO -> if (step.conditions.none { it.enabled }) return StepResult(Flow.Stop("Stopped by a Stop step ($where)."), true)
            StepType.GOTO -> if (step.conditions.none { it.enabled }) return StepResult(gotoFlow(step, where), true)
            else -> Unit
        }
        if (step.delayMs > 0) waitWatching(step.delayMs, state)

        // "Skip" conditions: when one holds, this step is left out and the macro goes on with the next one.
        val skippers = step.conditions.filter { it.enabled && it.skipWhen && (it.kind == ConditionKind.IMAGE || it.kind == ConditionKind.COLOR || it.kind == ConditionKind.TEXT) }
        if (skippers.isNotEmpty()) {
            host.resetBaselines()
            for (c in skippers) {
                if (host.imageHolds(c).holds) {
                    host.note("step $where skipped: ${c.label.ifBlank { "a skip condition" }} was true")
                    return StepResult(Flow.Next, prevOk)
                }
            }
        }

        var outcome: Outcome = Outcome.Ok
        if (step.conditions.isNotEmpty()) outcome = waitConditions(step, where, prevOk, state)
        if (outcome is Outcome.Ok && step.type == StepType.STOP_MACRO) return StepResult(Flow.Stop("Stopped by a Stop step ($where)."), true)
        if (outcome is Outcome.Ok && step.type == StepType.GOTO) return StepResult(gotoFlow(step, where), true)
        if (outcome is Outcome.Ok) {
            for (r in 1..step.repeatCount) {
                outcome = if (step.type == StepType.RUN_JOB) runJobStep(macroName, where, step, state) else host.perform(step, where)
                if (outcome !is Outcome.Ok) break
                state.acted = true
            }
        }

        return when (val o = outcome) {
            Outcome.Ok -> {
                val after = step.waitNextMs
                if (after > 0) waitWatching(host.humanWait(after), state)
                val flow = if (step.onSuccess.isNotEmpty()) runList(macroName, step.onSuccess, "$where on success", state) else Flow.Next
                StepResult(flow, true)
            }
            is Outcome.Fatal -> {
                host.note(o.message.removePrefix("Stopped: "))
                StepResult(Flow.Stop(o.message), false)
            }
            is Outcome.Missing -> {
                host.note("step $where skipped: " + o.message.removePrefix("Stopped: "))
                when {
                step.onFail.isNotEmpty() -> StepResult(runList(macroName, step.onFail, "$where on fail", state), false)
                step.skipIfMissing || state.scan -> StepResult(Flow.Next, false)
                else -> StepResult(Flow.Stop(o.message), false)
                }
            }
        }
    }

    /** Waits, in short pieces, so the watchers can look at the screen while it waits. Stuck is not checked: waiting is on purpose. */
    private suspend fun waitWatching(ms: Long, state: RunState) {
        if (state.macro?.watchers.isNullOrEmpty() || ms < 1200) {
            host.pause(ms)
            return
        }
        val end = host.now() + ms
        while (true) {
            val left = end - host.now()
            if (left <= 0) break
            host.pause(minOf(left, 700))
            guards(state, stuckToo = false)
        }
        state.lastChangeAt = host.now()
    }

    /**
     * Looks at the watchers (and the stuck timer) if a second has passed since the last look.
     * A watcher whose conditions hold runs its If true steps. A Stop or Go to inside them ends the wait, via [FlowSignal].
     */
    private suspend fun guards(state: RunState, stuckToo: Boolean) {
        val m = state.macro ?: return
        if (state.inGuard) return
        val now = host.now()
        if (now - state.lastGuardAt < GUARD_EVERY_MS) return
        state.lastGuardAt = now
        state.inGuard = true
        try {
            for ((n, w) in m.watchers.withIndex()) {
                if (!w.enabled || w.type != StepType.WATCH) continue
                val last = state.firedAt[w.id]
                if (last != null && host.now() - last < WATCH_COOLDOWN_MS) continue
                val conds = w.conditions.filter { it.enabled }
                if (conds.isEmpty()) continue
                if (!conditionsHold(w, conds, state)) continue
                state.firedAt[w.id] = host.now()
                val f = runList(m.name, w.onSuccess, "watcher ${n + 1}", state)
                state.firedAt[w.id] = host.now()
                if (f !is Flow.Next) throw FlowSignal(f)
                state.lastChangeAt = host.now()
            }
            for (job in m.jobs) {
                val trigger = job.steps.firstOrNull { it.type == StepType.WATCH && it.enabled } ?: continue
                val conds = trigger.conditions.filter { it.enabled }
                if (conds.isEmpty()) continue
                val last = state.firedAt[job.id]
                if (last != null && host.now() - last < WATCH_COOLDOWN_MS) continue
                val key = "job:" + job.id
                if (key in state.active) continue
                if (!conditionsHold(trigger, conds, state)) continue
                state.firedAt[job.id] = host.now()
                state.active.add(key)
                val f = try { runList(m.name, job.steps, "job ${job.name}", state) } finally { state.active.removeAt(state.active.lastIndex) }
                state.firedAt[job.id] = host.now()
                if (f !is Flow.Next) throw FlowSignal(f)
                state.lastChangeAt = host.now()
            }
            if (stuckToo && m.stuckSec > 0) checkStuck(m, state)
        } finally {
            state.inGuard = false
        }
    }

    private suspend fun checkStuck(m: Macro, state: RunState) {
        val changed = host.screenChanged()
        if (changed == true) {
            state.lastChangeAt = host.now()
            state.rescues = 0
            return
        }
        if (changed == null) return
        if (host.now() - state.lastChangeAt < m.stuckSec * 1000L) return
        if (m.stuck.isEmpty()) throw FlowSignal(Flow.Stop("Stopped: the screen did not change for ${m.stuckSec} s (stuck)."))
        if (++state.rescues > MAX_RESCUES) throw FlowSignal(Flow.Stop("Stopped: still stuck after $MAX_RESCUES rescue tries."))
        val f = runList(m.name, m.stuck, "stuck rescue", state)
        state.lastChangeAt = host.now()
        if (f !is Flow.Next) throw FlowSignal(f)
    }

    /** One look at a list of conditions, with no waiting. */
    private suspend fun conditionsHold(step: Step, conds: List<Condition>, state: RunState): Boolean {
        host.resetBaselines()
        var holds = 0
        for ((k, c) in conds.withIndex()) {
            val ok = when (c.kind) {
                ConditionKind.IMAGE, ConditionKind.COLOR, ConditionKind.TEXT -> host.imageHolds(c).holds
                ConditionKind.COUNTER -> (state.counters[c.text] ?: 0).let { v -> if (c.mustAppear) v >= c.percent else v < c.percent }
                ConditionKind.RANDOM -> host.roll() < c.percent
                ConditionKind.ONCE -> (step.id + "#" + k) !in state.onceUsed
                ConditionKind.ACTION_RESULT -> false
            }
            if (ok) holds++
        }
        val pass = if (step.anyOf) holds > 0 else holds == conds.size
        if (pass) conds.forEachIndexed { k, c -> if (c.kind == ConditionKind.ONCE) state.onceUsed.add(step.id + "#" + k) }
        return pass
    }

    /** Waits until the step's conditions hold. Only picture conditions can change while waiting. */
    private suspend fun waitConditions(step: Step, where: String, prevOk: Boolean, state: RunState): Outcome {
        val conds = step.conditions.filter { it.enabled && !it.skipWhen }
        if (conds.isEmpty()) return Outcome.Ok
        val rolls = conds.map { if (it.kind == ConditionKind.RANDOM) host.roll() else 0 }
        val hasImage = conds.any { it.kind == ConditionKind.IMAGE || it.kind == ConditionKind.COLOR || it.kind == ConditionKind.TEXT }
        host.resetBaselines()
        val started = host.now()
        var why = ""
        while (true) {
            var holds = 0
            val notes = ArrayList<String>()
            conds.forEachIndexed { k, c ->
                val ok: Boolean
                val note: String
                when (c.kind) {
                    ConditionKind.IMAGE, ConditionKind.COLOR, ConditionKind.TEXT -> host.imageHolds(c).let { ok = it.holds; note = it.note }
                    ConditionKind.ACTION_RESULT -> {
                        ok = prevOk == c.resultOk
                        note = if (c.resultOk) "the step before did not work" else "the step before worked"
                    }
                    ConditionKind.RANDOM -> {
                        ok = rolls[k] < c.percent
                        note = "the ${c.percent}% chance did not come up"
                    }
                    ConditionKind.COUNTER -> {
                        val v = state.counters[c.text] ?: 0
                        ok = if (c.mustAppear) v >= c.percent else v < c.percent
                        note = "counter ${c.text} is $v, " + (if (c.mustAppear) "it needs to be at least ${c.percent}" else "it needs to be below ${c.percent}")
                    }
                    ConditionKind.ONCE -> {
                        ok = (step.id + "#" + k) !in state.onceUsed
                        note = "it already ran once"
                    }
                }
                if (ok) holds++ else notes.add(note)
            }
            val pass = if (step.anyOf) holds > 0 else holds == conds.size
            if (pass) {
                conds.forEachIndexed { k, c -> if (c.kind == ConditionKind.ONCE) state.onceUsed.add(step.id + "#" + k) }
                return Outcome.Ok
            }
            why = notes.joinToString(" and ")
            if (!hasImage || state.scan) return Outcome.Missing("Stopped: step $where did not run, because $why.")
            if (host.now() - started >= step.timeoutMs) break
            host.status("Step $where: waiting\n$why")
            host.pause(150)
            guards(state, stuckToo = true)
        }
        return Outcome.Missing("Stopped: step $where waited ${step.timeoutMs / 1000} s, but $why.")
    }
}
