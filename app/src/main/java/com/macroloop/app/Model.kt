package com.macroloop.app

import org.json.JSONArray
import org.json.JSONObject

enum class StepType { CLICK, WAIT, CLICK_IMAGE }

data class Step(
    val type: StepType,
    val x: Int = 0,
    val y: Int = 0,
    val ms: Long = 0,
    // picture steps
    val templateId: String = "",
    val label: String = "",
    val threshold: Int = 90,            // percent, how exact the match must be
    val wholeScreen: Boolean = false,   // false = look near where the picture was picked
    val timeoutMs: Long = 10_000,
    val skipIfMissing: Boolean = false, // false = stop the macro when not found
    val boxX: Int = 0,                  // where the picture was picked, in screen pixels
    val boxY: Int = 0,
    val boxW: Int = 0,
    val boxH: Int = 0,
    val capW: Int = 0,                  // screen size when the picture was picked
    val capH: Int = 0,
) {
    /** One readable line, in plain words. */
    fun describe(): String = when (type) {
        StepType.CLICK -> "Tap the spot ($x, $y)"
        StepType.WAIT -> "Wait ${ms / 1000.0} s"
        StepType.CLICK_IMAGE -> "Tap the picture \"$label\" (wait up to ${timeoutMs / 1000} s)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("x", x)
        .put("y", y)
        .put("ms", ms)
        .put("templateId", templateId)
        .put("label", label)
        .put("threshold", threshold)
        .put("wholeScreen", wholeScreen)
        .put("timeoutMs", timeoutMs)
        .put("skipIfMissing", skipIfMissing)
        .put("boxX", boxX).put("boxY", boxY).put("boxW", boxW).put("boxH", boxH)
        .put("capW", capW).put("capH", capH)

    companion object {
        fun fromJson(o: JSONObject) = Step(
            type = StepType.valueOf(o.getString("type")),
            x = o.optInt("x"),
            y = o.optInt("y"),
            ms = o.optLong("ms"),
            templateId = o.optString("templateId", ""),
            label = o.optString("label", ""),
            threshold = o.optInt("threshold", 90),
            wholeScreen = o.optBoolean("wholeScreen", false),
            timeoutMs = o.optLong("timeoutMs", 10_000),
            skipIfMissing = o.optBoolean("skipIfMissing", false),
            boxX = o.optInt("boxX"), boxY = o.optInt("boxY"),
            boxW = o.optInt("boxW"), boxH = o.optInt("boxH"),
            capW = o.optInt("capW"), capH = o.optInt("capH"),
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
