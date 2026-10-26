package com.macroloop.app

import org.json.JSONArray
import org.json.JSONObject

enum class StepType { CLICK, SWIPE, WAIT, CLICK_IMAGE, STOP_MACRO }

/**
 * Something that must (or must not) be on screen before a step runs.
 * It is a picture looked for near where it was captured.
 */
data class Condition(
    val templateId: String = "",
    val label: String = "",
    val mustAppear: Boolean = true,     // false = the picture must NOT be on screen
    val threshold: Int = 90,
    val wholeScreen: Boolean = false,
    val boxX: Int = 0,
    val boxY: Int = 0,
    val boxW: Int = 0,
    val boxH: Int = 0,
    val capW: Int = 0,
    val capH: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("templateId", templateId).put("label", label)
        .put("mustAppear", mustAppear).put("threshold", threshold)
        .put("wholeScreen", wholeScreen)
        .put("boxX", boxX).put("boxY", boxY).put("boxW", boxW).put("boxH", boxH)
        .put("capW", capW).put("capH", capH)

    companion object {
        fun fromJson(o: JSONObject) = Condition(
            templateId = o.optString("templateId", ""),
            label = o.optString("label", ""),
            mustAppear = o.optBoolean("mustAppear", true),
            threshold = o.optInt("threshold", 90),
            wholeScreen = o.optBoolean("wholeScreen", false),
            boxX = o.optInt("boxX"), boxY = o.optInt("boxY"),
            boxW = o.optInt("boxW"), boxH = o.optInt("boxH"),
            capW = o.optInt("capW"), capH = o.optInt("capH"),
        )
    }
}

data class Step(
    val type: StepType,
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
    // action settings (all step types except WAIT)
    val holdMs: Long = 0,               // 0 = quick tap
    val repeatCount: Int = 1,
    val delayMs: Long = 0,              // wait before this step
    val waitNextMs: Long = 100,         // wait after this step
    val randomizePx: Int = 0,           // random offset on every tap
    // conditions: the step runs only when these hold
    val conditions: List<Condition> = emptyList(),
    val anyOf: Boolean = false,         // false = all must hold, true = any one is enough
    // branches: steps that run after this one
    val onSuccess: List<Step> = emptyList(), // when the step worked
    val onFail: List<Step> = emptyList(),    // when its picture or conditions never showed up
    val enabled: Boolean = true,             // false = the step is skipped
) {
    /** One readable line, in plain words. */
    fun describe(): String = when (type) {
        StepType.CLICK -> "Tap the spot ($x, $y)"
        StepType.SWIPE -> "Swipe from ($x, $y) to ($x2, $y2)"
        StepType.WAIT -> "Wait ${ms / 1000.0} s"
        StepType.CLICK_IMAGE -> "Tap the picture \"$label\" (look for up to ${timeoutMs / 1000} s)"
        StepType.STOP_MACRO -> "Stop the macro"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
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
        .put("repeatCount", repeatCount)
        .put("delayMs", delayMs)
        .put("waitNextMs", waitNextMs)
        .put("randomizePx", randomizePx)
        .put("anyOf", anyOf)
        .put("enabled", enabled)
        .put("onSuccess", JSONArray().also { a -> onSuccess.forEach { a.put(it.toJson()) } })
        .put("onFail", JSONArray().also { a -> onFail.forEach { a.put(it.toJson()) } })
        .put("conditions", JSONArray().also { a -> conditions.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            type = StepType.valueOf(o.getString("type")),
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
            repeatCount = o.optInt("repeatCount", 1).coerceAtLeast(1),
            delayMs = o.optLong("delayMs", 0),
            waitNextMs = o.optLong("waitNextMs", 100),
            randomizePx = o.optInt("randomizePx", 0),
            anyOf = o.optBoolean("anyOf", false),
            enabled = o.optBoolean("enabled", true),
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
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            return Macro(
                id = o.getString("id"),
                name = o.getString("name"),
                repeat = o.optInt("repeat", 1).coerceAtLeast(1),
                updatedAt = o.optLong("updatedAt"),
                steps = List(arr.length()) { Step.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
