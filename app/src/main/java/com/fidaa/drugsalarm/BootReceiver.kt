package com.fidaa.drugsalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms all alarms after reboot, app update, or a clock / time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.rescheduleAll(context)
    }
}
