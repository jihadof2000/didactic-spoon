package com.fidaa.drugsalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val tm = intent.getStringExtra("tm") ?: return
        val snooze = intent.getBooleanExtra("snooze", false)
        val at = intent.getLongExtra("at", 0L)

        val payload = Store.load(context) ?: return
        // The medicine may have been deleted since this alarm was scheduled.
        val item = Alarms.findItem(context, id, tm) ?: return

        val now = System.currentTimeMillis()
        val end = item.optLong("end", 0L)
        if (end > 0L && now > end) return

        if (!snooze) {
            // Re-arm the same time for the next day.
            val base = maxOf(now, at) + 1000L
            val next = Alarms.nextTrigger(tm, false, base)
            if (end == 0L || next <= end) Alarms.schedule(context, id, tm, next, false)
        }

        Notifier.show(context, payload.optString("title", "Time for your medicine"), item.optString("label"))
    }
}
