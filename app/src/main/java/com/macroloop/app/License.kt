package com.macroloop.app

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * The 2-day trial and the owner's approval, kept on a small Supabase project (macroloop-license).
 *
 * How it works:
 *  - each phone has a short code (a hash of its Android ID);
 *  - the app calls the server function check_in with that code. The first call starts a 2-day trial on the
 *    SERVER's clock, so changing the phone's date does not help;
 *  - the answer is trial, active (approved by the owner), locked or revoked;
 *  - the last good answer is kept, and the app works offline for up to 24 hours after it;
 *  - if the phone's clock is moved back, the app asks for a fresh check before it runs anything.
 * The key below is the project's public key: it can only call check_in, create_share and get_share_key.
 * It cannot read the tables or approve a phone.
 */
object License {
    private const val SERVER = "https://yiveaqhrzcoyrlazvoht.supabase.co"
    private const val PUBLIC_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlpdmVhcWhyemNveXJsYXp2b2h0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMwMDY1NDgsImV4cCI6MjEwODU4MjU0OH0.pD5bT4ENx4n8soQp01E292eRQa0APpbO5mToVpvnm7E"

    private const val PREFS = "license"
    private const val GRACE_MS = 24L * 60 * 60 * 1000
    private const val CLOCK_SLACK_MS = 5L * 60 * 1000
    const val STALE_MS = 6L * 60 * 60 * 1000

    enum class State { UNKNOWN, TRIAL, ACTIVE, LOCKED, REVOKED }

    var state by mutableStateOf(State.UNKNOWN)
        private set
    var untilMs by mutableStateOf(0L)          // estimated server time when access ends, 0 = no end
        private set
    var message by mutableStateOf("")
        private set
    var checking by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var checkedAt by mutableStateOf(0L)        // when the last good answer came, on this phone's clock
        private set

    val usable: Boolean get() = state == State.TRIAL || state == State.ACTIVE

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The short code of this phone, like A1B2-C3D4-E5F6. The owner needs it to switch the phone on. */
    fun deviceCode(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256").digest(("macroloop|$id").toByteArray())
        val hex = hash.joinToString("") { "%02X".format(it) }.take(12)
        return hex.chunked(4).joinToString("-")
    }

    private fun rawCode(ctx: Context) = deviceCode(ctx).replace("-", "")

    fun name(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""

    fun hasAskedName(ctx: Context): Boolean = prefs(ctx).getBoolean("asked_name", false)

    fun setName(ctx: Context, name: String) {
        prefs(ctx).edit().putString("name", name.trim().take(40)).putBoolean("asked_name", true).apply()
    }

    /** Loads the last answer and works out the state. Call once when the app or service starts. */
    fun init(ctx: Context) {
        evaluate(ctx)
    }

    /** True if macros may run right now. Also refreshes [state] for the screen. */
    fun allowed(ctx: Context): Boolean = evaluate(ctx)

    /** True only for a phone the owner has switched on (not one in its free trial). Needed to share macros. */
    fun switchedOn(ctx: Context): Boolean = evaluate(ctx) && state == State.ACTIVE

    /** Milliseconds since the last good answer from the server, or a very large number if there is none. */
    fun ageMs(ctx: Context): Long {
        val received = prefs(ctx).getLong("received", 0L)
        return if (received == 0L) Long.MAX_VALUE else System.currentTimeMillis() - received
    }

    /** "2 min ago", "3 h ago" or "never". */
    fun lastCheckedText(ctx: Context): String {
        val age = ageMs(ctx)
        if (age == Long.MAX_VALUE) return "never"
        val mins = age / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 48 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} days ago"
        }
    }

    /** A plain "1 day 5 h" for the time left. */
    fun leftText(ctx: Context): String {
        val left = (untilMs - serverNow(ctx)).coerceAtLeast(0)
        val mins = left / 60_000
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d >= 400 -> "${d / 365} years"
            d > 0 -> "$d day${if (d == 1L) "" else "s"} $h h"
            h > 0 -> "$h h $m min"
            else -> "$m min"
        }
    }

    /** The end date, like "25 Dec 2026", on this phone's time zone. */
    fun untilDateText(): String =
        if (untilMs == 0L) "" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(untilMs))

    private fun serverNow(ctx: Context): Long {
        val p = prefs(ctx)
        val received = p.getLong("received", 0L)
        return p.getLong("server_time", 0L) + (System.currentTimeMillis() - received)
    }

    private fun evaluate(ctx: Context): Boolean {
        val p = prefs(ctx)
        val status = p.getString("status", null)
        val now = System.currentTimeMillis()

        // A clock moved back is treated as a trick: ask the server again before running anything.
        val maxSeen = p.getLong("max_seen", 0L)
        val clockBack = now < maxSeen - CLOCK_SLACK_MS
        if (now > maxSeen) p.edit().putLong("max_seen", now).apply()

        if (status == null) {
            state = State.UNKNOWN
            message = if (offline) "TapLens needs the internet once to start your trial." else "Checking your access..."
            return false
        }
        val until = p.getLong("until", 0L)
        val received = p.getLong("received", 0L)
        untilMs = until
        checkedAt = received
        val age = now - received
        return when (status) {
            "trial", "active" -> {
                val expired = until != 0L && serverNow(ctx) >= until
                when {
                    expired -> {
                        state = State.LOCKED
                        message = if (status == "trial") "Your 2-day trial has ended." else "Your access has ended."
                        false
                    }
                    clockBack || age < -CLOCK_SLACK_MS || age > GRACE_MS -> {
                        state = State.LOCKED
                        message = "TapLens needs to check with the server. Connect to the internet and tap Check again."
                        false
                    }
                    else -> {
                        state = if (status == "trial") State.TRIAL else State.ACTIVE
                        message = ""
                        true
                    }
                }
            }
            "revoked" -> {
                state = State.REVOKED
                message = "This phone has been switched off by the owner."
                false
            }
            else -> {
                state = State.LOCKED
                message = "Your 2-day trial has ended."
                false
            }
        }
    }

    private fun parseTime(s: String?): Long =
        if (s.isNullOrBlank() || s == "null") 0L else runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** Calls one server function and returns its answer. Blocking: use it off the main thread. */
    private fun post(function: String, body: JSONObject): JSONObject {
        val conn = (URL("$SERVER/rest/v1/rpc/$function").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("apikey", PUBLIC_KEY)
            setRequestProperty("Authorization", "Bearer $PUBLIC_KEY")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "bad_reply") }
        } finally {
            conn.disconnect()
        }
    }

    /** Asks the server about this phone. Safe to call from any thread. Never throws. */
    suspend fun checkIn(ctx: Context) {
        val app = ctx.applicationContext
        checking = true
        try {
            withContext(Dispatchers.IO) {
                val j = post("check_in", JSONObject().put("p_device", rawCode(app)).put("p_name", name(app)))
                val status = j.optString("status", "error")
                if (status == "error" || j.has("error") || j.has("code")) {
                    message = j.optString("message", "The server did not accept this phone.")
                    offline = false
                } else {
                    prefs(app).edit()
                        .putString("status", status)
                        .putLong("until", parseTime(j.optString("until", "")))
                        .putLong("server_time", parseTime(j.optString("server_time", "")))
                        .putLong("received", System.currentTimeMillis())
                        .apply()
                    offline = false
                }
            }
        } catch (e: Exception) {
            offline = true
        } finally {
            checking = false
            evaluate(app)
        }
    }

    // ------------------------------------------------------------------ sharing macros

    /** The answer to making or opening a share. [error] is empty when it worked. */
    class ShareAnswer(
        val error: String = "",
        val shareId: String = "",
        val key: String = "",
        val runOnly: Boolean = false,
        val macroName: String = "",
        val fromName: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

    private fun shareError(j: JSONObject): String = when (j.optString("error", "")) {
        "" -> if (j.has("code")) "The server refused the request." else ""
        "not_active" -> "Only phones the owner has switched on can share or open macros."
        "revoked" -> "The owner has switched this file off."
        "unknown" -> "The server does not know this file."
        else -> "The server could not do that."
    }

    /** Export: asks the server for a new share (an id and a key). Only a switched-on phone gets one. */
    suspend fun createShare(ctx: Context, macroName: String, runOnly: Boolean): ShareAnswer = try {
        withContext(Dispatchers.IO) {
            val j = post("create_share", JSONObject().put("p_device", rawCode(ctx)).put("p_macro_name", macroName).put("p_run_only", runOnly))
            val err = shareError(j)
            if (err.isNotEmpty()) ShareAnswer(error = err)
            else ShareAnswer(shareId = j.getString("share_id"), key = j.getString("key"), runOnly = j.optBoolean("run_only"))
        }
    } catch (e: Exception) {
        ShareAnswer(error = "No internet. Sharing needs a connection to get the key.")
    }

    /** Import: asks the server for the key of a file. Only a switched-on phone gets it, and only while the file is on. */
    suspend fun getShareKey(ctx: Context, shareId: String): ShareAnswer = try {
        withContext(Dispatchers.IO) {
            val j = post("get_share_key", JSONObject().put("p_device", rawCode(ctx)).put("p_share", shareId))
            val err = shareError(j)
            if (err.isNotEmpty()) ShareAnswer(error = err)
            else ShareAnswer(
                shareId = shareId, key = j.getString("key"), runOnly = j.optBoolean("run_only"),
                macroName = j.optString("macro_name", ""), fromName = j.optString("made_by_name", ""),
            )
        }
    } catch (e: Exception) {
        ShareAnswer(error = "No internet. Opening a shared macro needs a connection to get the key.")
    }
}
