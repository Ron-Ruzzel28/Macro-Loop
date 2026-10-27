package com.macroloop.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A copy of a macro that is being edited, saved a moment after every change. If the editor is closed, or the app is
 * stopped, before Save, the changes are still here and the editor offers to bring them back.
 */
object DraftStore {
    private lateinit var dir: File

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "drafts").also { it.mkdirs() }
    }

    fun save(m: Macro) {
        if (!::dir.isInitialized) return
        runCatching { File(dir, m.id + ".json").writeText(m.toJson().toString()) }
    }

    fun load(id: String): Macro? {
        if (!::dir.isInitialized) return null
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { Macro.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun clear(id: String) {
        if (!::dir.isInitialized) return
        File(dir, "$id.json").delete()
    }

    fun macros(): List<Macro> {
        if (!::dir.isInitialized) return emptyList()
        return dir.listFiles()?.mapNotNull { f -> runCatching { Macro.fromJson(JSONObject(f.readText())) }.getOrNull() } ?: emptyList()
    }
}
