package com.macroloop.app

import org.json.JSONArray
import org.json.JSONObject

enum class StepType {
    CLICK, SWIPE, WAIT, CLICK_IMAGE, STOP_MACRO, GOTO, LINK,
    PRESS_BACK, PRESS_HOME, OPEN_RECENT, OPEN_NOTIFICATION, OPEN_QUICKSETTING, LOCK_SCREEN,
    TOAST, OPEN_APP,
}

enum class ConditionKind { IMAGE, ACTION_RESULT, RANDOM, ONCE }

/**
 * Something that has to be true before a step runs.
 *  IMAGE         a picture is on screen (or, with mustAppear = false, is not)
 *  ACTION_RESULT the step before it worked (resultOk = true) or did not (false)
 *  RANDOM        true by chance, percent of the time
 *  ONCE          true only the first time during one run
 */
data class Condition(
    val kind: ConditionKind = ConditionKind.IMAGE,
    val templateId: String = "",
    val label: String = "",
    val mustAppear: Boolean = true,     // IMAGE: false = the picture must NOT be on screen
    val threshold: Int = 90,
    val wholeScreen: Boolean = false,
    val boxX: Int = 0,
    val boxY: Int = 0,
    val boxW: Int = 0,
    val boxH: Int = 0,
    val capW: Int = 0,
    val capH: Int = 0,
    val resultOk: Boolean = true,       // ACTION_RESULT
    val percent: Int = 50,              // RANDOM
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("templateId", templateId).put("label", label)
        .put("mustAppear", mustAppear).put("threshold", threshold)
        .put("wholeScreen", wholeScreen)
        .put("boxX", boxX).put("boxY", boxY).put("boxW", boxW).put("boxH", boxH)
        .put("capW", capW).put("capH", capH)
        .put("resultOk", resultOk).put("percent", percent)

    companion object {
        fun fromJson(o: JSONObject) = Condition(
            kind = runCatching { ConditionKind.valueOf(o.optString("kind", "IMAGE")) }.getOrDefault(ConditionKind.IMAGE),
            templateId = o.optString("templateId", ""),
            label = o.optString("label", ""),
            mustAppear = o.optBoolean("mustAppear", true),
            threshold = o.optInt("threshold", 90),
            wholeScreen = o.optBoolean("wholeScreen", false),
            boxX = o.optInt("boxX"), boxY = o.optInt("boxY"),
            boxW = o.optInt("boxW"), boxH = o.optInt("boxH"),
            capW = o.optInt("capW"), capH = o.optInt("capH"),
            resultOk = o.optBoolean("resultOk", true),
            percent = o.optInt("percent", 50),
        )
    }
}

data class Step(
    val type: StepType,
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",             // the user's own label, shown instead of the description
    val targetId: String = "",         // GOTO: the id of the step to jump to
    val text: String = "",             // TOAST: the message. OPEN_APP: the package name
    val x: Int = 0,
    val y: Int = 0,
    val ms: Long = 0,                   // WAIT length
    // swipe end point and length
    val x2: Int = 0,
    val y2: Int = 0,
    val swipeMs: Long = 300,
    // picture steps
    val templateId: String = "",
    val label: String = "",
    val threshold: Int = 90,            // percent, how exact the match must be
    val wholeScreen: Boolean = false,   // false = look near where the picture was picked
    val timeoutMs: Long = 10_000,
    val skipIfMissing: Boolean = false, // false = stop the macro when not found
    val flexScale: Boolean = false,     // also try slightly bigger and smaller copies of the picture
    val matchAll: Boolean = false,      // tap every match, top to bottom, not only the best
    val boxX: Int = 0,                  // where the picture box is, in screen pixels
    val boxY: Int = 0,
    val boxW: Int = 0,
    val boxH: Int = 0,
    val capW: Int = 0,                  // screen size when the picture was captured
    val capH: Int = 0,
    // action settings
    val holdMs: Long = 0,               // 0 = quick tap, more = long click
    val clicks: Int = 1,                // click style: 1 single, 2 double, 3 triple
    val repeatCount: Int = 1,           // run the whole step this many times
    val delayMs: Long = 0,              // wait before this step
    val waitNextMs: Long = 100,         // wait after this step
    val randomizePx: Int = 0,           // random offset on every tap
    val offsetX: Int = 0,               // move the tap from the middle of what was found
    val offsetY: Int = 0,
    // conditions: the step runs only when these hold
    val conditions: List<Condition> = emptyList(),
    val anyOf: Boolean = false,         // false = all must hold, true = any one is enough
    // branches: steps that run after this one
    val onSuccess: List<Step> = emptyList(), // when the step worked
    val onFail: List<Step> = emptyList(),    // when its picture or conditions never showed up
    val enabled: Boolean = true,             // false = the step is skipped
    val linkedOnly: Boolean = false,         // true = skipped in the normal order, runs only when a link calls it
) {
    /** A short plain description, used when the step has no name. */
    fun describe(): String = when (type) {
        StepType.CLICK -> "Click ($x, $y)"
        StepType.SWIPE -> "Swipe ($x, $y) to ($x2, $y2)"
        StepType.WAIT -> "Wait ${ms / 1000.0} s"
        StepType.CLICK_IMAGE -> "Click image"
        StepType.STOP_MACRO -> "Stop macro"
        StepType.GOTO -> "Go to step"
        StepType.LINK -> "Run step"
        StepType.PRESS_BACK -> "Press Back"
        StepType.PRESS_HOME -> "Press Home"
        StepType.OPEN_RECENT -> "Open Recent"
        StepType.OPEN_NOTIFICATION -> "Open Notification"
        StepType.OPEN_QUICKSETTING -> "Open Quicksetting"
        StepType.LOCK_SCREEN -> "Lock Screen"
        StepType.TOAST -> "Toast Message"
        StepType.OPEN_APP -> "Open App"
    }

    /** True for steps that tap or swipe somewhere (they have click style, offset and delay). */
    val isGesture: Boolean get() = type == StepType.CLICK || type == StepType.SWIPE || type == StepType.CLICK_IMAGE

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("id", id)
        .put("name", name)
        .put("targetId", targetId)
        .put("text", text)
        .put("x", x).put("y", y).put("ms", ms)
        .put("x2", x2).put("y2", y2).put("swipeMs", swipeMs)
        .put("templateId", templateId)
        .put("label", label)
        .put("threshold", threshold)
        .put("wholeScreen", wholeScreen)
        .put("timeoutMs", timeoutMs)
        .put("skipIfMissing", skipIfMissing)
        .put("flexScale", flexScale)
        .put("matchAll", matchAll)
        .put("boxX", boxX).put("boxY", boxY).put("boxW", boxW).put("boxH", boxH)
        .put("capW", capW).put("capH", capH)
        .put("holdMs", holdMs)
        .put("clicks", clicks)
        .put("repeatCount", repeatCount)
        .put("delayMs", delayMs)
        .put("waitNextMs", waitNextMs)
        .put("randomizePx", randomizePx)
        .put("offsetX", offsetX).put("offsetY", offsetY)
        .put("anyOf", anyOf)
        .put("enabled", enabled)
        .put("linkedOnly", linkedOnly)
        .put("onSuccess", JSONArray().also { a -> onSuccess.forEach { a.put(it.toJson()) } })
        .put("onFail", JSONArray().also { a -> onFail.forEach { a.put(it.toJson()) } })
        .put("conditions", JSONArray().also { a -> conditions.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            type = StepType.valueOf(o.getString("type")),
            id = o.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
            name = o.optString("name", ""),
            targetId = o.optString("targetId", ""),
            text = o.optString("text", ""),
            x = o.optInt("x"),
            y = o.optInt("y"),
            ms = o.optLong("ms"),
            x2 = o.optInt("x2"),
            y2 = o.optInt("y2"),
            swipeMs = o.optLong("swipeMs", 300),
            templateId = o.optString("templateId", ""),
            label = o.optString("label", ""),
            threshold = o.optInt("threshold", 90),
            wholeScreen = o.optBoolean("wholeScreen", false),
            timeoutMs = o.optLong("timeoutMs", 10_000),
            skipIfMissing = o.optBoolean("skipIfMissing", false),
            flexScale = o.optBoolean("flexScale", false),
            matchAll = o.optBoolean("matchAll", false),
            boxX = o.optInt("boxX"), boxY = o.optInt("boxY"),
            boxW = o.optInt("boxW"), boxH = o.optInt("boxH"),
            capW = o.optInt("capW"), capH = o.optInt("capH"),
            holdMs = o.optLong("holdMs", 0),
            clicks = o.optInt("clicks", 1).coerceIn(1, 3),
            repeatCount = o.optInt("repeatCount", 1).coerceAtLeast(1),
            delayMs = o.optLong("delayMs", 0),
            waitNextMs = o.optLong("waitNextMs", 100),
            randomizePx = o.optInt("randomizePx", 0),
            offsetX = o.optInt("offsetX", 0),
            offsetY = o.optInt("offsetY", 0),
            anyOf = o.optBoolean("anyOf", false),
            enabled = o.optBoolean("enabled", true),
            linkedOnly = o.optBoolean("linkedOnly", false),
            onSuccess = o.optJSONArray("onSuccess")?.let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            onFail = o.optJSONArray("onFail")?.let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            conditions = o.optJSONArray("conditions")?.let { a ->
                List(a.length()) { Condition.fromJson(a.getJSONObject(it)) }
            } ?: emptyList(),
        )
    }
}

data class Macro(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val repeat: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val game: String = "",
    val version: String = "1.0.0",
) {
    fun summary(): String {
        val s = if (steps.size == 1) "1 step" else "${steps.size} steps"
        val r = if (repeat == 1) "runs once" else "repeats $repeat times"
        return "$s, $r"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("repeat", repeat)
        .put("updatedAt", updatedAt)
        .put("game", game)
        .put("version", version)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            return Macro(
                id = o.getString("id"),
                name = o.getString("name"),
                repeat = o.optInt("repeat", 1).coerceAtLeast(1),
                updatedAt = o.optLong("updatedAt"),
                game = o.optString("game", ""),
                version = o.optString("version", "1.0.0"),
                steps = List(arr.length()) { Step.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
