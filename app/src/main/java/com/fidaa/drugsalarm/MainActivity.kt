package com.fidaa.drugsalarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.ValueCallback
import org.json.JSONObject
import org.json.JSONTokener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingMic: PermissionRequest? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingSanad: String? = null
    private var pendingPatients: String? = null
    private var loadingLegacy = false
    private val remoteUrl = "https://sand.site.je/"

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            if (callback != null) {
                val data = result.data
                val uris = if (result.resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    when {
                        clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                } else null
                callback.onReceiveValue(uris)
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingMic
            pendingMic = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyAlarmFlags(intent)

        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.textZoom = 100
        s.setSupportZoom(false)
        // Always fetch the live site rather than requiring a cached copy.
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.blockNetworkLoads = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val prefs = getSharedPreferences("sanad_web_migration", MODE_PRIVATE)
                if (url.startsWith("https://appassets.androidplatform.net/assets/www/index.html") &&
                    !prefs.getBoolean("done", false) && !loadingLegacy
                ) {
                    loadingLegacy = true
                    view.evaluateJavascript("JSON.stringify({sanad2:localStorage.getItem('sanad2'),sanadPatients:localStorage.getItem('sanadPatients')})") { encoded ->
                        try {
                            val raw = JSONTokener(encoded).nextValue() as? String
                            val data = if (raw != null) JSONObject(raw) else JSONObject()
                            pendingSanad = data.optString("sanad2").takeIf { it.isNotEmpty() && it != "null" }
                            pendingPatients = data.optString("sanadPatients").takeIf { it.isNotEmpty() && it != "null" }
                        } catch (_: Exception) { }
                        view.post { view.loadUrl(remoteUrl) }
                    }
                } else if (url.startsWith(remoteUrl) && !prefs.getBoolean("done", false)) {
                    val script = StringBuilder("(function(){")
                    pendingSanad?.let { value ->
                        script.append("if(localStorage.getItem('sanad2')===null)localStorage.setItem('sanad2',")
                        script.append(JSONObject.quote(value)).append(");")
                    }
                    pendingPatients?.let { value ->
                        script.append("if(localStorage.getItem('sanadPatients')===null)localStorage.setItem('sanadPatients',")
                        script.append(JSONObject.quote(value)).append(");")
                    }
                    script.append("})();")
                    view.evaluateJavascript(script.toString(), null)
                    prefs.edit().putBoolean("done", true).apply()
                    pendingSanad = null
                    pendingPatients = null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net" || u.host == "sand.site.je") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                    // no app can handle this link
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                return try {
                    fileChooser.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    pendingFileChooser = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant only microphone access requested by our HTTPS website.
                val trustedOrigin = request.origin.scheme == "https" && request.origin.host == "sand.site.je"
                val audioOnly = request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (trustedOrigin && audioOnly && request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMic = request
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.deny()
                }
            }
        }

        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        if (getSharedPreferences("sanad_web_migration", MODE_PRIVATE).getBoolean("done", false)) {
            web.loadUrl(remoteUrl)
        } else {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAlarmFlags(intent)
    }

    /** When opened by an alarm, show over the lock screen and wake the display. */
    @Suppress("DEPRECATION")
    private fun applyAlarmFlags(i: Intent?) {
        if (i?.getBooleanExtra("alarm", false) != true) return
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
