package com.macroloop.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export and import of macros as encrypted files (.mlm).
 *
 * A file is: "MLM1", the share id (16 bytes), a random nonce (12 bytes), then the macro and its pictures,
 * zipped and locked with AES-256-GCM. The key is NOT in the file and NOT in the app. It lives on the server,
 * which gives it only to a phone the owner has switched on, and only while the file has not been switched off.
 * The share id is part of what is checked, so a file cannot be re-labelled to borrow another file's key.
 */
object MacroShare {
    private val MAGIC = "MLM1".toByteArray()
    private const val NONCE_LEN = 12
    private const val HEADER_LEN = 4 + 16
    private const val MAX_UNZIPPED = 60L * 1024 * 1024

    class Pack(val macro: Macro, val pictures: Map<String, ByteArray>)

    class Outcome(val ok: Boolean, val message: String, val file: File? = null, val macro: Macro? = null)

    fun pictureIds(steps: List<Step>): Set<String> {
        val ids = LinkedHashSet<String>()
        fun walk(list: List<Step>) {
            for (s in list) {
                if (s.templateId.isNotEmpty()) ids.add(s.templateId)
                for (c in s.conditions) if (c.templateId.isNotEmpty()) ids.add(c.templateId)
                s.holdUntil?.let { if (it.templateId.isNotEmpty()) ids.add(it.templateId) }
                walk(s.onSuccess)
                walk(s.onFail)
            }
        }
        walk(steps)
        return ids
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun uuidBytes(id: String): ByteArray {
        val u = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }

    private fun bytesToUuid(b: ByteArray): String {
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long).toString()
    }

    /** Locks a macro and its pictures with a key. */
    fun seal(macro: Macro, pictures: Map<String, ByteArray>, keyHex: String, shareId: String): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("macro.json"))
            z.write(macro.toJson().toString().toByteArray())
            z.closeEntry()
            for ((id, bytes) in pictures) {
                z.putNextEntry(ZipEntry("pic/$id.png"))
                z.write(bytes)
                z.closeEntry()
            }
        }
        val header = MAGIC + uuidBytes(shareId)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + nonce + cipher.doFinal(zip.toByteArray())
    }

    /** The share id inside a file, or null if it is not a TapLens file. */
    fun shareIdOf(bytes: ByteArray): String? {
        if (bytes.size < HEADER_LEN + NONCE_LEN + 16) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        return runCatching { bytesToUuid(bytes.copyOfRange(4, 20)) }.getOrNull()
    }

    /** Unlocks a file. Returns null if the key is wrong or the file was changed. */
    fun open(bytes: ByteArray, keyHex: String): Pack? {
        if (shareIdOf(bytes) == null) return null
        return runCatching {
            val header = bytes.copyOfRange(0, HEADER_LEN)
            val nonce = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(bytes, HEADER_LEN + NONCE_LEN, bytes.size - HEADER_LEN - NONCE_LEN)
            var macro: Macro? = null
            val pics = HashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(plain)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNZIPPED) error("too big")
                        out.write(buf, 0, n)
                    }
                    when {
                        e.name == "macro.json" -> macro = Macro.fromJson(JSONObject(out.toString("UTF-8")))
                        e.name.startsWith("pic/") && e.name.endsWith(".png") ->
                            pics[e.name.removePrefix("pic/").removeSuffix(".png")] = out.toByteArray()
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
            Pack(macro ?: error("no macro"), pics)
        }.getOrNull()
    }

    /** Checks the locking on its own, no server needed: round trip, wrong key, tampering, wrong id, bad file. */
    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        var passed = 0
        var total = 0
        fun check(name: String, ok: Boolean) {
            total++
            if (ok) passed++ else out += "FAIL  share: $name"
        }
        val key = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val other = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        val id = UUID.randomUUID().toString()
        val macro = Macro(id = "m1", name = "Test", steps = listOf(Step(type = StepType.CLICK_IMAGE, id = "s1", templateId = "pic-1")))
        val pic = ByteArray(5000) { (it % 251).toByte() }
        val sealed = seal(macro, mapOf("pic-1" to pic), key, id)
        check("share id readable", shareIdOf(sealed) == id)
        val back = open(sealed, key)
        check("round trip name", back?.macro?.name == "Test")
        check("round trip step", back?.macro?.steps?.firstOrNull()?.templateId == "pic-1")
        check("round trip picture", back?.pictures?.get("pic-1")?.contentEquals(pic) == true)
        check("wrong key refused", open(sealed, other) == null)
        val flipped = sealed.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        check("changed body refused", open(flipped, key) == null)
        val relabelled = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        check("changed share id refused", open(relabelled, key) == null)
        check("bad magic refused", shareIdOf(ByteArray(100)) == null)
        check("too short refused", shareIdOf(ByteArray(10)) == null)
        check("two seals differ", !seal(macro, emptyMap(), key, id).contentEquals(seal(macro, emptyMap(), key, id)))
        check("run only flag saved", Macro.fromJson(macro.copy(runOnly = true, sharedBy = "A").toJson()).let { it.runOnly && it.sharedBy == "A" })
        out.add(0, "Sharing: $passed of $total")
        return out
    }

    private fun safeFileName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_').ifEmpty { "macro" }.take(40)

    /** Export one macro. Needs the internet (for the key) and a switched-on phone. */
    suspend fun export(ctx: Context, macro: Macro, runOnly: Boolean): Outcome {
        if (macro.runOnly) return Outcome(false, "This macro is run only. It cannot be shared on.")
        if (!License.switchedOn(ctx)) return Outcome(false, "Only a phone the owner has switched on can share macros.")
        val answer = License.createShare(ctx, macro.name, runOnly)
        if (!answer.ok) return Outcome(false, answer.error)
        val pictures = HashMap<String, ByteArray>()
        for (id in pictureIds(macro.lists.flatten())) TemplateStore.loadBytes(id)?.let { pictures[id] = it }
        val bytes = seal(macro.copy(runOnly = false, sharedBy = "", schedule = Schedule()), pictures, answer.key, answer.shareId)
        val dir = File(ctx.cacheDir, "shared").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, safeFileName(macro.name) + ".mlm")
        file.writeBytes(bytes)
        return Outcome(true, "Ready to send.", file = file)
    }

    /** Import a file. Needs the internet (for the key) and a switched-on phone. */
    suspend fun import(ctx: Context, bytes: ByteArray): Outcome {
        val shareId = shareIdOf(bytes) ?: return Outcome(false, "This is not a TapLens macro file.")
        if (!License.switchedOn(ctx)) return Outcome(false, "Only a phone the owner has switched on can open shared macros.")
        val answer = License.getShareKey(ctx, shareId)
        if (!answer.ok) return Outcome(false, answer.error)
        val pack = open(bytes, answer.key) ?: return Outcome(false, "The file is damaged, or it was changed after it was sent.")
        for ((id, png) in pack.pictures) TemplateStore.saveBytes(id, png)
        val imported = pack.macro.copy(
            id = UUID.randomUUID().toString(),
            runOnly = answer.runOnly,
            sharedBy = answer.fromName.ifBlank { "the owner" },
            updatedAt = System.currentTimeMillis(),
            schedule = Schedule(),
        )
        MacroRepository.save(imported)
        return Outcome(true, "Added \"${imported.name}\"" + if (answer.runOnly) " (run only)." else ".", macro = imported)
    }
}
