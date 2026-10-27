package com.macroloop.app

import kotlinx.coroutines.runBlocking

/**
 * Checks the run rules (FlowEngine) with scripted steps and no taps: order, true and false branches,
 * skip, stop, Go to links, conditions, repeats and the test-from-here slices.
 */
object LogicTest {

    private class Host : StepHost {
        val ran = ArrayList<String>()
        val script = HashMap<String, Outcome>()       // what a step does, by its name
        var imageFlipAt = HashMap<String, Long>()     // a picture condition holds once the clock reaches this
        val images = HashMap<String, Boolean>()       // a picture condition's fixed answer, by its label
        var clock = 0L
        var dice = 0
        var screen: (() -> Boolean?)? = null          // answers "did the screen change", when a test needs it
        val loaded = HashMap<String, Int>()           // kept counters at the start of the run
        val saved = HashMap<String, Int>()            // kept counters as they were saved

        override suspend fun perform(step: Step, where: String): Outcome {
            ran.add(step.name.ifEmpty { step.describe() })
            return script[step.name] ?: Outcome.Ok
        }

        override suspend fun imageHolds(c: Condition): ImageCheck {
            val flip = imageFlipAt[c.label]
            val holds = if (flip != null) clock >= flip else images[c.label] ?: false
            return ImageCheck(holds, "\"${c.label}\" was not on screen")
        }

        override suspend fun pause(ms: Long) { clock += ms }
        override fun now() = clock
        override fun status(text: String) = Unit
        override fun roll() = dice
        override fun humanWait(ms: Long) = ms
        override suspend fun screenChanged(): Boolean? = screen?.invoke()
        override fun counterLoad(macroId: String, name: String) = loaded[name] ?: 0
        override fun counterSave(macroId: String, name: String, value: Int) { saved[name] = value }
    }

    private fun step(name: String, type: StepType = StepType.CLICK, f: (Step) -> Step = { it }) =
        f(Step(type, id = name, name = name, waitNextMs = 0, timeoutMs = 1000))

    private fun macro(vararg steps: Step) = Macro("m", "test", steps.toList(), scan = false)

    private class Case(val name: String, val ok: Boolean, val detail: String)

    fun run(): List<String> = runBlocking {
        val cases = ArrayList<Case>()

        suspend fun check(
            name: String,
            expectRan: List<String>,
            expectEnd: ((String?) -> Boolean)? = null,
            maxJumps: Int = 5000,
            rounds: Int? = null,
            start: ((Macro) -> List<Step>)? = null,
            setup: (Host) -> Unit = {},
            build: () -> Macro,
        ) {
            val h = Host()
            setup(h)
            val m = build()
            val end = FlowEngine(h, maxJumps).run(m, start?.invoke(m), rounds ?: m.repeat)
            val endOk = expectEnd?.invoke(end) ?: (end == null)
            cases += Case(name, h.ran == expectRan && endOk, "ran ${h.ran}, ended ${end ?: "normally"}")
        }

        check("Steps run in order", listOf("a", "b", "c")) { macro(step("a"), step("b"), step("c")) }

        check("A disabled step is skipped", listOf("a", "c")) {
            macro(step("a"), step("b") { it.copy(enabled = false) }, step("c"))
        }

        check("On success runs after a step that worked", listOf("a", "a1", "b")) {
            macro(step("a") { it.copy(onSuccess = listOf(step("a1"))) }, step("b"))
        }

        check("On success does NOT run when the step failed", listOf("a", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(step("a") { it.copy(onSuccess = listOf(step("a1")), skipIfMissing = true) }, step("b"))
        }

        check("On fail runs when the picture never shows up, then the list goes on", listOf("a", "f1", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(step("a") { it.copy(onFail = listOf(step("f1"))) }, step("b"))
        }

        check("Missing with nothing under On fail stops the macro", listOf("a"),
            expectEnd = { it != null && it.startsWith("Stopped") },
            setup = { it.script["a"] = Outcome.Missing("Stopped: not found") }) {
            macro(step("a"), step("b"))
        }

        check("Missing with Skip chosen goes on", listOf("a", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(step("a") { it.copy(skipIfMissing = true) }, step("b"))
        }

        check("Setup trouble stops, even with On fail steps", listOf("a"),
            expectEnd = { it == "Stopped: capture is off" },
            setup = { it.script["a"] = Outcome.Fatal("Stopped: capture is off") }) {
            macro(step("a") { it.copy(onFail = listOf(step("f1"))) }, step("b"))
        }

        check("A Stop step ends the macro", listOf("a"), expectEnd = { it != null && it.contains("Stop step") }) {
            macro(step("a"), step("s", StepType.STOP_MACRO), step("b"))
        }

        check("A Stop step inside a branch ends everything", listOf("a"), expectEnd = { it != null }) {
            macro(step("a") { it.copy(onSuccess = listOf(step("s", StepType.STOP_MACRO), step("a2"))) }, step("b"))
        }

        check("Go to jumps forward and the run carries on from there", listOf("x", "z")) {
            macro(step("x"), step("g", StepType.GOTO) { it.copy(targetId = "z") }, step("y"), step("z"))
        }

        check("Go to loops back, and the jump limit ends an endless loop", List(6) { "a" },
            expectEnd = { it != null && it.contains("too many jumps") }, maxJumps = 5) {
            macro(step("a"), step("g", StepType.GOTO) { it.copy(targetId = "a") })
        }

        check("Go to a deleted step stops with a clear message", listOf("a"),
            expectEnd = { it != null && it.contains("no longer exists") }) {
            macro(step("a"), step("g", StepType.GOTO) { it.copy(targetId = "gone") })
        }

        check("Go to with no target stops", emptyList(), expectEnd = { it != null && it.contains("no target") }) {
            macro(step("g", StepType.GOTO))
        }

        check("Go to inside an If false branch jumps out of it", listOf("a", "z"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(
                step("a") { it.copy(onFail = listOf(step("g", StepType.GOTO) { g -> g.copy(targetId = "z") }, step("never"))) },
                step("skipped"),
                step("z"),
            )
        }

        check("Action Result: a step that worked lets 'did the step before work' pass", listOf("a", "b")) {
            macro(
                step("a"),
                step("b") { it.copy(conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = true))) },
                step("c") { it.copy(skipIfMissing = true, conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = false))) },
            )
        }

        check("Action Result: after a failed step only the 'failed' one runs", listOf("a", "c"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(
                step("a") { it.copy(skipIfMissing = true) },
                step("b") { it.copy(skipIfMissing = true, conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = true))) },
                step("c") { it.copy(conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = false))) },
            )
        }

        check("A condition that is turned off is skipped, so the step runs", listOf("a", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(
                step("a") { it.copy(skipIfMissing = true) },
                step("b") { it.copy(conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = true, enabled = false))) },
            )
        }

        check("Once True runs once, even over three rounds", listOf("o", "a", "a", "a"), rounds = 3) {
            macro(
                step("o") { it.copy(skipIfMissing = true, conditions = listOf(Condition(kind = ConditionKind.ONCE))) },
                step("a"),
            )
        }

        check("Random True runs when the dice are under the chance", listOf("r"), setup = { it.dice = 10 }) {
            macro(step("r") { it.copy(conditions = listOf(Condition(kind = ConditionKind.RANDOM, percent = 50))) })
        }

        check("Random True is skipped when the dice are over the chance", emptyList(), setup = { it.dice = 80 }) {
            macro(step("r") { it.copy(skipIfMissing = true, conditions = listOf(Condition(kind = ConditionKind.RANDOM, percent = 50))) })
        }

        val two = listOf(Condition(label = "p"), Condition(label = "q"))
        check("All true needs every picture", emptyList(),
            setup = { it.images["p"] = true; it.images["q"] = false }) {
            macro(step("s") { it.copy(skipIfMissing = true, conditions = two) })
        }

        check("One true needs just one picture", listOf("s"),
            setup = { it.images["p"] = true; it.images["q"] = false }) {
            macro(step("s") { it.copy(anyOf = true, conditions = two) })
        }

        check("A picture condition waits until the picture shows up", listOf("s"),
            setup = { it.imageFlipAt["p"] = 600L }) {
            macro(step("s") { it.copy(conditions = listOf(Condition(label = "p"))) })
        }

        check("A picture condition that never shows up runs On fail", listOf("f1", "b"),
            setup = { it.images["p"] = false }) {
            macro(step("s") { it.copy(conditions = listOf(Condition(label = "p")), onFail = listOf(step("f1"))) }, step("b"))
        }

        check("A step can be repeated", listOf("a", "a", "a")) {
            macro(step("a") { it.copy(repeatCount = 3) })
        }

        check("The list runs once for each round", listOf("a", "b", "a", "b"), rounds = 2) {
            macro(step("a"), step("b"))
        }

        check("Test this step runs only that step, without its branches", listOf("b"),
            start = { m -> listOf(m.steps[1].copy(onSuccess = emptyList(), onFail = emptyList())) }, rounds = 1) {
            macro(step("a"), step("b") { it.copy(onSuccess = listOf(step("b1"))) }, step("c"))
        }

        check("Test from this step runs it and everything after it", listOf("b", "b1", "c"),
            start = { m -> m.steps.drop(1) }, rounds = 1) {
            macro(step("a"), step("b") { it.copy(onSuccess = listOf(step("b1"))) }, step("c"))
        }

        check("A step that failed hands 'failed' to the next step", listOf("a", "f", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(
                step("a") { it.copy(onFail = listOf(step("f"))) },
                step("b") { it.copy(conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = false))) },
            )
        }

        fun link(name: String, to: String) = step(name, StepType.LINK) { it.copy(targetId = to) }

        check("A link runs the linked step and the list carries on", listOf("a", "x", "b")) {
            macro(step("a") { it.copy(onSuccess = listOf(link("l", "x"))) }, step("b"), step("x") { it.copy(linkedOnly = true) })
        }

        check("A step marked 'only when linked' is skipped in the normal order when a link points to it", listOf("a", "b")) {
            macro(step("a") { it.copy(onFail = listOf(link("l", "x"))) }, step("x") { it.copy(linkedOnly = true) }, step("b"))
        }

        check("A link as the fall back: If false runs the linked step, then the list goes on", listOf("a", "f", "b"),
            setup = { it.script["a"] = Outcome.Missing("not found") }) {
            macro(step("a") { it.copy(onFail = listOf(link("l", "f"))) }, step("b"), step("f") { it.copy(linkedOnly = true) })
        }

        check("A linked step brings its own If true steps", listOf("a", "x", "x1")) {
            macro(
                step("a") { it.copy(onSuccess = listOf(link("l", "x"))) },
                step("x") { it.copy(linkedOnly = true, onSuccess = listOf(step("x1"))) },
            )
        }

        check("A link to a step inside a branch works too", listOf("a", "n")) {
            macro(
                step("a") { it.copy(onSuccess = listOf(link("l", "n"))) },
                step("holder") { it.copy(enabled = false, onSuccess = listOf(step("n"))) },
            )
        }

        check("A link to a deleted step stops with a clear message", listOf("a"),
            expectEnd = { it != null && it.contains("deleted") }) {
            macro(step("a") { it.copy(onSuccess = listOf(link("l", "gone"))) }, step("b"))
        }

        check("A link with no step chosen stops", emptyList(), expectEnd = { it != null && it.contains("no step chosen") }) {
            macro(link("l", ""))
        }

        check("A link that leads back to itself is caught", listOf("x"),
            expectEnd = { it != null && it.contains("link loop") }) {
            macro(link("start", "x"), step("x") { it.copy(linkedOnly = true, onSuccess = listOf(link("again", "x"))) })
        }

        check("A linked step that cannot run (and has no If false) stops the macro", listOf("x"),
            expectEnd = { it != null && it.startsWith("Stopped") },
            setup = { it.script["x"] = Outcome.Missing("Stopped: not found") }) {
            macro(link("l", "x"), step("b"), step("x") { it.copy(linkedOnly = true) })
        }

        check("A linked step with its own If false runs it, and the list goes on", listOf("x", "xf", "b"),
            setup = { it.script["x"] = Outcome.Missing("not found") }) {
            macro(link("l", "x"), step("b"), step("x") { it.copy(linkedOnly = true, onFail = listOf(step("xf"))) })
        }

        check("A Stop inside a linked step ends the macro", listOf("x"),
            expectEnd = { it != null }) {
            macro(link("l", "x"), step("b"), step("x") { it.copy(linkedOnly = true, onSuccess = listOf(step("s", StepType.STOP_MACRO))) })
        }

        check("A linked step that is turned off is skipped", listOf("b")) {
            macro(link("l", "x"), step("b"), step("x") { it.copy(linkedOnly = true, enabled = false) })
        }

        check("After a link that failed, the next step sees 'failed'", listOf("x", "xf", "c"),
            setup = { it.script["x"] = Outcome.Missing("not found") }) {
            macro(
                link("l", "x"),
                step("c") { it.copy(conditions = listOf(Condition(kind = ConditionKind.ACTION_RESULT, resultOk = false))) },
                step("x") { it.copy(linkedOnly = true, onFail = listOf(step("xf"))) },
            )
        }

        // ---- counters, watchers, stuck rescue
        suspend fun custom(name: String, setup: (Host) -> Unit = {}, m: Macro, ok: (Host, String?) -> Boolean) {
            val h = Host()
            setup(h)
            val end = FlowEngine(h).run(m, null, 1)
            cases += Case(name, ok(h, end), "ran ${h.ran}, ended ${end ?: "normally"}, saved ${h.saved}")
        }

        fun add(name: String, counter: String, amount: Int = 1) =
            step(name, StepType.COUNTER_ADD) { it.copy(text = counter, amount = amount) }

        fun atLeast(counter: String, n: Int, yes: Boolean = true) =
            Condition(kind = ConditionKind.COUNTER, text = counter, percent = n, mustAppear = yes)

        custom("A counter counts up, and 'at least' lets a step run", m = macro(add("a1", "n"), add("a2", "n"), add("a3", "n"),
            step("done") { it.copy(conditions = listOf(atLeast("n", 3))) })) { h, e -> h.ran == listOf("done") && e == null }

        custom("A counter below its number stops the step with a clear reason", m = macro(add("a1", "n"), add("a2", "n"),
            step("done") { it.copy(conditions = listOf(atLeast("n", 3))) })) { h, e -> h.ran.isEmpty() && e != null && e.contains("counter n is 2") }

        custom("'Below' is true while the counter is smaller", m = macro(add("a1", "n"),
            step("early") { it.copy(conditions = listOf(atLeast("n", 3, yes = false))) })) { h, e -> h.ran == listOf("early") && e == null }

        custom("Reset sets a counter back to 0", m = macro(add("a1", "n", 5), step("r", StepType.COUNTER_RESET) { it.copy(text = "n") },
            step("done") { it.copy(conditions = listOf(atLeast("n", 1))) })) { h, e -> h.ran.isEmpty() && e != null }

        custom("A counter step with no counter chosen stops", m = macro(step("c", StepType.COUNTER_ADD))) { _, e -> e != null && e.contains("no counter chosen") }

        custom("A kept counter starts from the saved number and is saved again",
            setup = { it.loaded["n"] = 5 },
            m = macro(add("a1", "n", 2), step("done") { it.copy(conditions = listOf(atLeast("n", 7))) }).copy(counters = listOf(CounterDef("n", keep = true)))) { h, e ->
            h.ran == listOf("done") && e == null && h.saved["n"] == 7
        }

        custom("A counter that is not kept starts at 0 and is not saved",
            setup = { it.loaded["n"] = 5 },
            m = macro(add("a1", "n"), step("done") { it.copy(conditions = listOf(atLeast("n", 2))) })) { h, e -> h.ran.isEmpty() && e != null && h.saved.isEmpty() }

        val popup = Condition(label = "popup")
        val closeIt = step("w1", StepType.WATCH) { it.copy(conditions = listOf(popup), onSuccess = listOf(step("close"))) }

        custom("A watcher closes a popup while a step waits, and the step then goes on",
            setup = { it.images["popup"] = true; it.imageFlipAt["p"] = 4000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 10_000) }).copy(watchers = listOf(closeIt))) { h, e ->
            h.ran.count { it == "close" } >= 1 && h.ran.last() == "a" && e == null
        }

        custom("A watcher that does not see its picture does nothing",
            setup = { it.images["popup"] = false },
            m = macro(step("a"), step("b")).copy(watchers = listOf(closeIt))) { h, e -> h.ran == listOf("a", "b") && e == null }

        custom("A turned-off watcher does nothing",
            setup = { it.images["popup"] = true },
            m = macro(step("a"), step("w", StepType.WAIT) { it.copy(ms = 3000) }, step("b")).copy(watchers = listOf(closeIt.copy(enabled = false)))) { h, e ->
            h.ran == listOf("a", "b") && e == null
        }

        custom("A watcher also looks during a long Wait step",
            setup = { it.images["popup"] = true },
            m = macro(step("w", StepType.WAIT) { it.copy(ms = 5000) }, step("b")).copy(watchers = listOf(closeIt))) { h, e ->
            h.ran.count { it == "close" } >= 2 && h.ran.last() == "b" && e == null
        }

        custom("A Stop inside a watcher ends the macro",
            setup = { it.images["popup"] = true; it.imageFlipAt["p"] = 9000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 20_000) })
                .copy(watchers = listOf(closeIt.copy(onSuccess = listOf(step("s", StepType.STOP_MACRO)))))) { h, e ->
            h.ran.isEmpty() && e != null && e.contains("Stop step")
        }

        custom("A Go to inside a watcher jumps the main list",
            setup = { it.images["popup"] = true; it.imageFlipAt["p"] = 99_000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 20_000, skipIfMissing = true) }, step("skipped"), step("end"))
                .copy(watchers = listOf(closeIt.copy(onSuccess = listOf(step("g", StepType.GOTO) { it.copy(targetId = "end") }))))) { h, e ->
            h.ran == listOf("end") && e == null
        }

        custom("A watcher with a counter condition fires when the counter is high enough",
            m = macro(add("a1", "n", 3), step("w", StepType.WAIT) { it.copy(ms = 2500) }, step("b"))
                .copy(watchers = listOf(step("w1", StepType.WATCH) { it.copy(conditions = listOf(atLeast("n", 3)), onSuccess = listOf(step("reward"))) }))) { h, e ->
            h.ran.contains("reward") && h.ran.last() == "b" && e == null
        }

        val never = Condition(label = "never")
        custom("A frozen screen with no rescue steps stops the macro",
            setup = { it.screen = { false } },
            m = macro(step("a") { it.copy(conditions = listOf(never), timeoutMs = 60_000) }).copy(stuckSec = 10)) { _, e ->
            e != null && e.contains("did not change for 10 s")
        }

        custom("A frozen screen runs the rescue steps, then stops after too many tries",
            setup = { it.screen = { false } },
            m = macro(step("a") { it.copy(conditions = listOf(never), timeoutMs = 600_000) }).copy(stuckSec = 10, stuck = listOf(step("rescue")))) { h, e ->
            h.ran.count { it == "rescue" } == 5 && e != null && e.contains("still stuck")
        }

        custom("A screen that keeps changing is never stuck",
            setup = { it.screen = { true }; it.imageFlipAt["p"] = 30_000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 60_000) }).copy(stuckSec = 10, stuck = listOf(step("rescue")))) { h, e ->
            h.ran == listOf("a") && e == null
        }

        custom("A screen that cannot be read is not called stuck",
            setup = { it.screen = { null }; it.imageFlipAt["p"] = 30_000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 60_000) }).copy(stuckSec = 10)) { h, e ->
            h.ran == listOf("a") && e == null
        }

        custom("Stuck check off (0) never rescues",
            setup = { it.screen = { false }; it.imageFlipAt["p"] = 30_000 },
            m = macro(step("a") { it.copy(conditions = listOf(Condition(label = "p")), timeoutMs = 60_000) }).copy(stuck = listOf(step("rescue")))) { h, e ->
            h.ran == listOf("a") && e == null
        }

        // ---- Scan mode: look once, skip what is not there, loop until something happens
        fun scanned(vararg steps: Step) = Macro("m", "test", steps.toList(), scan = true)
        val pic = Condition(label = "p")

        suspend fun scanCheck(name: String, expectRan: List<String>, setup: (Host) -> Unit = {}, rounds: Int = 1,
                              start: ((Macro) -> List<Step>)? = null, m: Macro, endOk: (String?) -> Boolean = { it == null }) {
            val h = Host()
            setup(h)
            val end = FlowEngine(h).run(m, start?.invoke(m), rounds)
            cases += Case(name, h.ran == expectRan && endOk(end), "ran ${h.ran}, ended ${end ?: "normally"}, clock ${h.clock}")
        }

        scanCheck("Scan: a step whose picture is not there is skipped at once, the next one runs", listOf("b"),
            setup = { it.images["p"] = false }, rounds = 1,
            m = scanned(step("a") { it.copy(conditions = listOf(pic)) }, step("b")))

        scanCheck("Scan: it loops the list until the picture shows up, then does the step", listOf("a"),
            setup = { it.imageFlipAt["p"] = 600 },
            m = scanned(step("a") { it.copy(conditions = listOf(pic)) }))

        scanCheck("Scan: a picture that is there is done at once, without waiting", listOf("a", "b"),
            setup = { it.images["p"] = true },
            m = scanned(step("a") { it.copy(conditions = listOf(pic)) }, step("b")))

        scanCheck("Scan: If false steps run when the picture is not there", listOf("f"),
            setup = { it.images["p"] = false },
            m = scanned(step("a") { it.copy(conditions = listOf(pic), onFail = listOf(step("f"))) }))

        scanCheck("Scan: a passing round counts only when something was done (repeat 2)", listOf("a", "b", "a", "b"), rounds = 2,
            m = scanned(step("a"), step("b")))

        scanCheck("Scan: a test from a step looks again until its picture shows up, like a real run", listOf("a"),
            setup = { it.imageFlipAt["p"] = 600 }, start = { m -> listOf(m.steps[0]) },
            m = scanned(step("a") { it.copy(conditions = listOf(pic)) }))

        scanCheck("Scan: repeat 3 goes round three times that did something", listOf("a", "a", "a"), rounds = 3,
            m = scanned(step("a")))

        scanCheck("Scan: Stop ends it even while nothing is on screen", listOf("s1"),
            setup = { it.images["p"] = true },
            endOk = { it != null && it.contains("Stop step") },
            m = scanned(step("a") { it.copy(conditions = listOf(pic), onSuccess = emptyList()) }.copy(conditions = listOf(Condition(label = "never"))), step("s1"), step("s", StepType.STOP_MACRO)))

        scanCheck("Scan: a counter step counts as doing something", listOf(),
            m = scanned(step("c", StepType.COUNTER_ADD) { it.copy(text = "n") }))

        check("A step marked 'only when linked' that nothing links to still runs in order", listOf("a", "x", "b")) {
            macro(step("a"), step("x") { it.copy(linkedOnly = true) }, step("b"))
        }

        val passed = cases.count { it.ok }
        val lines = ArrayList<String>()
        lines += "Run rules: $passed of ${cases.size} checks passed"
        for (c in cases) lines += "${if (c.ok) "PASS" else "FAIL"}  ${c.name}" + if (c.ok) "" else "\n      got: ${c.detail}"
        lines
    }
}
