package com.fidaa.drugsalarm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import java.util.Locale

/** Exposed to the web page as `window.Android`. */
class Bridge(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var pendingLang: String = "ar"

    /** Full medicine schedule (JSON) from the page. */
    @JavascriptInterface
    fun sync(json: String) {
        Alarms.applyPayload(ctx, json)
    }

    /** Stops the ringing notification (the page is on screen, or the user answered). */
    @JavascriptInterface
    fun silence() {
        Notifier.cancel(ctx)
    }

    /** "Remind me later": one-off alarm after [ms] milliseconds. */
    @JavascriptInterface
    fun snooze(id: String, tm: String, ms: Long) {
        Notifier.cancel(ctx)
        Alarms.schedule(ctx, id, tm, System.currentTimeMillis() + ms, true)
    }

    @JavascriptInterface
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, ctx.getString(R.string.share_title))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    @JavascriptInterface
    fun speak(text: String, lang: String) {
        main.post {
            if (tts == null) {
                pendingText = text
                pendingLang = lang
                tts = TextToSpeech(ctx) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    val t = pendingText
                    if (ttsReady && t != null) {
                        pendingText = null
                        doSpeak(t, pendingLang)
                    }
                }
            } else if (ttsReady) {
                doSpeak(text, lang)
            }
        }
    }

    private fun doSpeak(text: String, lang: String) {
        val engine = tts ?: return
        engine.setLanguage(Locale(lang))
        engine.setSpeechRate(0.85f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "med")
    }
}
