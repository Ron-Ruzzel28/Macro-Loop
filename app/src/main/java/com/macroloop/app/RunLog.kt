package com.macroloop.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last runs: when, which macro, how it ended and why. When a run stops with a problem, a picture of the
 * screen at that moment is kept too. Only the last [KEEP] runs are kept; older ones and their pictures are deleted.
 */
object RunLog {
    const val KEEP = 20

    class Entry(
        val time: Long,
        val macro: String,
        val result: String,
        val problem: Boolean,
        val seconds: Int,
        val shot: String,       // file name of the last-screen picture, or ""
    )

    val entries = mutableStateListOf<Entry>()
    private lateinit var dir: File
    private val file: File get() = File(dir, "log.json")

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "runlog").also { it.mkdirs() }
        entries.clear()
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                entries.add(Entry(o.getLong("time"), o.getString("macro"), o.getString("result"), o.optBoolean("problem"), o.optInt("seconds"), o.optString("shot")))
            }
        }
    }

    /** Adds a run to the top of the log. [frame] is the screen at the end, kept only for a problem. */
    fun add(macro: String, result: String, problem: Boolean, seconds: Int, frame: Bitmap?) {
        if (!::dir.isInitialized) return
        val time = System.currentTimeMillis()
        var shot = ""
        if (problem && frame != null) {
            shot = "$time.jpg"
            runCatching { File(dir, shot).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
                .onFailure { shot = "" }
        }
        entries.add(0, Entry(time, macro, result, problem, seconds, shot))
        while (entries.size > KEEP) {
            val old = entries.removeAt(entries.lastIndex)
            if (old.shot.isNotEmpty()) File(dir, old.shot).delete()
        }
        persist()
    }

    fun clear() {
        entries.forEach { if (it.shot.isNotEmpty()) File(dir, it.shot).delete() }
        entries.clear()
        persist()
    }

    fun shotFile(e: Entry): File? = if (e.shot.isEmpty() || !::dir.isInitialized) null else File(dir, e.shot).takeIf { it.exists() }

    private fun persist() {
        val a = JSONArray()
        entries.forEach {
            a.put(JSONObject().put("time", it.time).put("macro", it.macro).put("result", it.result)
                .put("problem", it.problem).put("seconds", it.seconds).put("shot", it.shot))
        }
        runCatching { file.writeText(a.toString()) }
    }
}

/** Counters that are kept between runs (and days). One number per macro and name. */
object CounterStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("counters", Context.MODE_PRIVATE)

    fun load(ctx: Context, macroId: String, name: String): Int = prefs(ctx).getInt("$macroId/$name", 0)

    fun save(ctx: Context, macroId: String, name: String, value: Int) {
        prefs(ctx).edit().putInt("$macroId/$name", value).apply()
    }
}
