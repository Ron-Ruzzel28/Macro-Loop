package com.macroloop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/** Saved pictures (the crops the macro looks for). PNG files in the app's private folder. */
object TemplateStore {
    private lateinit var dir: File

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "pictures").also { it.mkdirs() }
    }

    fun save(bitmap: Bitmap): String {
        val id = UUID.randomUUID().toString()
        File(dir, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return id
    }

    /** The last pictures used, kept decoded: the editor shows many of them and rebuilds its list often. */
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 48
    }

    @Synchronized
    fun load(id: String): Bitmap? {
        if (id.isEmpty()) return null
        cache[id]?.let { return it }
        val f = File(dir, "$id.png")
        val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        if (b != null) cache[id] = b
        return b
    }

    fun loadBytes(id: String): ByteArray? {
        if (id.isEmpty()) return null
        val f = File(dir, "$id.png")
        return if (f.exists()) f.readBytes() else null
    }

    /** Stores a picture that came from a shared file, keeping its id. The id must look like a UUID. */
    fun saveBytes(id: String, bytes: ByteArray) {
        if (!Regex("[0-9a-fA-F-]{8,40}").matches(id)) return
        File(dir, "$id.png").writeBytes(bytes)
        synchronized(this) { cache.remove(id) }
    }

    /** Deletes picture files that no macro uses any more. */
    fun prune(used: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.extension == "png" && f.nameWithoutExtension !in used) {
                f.delete()
                synchronized(this) { cache.remove(f.nameWithoutExtension) }
            }
        }
    }

    fun delete(id: String) {
        if (id.isNotEmpty()) {
            File(dir, "$id.png").delete()
            synchronized(this) { cache.remove(id) }
        }
    }
}
