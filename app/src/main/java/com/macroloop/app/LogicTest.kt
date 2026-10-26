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
    }

    private fun step(name: String, type: StepType = StepType.CLICK, f: (Step) -> Step = { it }) =
        f(Step(type, id = name, name = name, waitNextMs = 0, timeoutMs = 1000))

    private fun macro(vararg steps: Step) = Macro("m", "test", steps.toList())

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

        check("A step marked 'only when linked' is skipped in the normal order", listOf("a", "b")) {
            macro(step("a"), step("x") { it.copy(linkedOnly = true) }, step("b"))
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
                step("holder") { it.copy(linkedOnly = true, onSuccess = listOf(step("n"))) },
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

        val passed = cases.count { it.ok }
        val lines = ArrayList<String>()
        lines += "Run rules: $passed of ${cases.size} checks passed"
        for (c in cases) lines += "${if (c.ok) "PASS" else "FAIL"}  ${c.name}" + if (c.ok) "" else "\n      got: ${c.detail}"
        lines
    }
}
