package com.macroloop.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Starts macros by themselves at set times of day. One alarm is kept: the next start of any macro.
 * It uses an "alarm clock" alarm, which Android lets an app set without special permission and which fires on time
 * even when the phone is resting. The alarm is set again each time the app opens, after a restart, and after each start.
 */
object MacroScheduler {
    private const val CHANNEL = "schedule"
    private const val ACTION = "com.macroloop.app.SCHEDULED_START"

    /** The next time this macro is due after [from], or null if it has no schedule. */
    fun nextRun(m: Macro, from: ZonedDateTime): ZonedDateTime? {
        val s = m.schedule
        if (!s.enabled || s.minutes.isEmpty() || s.days and 127 == 0) return null
        val times = s.minutes.distinct().sorted()
        for (k in 0..7) {
            val date = from.toLocalDate().plusDays(k.toLong())
            if (s.days and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            for (t in times) {
                val at = date.atTime(t / 60, t % 60).atZone(from.zone)
                if (at.isAfter(from)) return at
            }
        }
        return null
    }

    /** "06:00, 18:30" -> [360, 1110]. Returns null if something in it is not a time. */
    fun parseTimes(text: String): List<Int>? {
        val out = ArrayList<Int>()
        for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("(\\d{1,2})[:.](\\d{2})").matchEntire(part) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            out.add(h * 60 + min)
        }
        return out
    }

    fun timesText(minutes: List<Int>): String = minutes.sorted().joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }

    fun daysText(days: Int): String {
        if (days and 127 == 127) return "every day"
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return names.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Sets (or clears) the one alarm for the soonest start of any macro. */
    fun reschedule(ctx: Context, from: ZonedDateTime = ZonedDateTime.now()) {
        MacroRepository.init(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = MacroRepository.macros.mapNotNull { nextRun(it, from) }.minOrNull()
        if (next == null) {
            am.cancel(pending(ctx))
            return
        }
        val show = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(next.toInstant().toEpochMilli(), show), pending(ctx)) }
    }

    /** One line for the macro's schedule, or null when it has none. */
    fun describe(m: Macro): String? {
        val s = m.schedule
        if (!s.enabled || s.minutes.isEmpty()) return null
        return timesText(s.minutes) + ", " + daysText(s.days) + (if (s.durationMin > 0) ", max ${s.durationMin} min" else "")
    }

    fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled macros", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((System.currentTimeMillis() % 100000).toInt(), n) }
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "macroloop:scheduled",
        )
        runCatching { lock.acquire(20_000) }
    }

    /** Checks the time arithmetic and the saving of the new macro fields. */
    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        var passed = 0
        var total = 0
        fun check(name: String, ok: Boolean) {
            total++
            if (ok) passed++ else out += "FAIL  schedule: $name"
        }
        val zone = java.time.ZoneId.of("Asia/Manila")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
        val daily = Macro("m", "t", schedule = Schedule(true, listOf(360, 1110), 127, 0))
        check("times parse", parseTimes("06:00, 18:30") == listOf(360, 1110))
        check("a dot works as the separator", parseTimes("6.05") == listOf(365))
        check("25:00 is refused", parseTimes("25:00") == null)
        check("words are refused", parseTimes("noon") == null)
        check("next start the same morning", nextRun(daily, at(2026, 10, 27, 5, 0)) == at(2026, 10, 27, 6, 0))
        check("next start the same evening", nextRun(daily, at(2026, 10, 27, 7, 0)) == at(2026, 10, 27, 18, 30))
        check("at the exact time, the next one is later", nextRun(daily, at(2026, 10, 27, 6, 0)) == at(2026, 10, 27, 18, 30))
        check("after the last time, tomorrow", nextRun(daily, at(2026, 10, 27, 19, 0)) == at(2026, 10, 28, 6, 0))
        val mondays = Macro("m", "t", schedule = Schedule(true, listOf(360), 1, 0))
        check("Monday only, from a Tuesday, goes to next Monday", nextRun(mondays, at(2026, 10, 27, 5, 0)) == at(2026, 11, 2, 6, 0))
        check("off means no start", nextRun(daily.copy(schedule = daily.schedule.copy(enabled = false)), at(2026, 10, 27, 5, 0)) == null)
        check("no days means no start", nextRun(daily.copy(schedule = daily.schedule.copy(days = 0)), at(2026, 10, 27, 5, 0)) == null)
        check("days text", daysText(127) == "every day" && daysText(5) == "Mon Wed")
        val m = Macro(
            "m", "t", steps = listOf(Step(StepType.COUNTER_ADD, text = "n", amount = 3)),
            counters = listOf(CounterDef("n", true)),
            watchers = listOf(Step(StepType.WATCH, name = "w", conditions = listOf(Condition(kind = ConditionKind.COUNTER, text = "n", percent = 4)))),
            stuckSec = 30, stuck = listOf(Step(StepType.PRESS_BACK)),
            schedule = Schedule(true, listOf(360), 31, 90),
        )
        val back = Macro.fromJson(org.json.JSONObject(m.toJson().toString()))
        check("counters survive saving", back.counters == listOf(CounterDef("n", true)))
        check("counter steps survive saving", back.steps[0].type == StepType.COUNTER_ADD && back.steps[0].amount == 3 && back.steps[0].text == "n")
        check("watchers survive saving", back.watchers.size == 1 && back.watchers[0].conditions[0].kind == ConditionKind.COUNTER && back.watchers[0].conditions[0].percent == 4)
        check("stuck settings survive saving", back.stuckSec == 30 && back.stuck.size == 1)
        check("the schedule survives saving", back.schedule == m.schedule)
        check("an old macro without the new fields still loads", Macro.fromJson(org.json.JSONObject("{\"id\":\"a\",\"name\":\"b\"}")).let { it.counters.isEmpty() && !it.schedule.enabled && it.stuckSec == 0 })
        out.add(0, "Scheduler and saving: $passed of $total")
        return out
    }

    /** Called when the alarm goes off. */
    fun fire(ctx: Context, done: () -> Unit) {
        MacroRepository.init(ctx)
        TemplateStore.init(ctx)
        RunLog.init(ctx)
        License.init(ctx)
        val now = ZonedDateTime.now()
        val due = MacroRepository.macros.filter { m ->
            val n = nextRun(m, now.minusMinutes(2))
            n != null && Duration.between(now, n).seconds <= 20
        }
        // set the next alarm before anything else, so a problem below cannot lose the schedule
        reschedule(ctx, now.plusSeconds(30))
        if (due.isEmpty()) {
            done()
            return
        }
        fun skip(m: Macro, why: String) {
            RunLog.add(m.name, "Scheduled start skipped: $why.", true, 0, null)
            notify(ctx, "${m.name} did not start", why.replaceFirstChar { it.uppercase() } + ".")
        }
        val svc = MacroService.instance
        if (svc == null) {
            due.forEach { skip(it, "Accessibility is off, so MacroLoop cannot tap. Turn it on in MacroLoop's Set up page") }
            done()
            return
        }
        wakeScreen(ctx)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                if (locked) {
                    due.forEach { skip(it, "the phone is locked. Take the lock off, or use a swipe-only lock screen") }
                } else {
                    // only one macro can run; the first goes, the others are said to be skipped
                    val why = svc.startScheduled(due.first())
                    if (why != null) skip(due.first(), why)
                    due.drop(1).forEach { skip(it, "another macro started at the same time") }
                }
            } finally {
                done()
            }
        }, 1800)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        MacroScheduler.fire(context.applicationContext) { pending.finish() }
    }
}

/** After a restart the alarm is gone; set it again. (Screen capture still needs a tap on Start when the phone is on.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MacroScheduler.reschedule(context.applicationContext)
    }
}
