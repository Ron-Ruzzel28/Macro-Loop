package com.macroloop.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Stores macros in one JSON file. Same function names the Room version will have,
 * so screens do not change when the real database replaces this.
 */
object MacroRepository {
    val macros = mutableStateListOf<Macro>()
    private lateinit var file: File
    private var appContext: Context? = null

    fun init(context: Context) {
        if (::file.isInitialized) return
        appContext = context.applicationContext
        file = File(context.filesDir, "macros.json")
        load()
        // One time: macros made before looping was the default had Repeat 1 and would end after one trip. They now loop until stopped.
        val marker = File(context.filesDir, "migrated_loop_v1")
        if (!marker.exists()) {
            var changed = false
            for (i in macros.indices) if (macros[i].repeat == 1) { macros[i] = macros[i].copy(repeat = 0); changed = true }
            if (changed) persist()
            runCatching { marker.writeText("1") }
        }
    }

    fun get(id: String): Macro? = macros.firstOrNull { it.id == id }

    fun create(name: String, landscape: Boolean = false): Macro {
        val m = Macro(id = UUID.randomUUID().toString(), name = name.trim().ifEmpty { "New macro" }, landscape = landscape, repeat = 0)
        macros.add(0, m)
        persist()
        return m
    }

    /** A ready-made macro with no pictures and no taps: it only shows messages and waits, so it is safe to play anywhere. It shows how steps run in order. */
    fun createSample(): Macro {
        val m = Macro(
            id = UUID.randomUUID().toString(),
            name = "Demo: how steps work",
            repeat = 1,
            steps = listOf(
                Step(StepType.TOAST, name = "Say hello", text = "Step 1: TapLens shows this message."),
                Step(StepType.WAIT, name = "Wait 2 seconds", ms = 2000),
                Step(StepType.TOAST, name = "Say it came later", text = "Step 3: this came 2 seconds later."),
                Step(StepType.WAIT, name = "Wait 2 seconds more", ms = 2000),
                Step(StepType.TOAST, name = "Say done", text = "Done! Press EDIT MODE to see how this was made."),
            ),
        )
        macros.add(0, m)
        persist()
        return m
    }

    fun save(macro: Macro) {
        val updated = macro.copy(updatedAt = System.currentTimeMillis())
        val i = macros.indexOfFirst { it.id == macro.id }
        if (i >= 0) macros[i] = updated else macros.add(0, updated)
        persist()
    }

    fun delete(id: String) {
        macros.removeAll { it.id == id }
        persist()
    }

    private fun load() {
        macros.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) macros.add(Macro.fromJson(arr.getJSONObject(i)))
        }
    }

    private fun persist() {
        val arr = JSONArray()
        macros.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
        appContext?.let { runCatching { MacroScheduler.reschedule(it) } }
    }
}
