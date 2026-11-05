package com.fidaa.drugsalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote follow: this phone follows other patients. Their medicine times are stored here and
 * scheduled as local alarms (AlarmManager.setAlarmClock), so the follower is alerted at every
 * dose time even when the app is closed, the screen is off or the phone is on silent.
 */
object FollowStore {
    private const val PREFS = "da_follow"
    private const val KEY = "payload"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
    }

    fun load(ctx: Context): JSONObject? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }
}

object FollowAlarms {
    const val ACTION = "com.fidaa.drugsalarm.FOLLOW_ALARM"
    const val REFRESH_ACTION = "com.fidaa.drugsalarm.FOLLOW_REFRESH"
    private const val REFRESH_CODE = 91001
    private const val REFRESH_EVERY_MS = 30 * 60 * 1000L

    private fun code(pc: String, id: String, tm: String): Int = ("F|$pc|$id|$tm").hashCode()

    private fun operation(ctx: Context, pc: String, id: String, tm: String, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(ctx, FollowAlarmReceiver::class.java).apply {
            action = ACTION
            putExtra("pc", pc)
            putExtra("id", id)
            putExtra("tm", tm)
            putExtra("at", at)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, code(pc, id, tm), i, flags)
    }

    fun schedule(ctx: Context, pc: String, id: String, tm: String, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val op = operation(ctx, pc, id, tm, at, true) ?: return
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
    }

    private fun cancelAll(ctx: Context, payload: JSONObject?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pats = payload?.optJSONArray("patients") ?: return
        for (n in 0 until pats.length()) {
            val p = pats.optJSONObject(n) ?: continue
            val pc = p.optString("code")
            val items = p.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val it = items.optJSONObject(k) ?: continue
                val op = operation(ctx, pc, it.optString("id"), it.optString("tm"), 0L, false)
                if (op != null) {
                    am.cancel(op)
                    op.cancel()
                }
            }
        }
    }

    /** New list of followed patients from the page (or from a background refresh). */
    fun apply(ctx: Context, json: String) {
        cancelAll(ctx, FollowStore.load(ctx))
        FollowStore.save(ctx, json)
        rescheduleAll(ctx)
    }

    fun rescheduleAll(ctx: Context) {
        val payload = FollowStore.load(ctx)
        val pats = payload?.optJSONArray("patients")
        if (pats != null) {
            val now = System.currentTimeMillis()
            val today = Alarms.today(now)
            for (n in 0 until pats.length()) {
                val p = pats.optJSONObject(n) ?: continue
                val pc = p.optString("code")
                val skipValid = p.optString("day") == today
                val items = p.optJSONArray("items") ?: continue
                for (k in 0 until items.length()) {
                    val it = items.optJSONObject(k) ?: continue
                    val id = it.optString("id")
                    val tm = it.optString("tm")
                    if (id.isEmpty() || tm.isEmpty()) continue
                    try {
                        val at = Alarms.nextTrigger(tm, skipValid && it.optBoolean("skip", false), now)
                        val end = it.optLong("end", 0L)
                        if (end > 0L && at > end) continue
                        schedule(ctx, pc, id, tm, at)
                    } catch (e: Exception) {
                        // malformed entry
                    }
                }
            }
        }
        scheduleRefresh(ctx, pats != null && pats.length() > 0)
    }

    /** Inexact repeating refresh so changes made by the patient reach this phone even if the app stays closed. */
    fun scheduleRefresh(ctx: Context, enabled: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, FollowRefreshReceiver::class.java).apply { action = REFRESH_ACTION }
        val pi = PendingIntent.getBroadcast(
            ctx, REFRESH_CODE, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (!enabled) {
            am.cancel(pi)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + REFRESH_EVERY_MS, pi)
    }

    fun findPatient(ctx: Context, pc: String): JSONObject? {
        val pats = FollowStore.load(ctx)?.optJSONArray("patients") ?: return null
        for (n in 0 until pats.length()) {
            val p = pats.optJSONObject(n) ?: continue
            if (p.optString("code") == pc) return p
        }
        return null
    }

    fun findItem(patient: JSONObject, id: String, tm: String): JSONObject? {
        val items = patient.optJSONArray("items") ?: return null
        for (k in 0 until items.length()) {
            val it = items.optJSONObject(k) ?: continue
            if (it.optString("id") == id && it.optString("tm") == tm) return it
        }
        return null
    }

    /** Blocking network call: run off the main thread. Keeps the old data if the server cannot be reached. */
    fun refreshFromServer(ctx: Context) {
        val cur = FollowStore.load(ctx) ?: return
        val base = cur.optString("base")
        val fid = cur.optString("fid")
        val pats = cur.optJSONArray("patients") ?: return
        if (base.isEmpty() || fid.isEmpty() || pats.length() == 0) return
        val out = JSONArray()
        var changed = false
        for (n in 0 until pats.length()) {
            val p = pats.optJSONObject(n) ?: continue
            val pc = p.optString("code")
            val fresh = fetch(base, pc, fid)
            if (fresh == null) {
                out.put(p)
            } else {
                out.put(buildPatient(pc, p.optString("name"), fresh))
                changed = true
            }
        }
        if (changed) {
            cur.put("patients", out)
            apply(ctx, cur.toString())
        }
    }

    private fun fetch(base: String, pc: String, fid: String): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            // ?i=1 lets API calls through InfinityFree's browser-check page
            c = URL("$base/api/sync.php?i=1").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("action", "pull").put("code", pc).put("fid", fid).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return null
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(text)
            if (j.optBoolean("ok")) j else null
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun buildPatient(pc: String, name: String, resp: JSONObject): JSONObject {
        val day = resp.optString("day")
        val today = Alarms.today(System.currentTimeMillis())
        val taken = resp.optJSONObject("taken")
        val items = JSONArray()
        val meds = resp.optJSONArray("meds")
        if (meds != null) {
            for (n in 0 until meds.length()) {
                val m = meds.optJSONObject(n) ?: continue
                val id = m.optString("id")
                val label = m.optString("name").ifEmpty { m.optString("nameEn") }
                val end = m.optLong("end", 0L)
                val times = m.optJSONArray("times") ?: continue
                for (k in 0 until times.length()) {
                    val tm = times.optString(k)
                    val skip = day == today && taken != null && taken.has("$id|$tm")
                    items.put(JSONObject().put("id", id).put("tm", tm).put("label", label).put("end", end).put("skip", skip))
                }
            }
        }
        return JSONObject().put("code", pc).put("name", name).put("day", day).put("items", items)
    }
}

/** Fires at a followed patient's dose time. */
class FollowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pc = intent.getStringExtra("pc") ?: return
        val id = intent.getStringExtra("id") ?: return
        val tm = intent.getStringExtra("tm") ?: return
        val at = intent.getLongExtra("at", 0L)

        val patient = FollowAlarms.findPatient(context, pc) ?: return
        val item = FollowAlarms.findItem(patient, id, tm) ?: return

        val now = System.currentTimeMillis()
        val end = item.optLong("end", 0L)
        if (end > 0L && now > end) return

        // Re-arm the same time for tomorrow.
        val next = Alarms.nextTrigger(tm, false, maxOf(now, at) + 1000L)
        if (end == 0L || next <= end) FollowAlarms.schedule(context, pc, id, tm, next)

        val who = patient.optString("name").ifEmpty { pc }
        val label = item.optString("label")
        Notifier.showFollow(
            context, "$pc|$id|$tm",
            "حان موعد دواء $who",
            (if (label.isNotEmpty()) "$label — " else "") + "اتصل به لتذكيره بتناول الدواء"
        )
    }
}

/** Periodic background refresh of the followed patients' schedules. */
class FollowRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                FollowAlarms.refreshFromServer(app)
            } catch (e: Exception) {
                // try again at the next round
            } finally {
                val enabled = (FollowStore.load(app)?.optJSONArray("patients")?.length() ?: 0) > 0
                FollowAlarms.scheduleRefresh(app, enabled)
                pending.finish()
            }
        }.start()
    }
}
