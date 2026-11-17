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
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
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

    private var camUri: Uri? = null
    private var pendingChooser: WebChromeClient.FileChooserParams? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        var data: Array<Uri>? = null
        if (r.resultCode == Activity.RESULT_OK) {
            data = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            val cu = camUri
            if (data == null && cu != null) data = arrayOf(cu)
        }
        camUri = null
        fileCb?.onReceiveValue(data)
        fileCb = null
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val p = pendingChooser
        pendingChooser = null
        if (p != null) startChooser(p)
    }

    private fun wantsImage(p: WebChromeClient.FileChooserParams): Boolean {
        val t = p.acceptTypes
        if (t == null || t.isEmpty()) return true
        return t.any { it.isEmpty() || it.startsWith("image") || it.contains("*/*") }
    }

    private fun startChooser(p: WebChromeClient.FileChooserParams) {
        try {
            val content = p.createIntent()
            var cam: Intent? = null
            val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (wantsImage(p) && camOk) {
                val dir = File(cacheDir, "photos"); dir.mkdirs()
                val f = File.createTempFile("foto_", ".jpg", dir)
                val u = FileProvider.getUriForFile(this, "de.familienplaner.app.fp", f)
                camUri = u
                cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, u)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val launch: Intent = if (cam != null && p.isCaptureEnabled) cam
                else if (cam != null) Intent.createChooser(content, "Foto aufnehmen oder auswählen").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<android.os.Parcelable>(cam))
                else content
            picker.launch(launch)
        } catch (e: Exception) {
            camUri = null
            fileCb?.onReceiveValue(null); fileCb = null
        }
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
        @JavascriptInterface
        fun setLocShare(on: Boolean) { runOnUiThread { setLocShareUi(on) } }
        // App-Check-Debug-Token (kommt beim Bau aus dem GitHub-Secret FP_AC_DEBUG), nur für die eigene Seite
        @JavascriptInterface
        fun acDebug(): String = BuildConfig.AC_DEBUG
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
                return routeUrl(r.url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val camOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (wantsImage(p) && !camOk) { pendingChooser = p; camPerm.launch(Manifest.permission.CAMERA) } else startChooser(p)
                return true
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
                pop.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        val sch = r.url.scheme ?: ""
                        if (sch != "http" && sch != "https") return routeUrl(r.url)
                        val hh = r.url.host ?: ""
                        if (hh.contains("google.") && (r.url.path ?: "").startsWith("/maps")) return routeUrl(r.url)
                        return false
                    }
                }
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


    // Links ausserhalb der App an Android uebergeben (Maps, intent:, geo:, tel: ...)
    private fun routeUrl(u: Uri): Boolean {
        val sch = (u.scheme ?: "").lowercase()
        val h = (u.host ?: "").lowercase()
        val path = u.path ?: ""
        if (sch == "intent") {
            try {
                val ni = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                ni.addCategory(Intent.CATEGORY_BROWSABLE); ni.component = null; ni.selector = null
                startActivity(ni)
            } catch (e: Exception) {
                try {
                    val fb = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                    if (fb != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fb)))
                } catch (e2: Exception) { }
            }
            return true
        }
        if (sch != "http" && sch != "https") {
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
            return true
        }
        val maps = (h.startsWith("maps.google.") || h == "maps.app.goo.gl" || h == "goo.gl" ||
            ((h == "www.google.com" || h == "google.com" || h.startsWith("www.google.") || h.startsWith("google.")) && path.startsWith("/maps")))
        if (maps) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
            return true
        }
        if (h.endsWith("github.io") || h.endsWith("firebaseapp.com") || h.endsWith("googleapis.com") || h.endsWith("google.com") || h.endsWith("gstatic.com")) return false
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
        return true
    }

    private var locRunning = false
    private fun setLocShareUi(on: Boolean) {
        if (on == locRunning) return
        val i = Intent(this, LocService::class.java)
        if (on) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
            LocService.cb = { l ->
                val js = "window.fpNativeLoc&&window.fpNativeLoc(" + l.latitude + "," + l.longitude + "," + Math.round(l.accuracy) + ")"
                runOnUiThread { try { web.evaluateJavascript(js, null) } catch (e: Exception) { } }
            }
            try { ContextCompat.startForegroundService(this, i); locRunning = true } catch (e: Exception) { }
        } else {
            LocService.cb = null
            stopService(i)
            locRunning = false
        }
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

    override fun onDestroy() {
        if (isFinishing) { LocService.cb = null; try { stopService(Intent(this, LocService::class.java)) } catch (e: Exception) { }; locRunning = false }
        super.onDestroy()
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); web.saveState(o) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
