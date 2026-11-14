package de.familienplaner.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
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
    private var pendingDay: String? = null
    private var pageReady = false

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
        fun setMonths(json: String) {
            getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putString("months", json).apply()
            WidgetProvider.refresh(applicationContext)
        }
        @JavascriptInterface
        fun isNative(): Boolean = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.userAgentString = s.userAgentString + " FamilienPlanerApp/1.0"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        setup(web)
        web.addJavascriptInterface(Bridge(), "FPNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                if (url != null && url.startsWith("https://johnny22061975.github.io")) {
                    pageReady = true
                    val d = pendingDay
                    if (d != null) { pendingDay = null; runOpenDay(d) }
                }
            }
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
            // Anmelde-Fenster (Google) als eigenes Fenster in der App öffnen
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val pop = WebView(this@MainActivity)
                setup(pop)
                pop.webViewClient = WebViewClient()
                val dlg = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_NoActionBar)
                dlg.setContentView(pop)
                pop.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(w: WebView) { dlg.dismiss() }
                }
                dlg.setOnDismissListener { pop.destroy() }
                dlg.show()
                val tr = resultMsg.obj as WebView.WebViewTransport
                tr.webView = pop
                resultMsg.sendToTarget()
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleDayIntent(intent)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl("https://johnny22061975.github.io/")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDayIntent(intent)
    }

    private fun handleDayIntent(i: Intent?) {
        if (i == null) return
        val d = i.getStringExtra("day") ?: return
        i.removeExtra("day")
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) return
        if (pageReady) runOpenDay(d) else pendingDay = d
    }

    private fun runOpenDay(d: String) {
        val js = "(function(){var n=0;var t=setInterval(function(){ if((window.fpOpenDay&&window.fpOpenDay('" + d + "'))||++n>40) clearInterval(t); },300);})()"
        web.evaluateJavascript(js, null)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); web.saveState(o) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
