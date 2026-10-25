package com.macroloop.app

import org.json.JSONArray
import org.json.JSONObject

enum class StepType { CLICK, WAIT }

data class Step(
    val type: StepType,
    val x: Int = 0,
    val y: Int = 0,
    val ms: Long = 0,
) {
    /** One readable line, in plain words. */
    fun describe(): String = when (type) {
        StepType.CLICK -> "Tap the spot ($x, $y)"
        StepType.WAIT -> "Wait ${ms / 1000.0} s"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("x", x)
        .put("y", y)
        .put("ms", ms)

    companion object {
        fun fromJson(o: JSONObject) = Step(
            type = StepType.valueOf(o.getString("type")),
            x = o.optInt("x"),
            y = o.optInt("y"),
            ms = o.optLong("ms"),
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
