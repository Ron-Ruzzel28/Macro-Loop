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

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "macros.json")
        load()
    }

    fun get(id: String): Macro? = macros.firstOrNull { it.id == id }

    fun create(name: String): Macro {
        val m = Macro(id = UUID.randomUUID().toString(), name = name.trim().ifEmpty { "New macro" })
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
    }
}
