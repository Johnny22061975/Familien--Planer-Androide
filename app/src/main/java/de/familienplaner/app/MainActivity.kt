package de.familienplaner.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var geoCb: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null
        fileCb?.onReceiveValue(data)
        fileCb = null
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        geoCb?.invoke(geoOrigin, res.values.any { it }, false)
        geoCb = null
    }

    inner class Bridge {
        @JavascriptInterface
        fun setToday(json: String) {
            getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putString("today", json).apply()
            WidgetProvider.refresh(applicationContext)
        }
        @JavascriptInterface
        fun isNative(): Boolean = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " FamilienPlanerApp/1.0"
        web.addJavascriptInterface(Bridge(), "FPNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val h = r.url.host ?: return false
                if (h.endsWith("github.io") || h.endsWith("firebaseapp.com") || h.endsWith("googleapis.com") || h.endsWith("google.com")) return false
                startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { picker.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; false }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                val ok = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (ok) { cb.invoke(origin, true, false); return }
                geoCb = cb; geoOrigin = origin
                perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl("https://johnny22061975.github.io/")
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); web.saveState(o) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
