package com.fidaa.drugsalarm

import android.content.Context
import org.json.JSONObject

/** Keeps the last medicine schedule pushed by the web page, so alarms survive reboots. */
object Store {
    private const val PREFS = "da_prefs"
    private const val KEY = "payload"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
    }

    fun load(ctx: Context): JSONObject? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}
