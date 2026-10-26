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
 * The key below is the project's public key: it can only call check_in. It cannot read the table or approve a phone.
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

    val usable: Boolean get() = state == State.TRIAL || state == State.ACTIVE

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The short code of this phone, like A1B2-C3D4-E5F6. The owner needs it to switch the phone on. */
    fun deviceCode(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256").digest(("macroloop|$id").toByteArray())
        val hex = hash.joinToString("") { "%02X".format(it) }.take(12)
        return hex.chunked(4).joinToString("-")
    }

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

    /** Milliseconds since the last good answer from the server, or a very large number if there is none. */
    fun ageMs(ctx: Context): Long {
        val received = prefs(ctx).getLong("received", 0L)
        return if (received == 0L) Long.MAX_VALUE else System.currentTimeMillis() - received
    }

    /** A plain "1 day 5 h" for the time left. */
    fun leftText(ctx: Context): String {
        val left = (untilMs - serverNow(ctx)).coerceAtLeast(0)
        val mins = left / 60_000
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> "$d day${if (d == 1L) "" else "s"} $h h"
            h > 0 -> "$h h $m min"
            else -> "$m min"
        }
    }

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
            message = if (offline) "MacroLoop needs the internet once to start your trial." else "Checking your access..."
            return false
        }
        val until = p.getLong("until", 0L)
        val received = p.getLong("received", 0L)
        untilMs = until
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
                        message = "MacroLoop needs to check with the server. Connect to the internet and tap Check again."
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

    /** Asks the server about this phone. Safe to call from any thread. Never throws. */
    suspend fun checkIn(ctx: Context) {
        val app = ctx.applicationContext
        checking = true
        try {
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("p_device", deviceCode(app).replace("-", "")).put("p_name", name(app)).toString()
                val conn = (URL("$SERVER/rest/v1/rpc/check_in").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("apikey", PUBLIC_KEY)
                    setRequestProperty("Authorization", "Bearer $PUBLIC_KEY")
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                val j = JSONObject(text)
                val status = j.optString("status", "error")
                if (!ok || status == "error") {
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
}
