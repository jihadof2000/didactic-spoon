package com.fidaa.drugsalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Schedules exact alarms (AlarmManager.setAlarmClock) so they ring even when the app is closed. */
object Alarms {
    const val ACTION = "com.fidaa.drugsalarm.ALARM"

    private fun code(id: String, tm: String, snooze: Boolean): Int =
        (id + "|" + tm + (if (snooze) "|s" else "")).hashCode()

    private fun operation(ctx: Context, id: String, tm: String, snooze: Boolean, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION
            putExtra("id", id)
            putExtra("tm", tm)
            putExtra("snooze", snooze)
            putExtra("at", at)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, code(id, tm, snooze), i, flags)
    }

    fun nextTrigger(tm: String, skipToday: Boolean, now: Long): Long {
        val parts = tm.split(":")
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, parts[0].trim().toInt())
        cal.set(Calendar.MINUTE, parts[1].trim().toInt())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (skipToday || cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun schedule(ctx: Context, id: String, tm: String, at: Long, snooze: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val op = operation(ctx, id, tm, snooze, at, true) ?: return
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
    }

    private fun cancelItem(ctx: Context, id: String, tm: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (snooze in listOf(false, true)) {
            val op = operation(ctx, id, tm, snooze, 0L, false)
            if (op != null) {
                am.cancel(op)
                op.cancel()
            }
        }
    }

    private fun today(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    /** Called when the page sends a fresh schedule: drop the old alarms, store, schedule the new ones. */
    fun applyPayload(ctx: Context, json: String) {
        val old = Store.load(ctx)
        val oldItems = old?.optJSONArray("items")
        if (oldItems != null) {
            for (n in 0 until oldItems.length()) {
                val obj: JSONObject = oldItems.getJSONObject(n)
                cancelItem(ctx, obj.optString("id"), obj.optString("tm"))
            }
        }
        Store.save(ctx, json)
        rescheduleAll(ctx)
    }

    /** Re-arms every medicine time (also used after reboot / time change). */
    fun rescheduleAll(ctx: Context) {
        val payload = Store.load(ctx) ?: return
        val items = payload.optJSONArray("items") ?: return
        val now = System.currentTimeMillis()
        val skipValid = payload.optString("day") == today(now)
        for (n in 0 until items.length()) {
            val obj = items.getJSONObject(n)
            val id = obj.optString("id")
            val tm = obj.optString("tm")
            if (id.isEmpty() || tm.isEmpty()) continue
            try {
                val skip = skipValid && obj.optBoolean("skip", false)
                val at = nextTrigger(tm, skip, now)
                val end = obj.optLong("end", 0L)
                if (end > 0L && at > end) continue
                schedule(ctx, id, tm, at, false)
            } catch (e: Exception) {
                // ignore a malformed entry
            }
        }
    }

    fun findItem(ctx: Context, id: String, tm: String): JSONObject? {
        val items = Store.load(ctx)?.optJSONArray("items") ?: return null
        for (n in 0 until items.length()) {
            val obj = items.getJSONObject(n)
            if (obj.optString("id") == id && obj.optString("tm") == tm) return obj
        }
        return null
    }
}
