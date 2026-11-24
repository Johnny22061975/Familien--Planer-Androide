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
import com.google.firebase.messaging.FirebaseMessaging
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

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
    // Benachrichtigungen (Android 13+ fragt einmal nach Erlaubnis)
    private val notifPermL = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply(); pushToken() }
    private fun notifOk(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun notifPerm(): String = if (notifOk()) "granted" else if (getSharedPreferences("fp", Context.MODE_PRIVATE).getBoolean("notif_asked", false)) "denied" else "default"
    private fun pushJs(tok: String?) {
        val js = "window.fpNativePush&&window.fpNativePush(" + org.json.JSONObject.quote(tok ?: "") + "," + org.json.JSONObject.quote(notifPerm()) + ")"
        runOnUiThread { try { web.evaluateJavascript(js, null) } catch (e: Exception) { } }
    }
    private fun pushToken() {
        if (!notifOk() || !FpFire.init(this)) { pushJs(null); return }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                val tok = if (t.isSuccessful) t.result else null
                if (tok != null) getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putString("push_tok", tok).apply()
                pushJs(tok)
            }
        } catch (e: Exception) { pushJs(null) }
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
        // Anmeldedaten für das Senden im Hintergrund (Firebase-Konfiguration, Anmelde-Token, Name)
        @JavascriptInterface
        fun setLocAuth(json: String) {
            val p = getSharedPreferences("fp", Context.MODE_PRIVATE).edit()
            if (json.isEmpty()) p.remove("loc_auth").remove("loc_rt") else p.putString("loc_auth", json).remove("loc_rt")
            p.apply()
        }
        // Push: Erlaubnis holen (ask) und Gerätekennung an die Web-App melden (window.fpNativePush)
        @JavascriptInterface
        fun pushStart(ask: Boolean) { runOnUiThread { if (ask && !notifOk() && Build.VERSION.SDK_INT >= 33) notifPermL.launch(Manifest.permission.POST_NOTIFICATIONS) else pushToken() } }
        @JavascriptInterface
        fun notifPerm(): String = this@MainActivity.notifPerm()
        @JavascriptInterface
        fun pushOff() { try { if (FpFire.init(this@MainActivity)) FirebaseMessaging.getInstance().deleteToken() } catch (e: Exception) { } }
        // Prüfung Push: erlaubt?, Kennung vorhanden?, zuletzt empfangen?
        @JavascriptInterface
        fun pushDiag(): String {
            val p = getSharedPreferences("fp", Context.MODE_PRIVATE)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = if (Build.VERSION.SDK_INT >= 26) nm.notificationChannels.filter { it.id.startsWith("fp_") && it.id != "fp_loc" }.map { it.id + ":" + it.importance } else emptyList()
            return org.json.JSONObject().put("allowed", nm.areNotificationsEnabled()).put("tok", (p.getString("push_tok", "") ?: "").take(8))
                .put("rx", p.getString("rx_last", "") ?: "").put("ch", org.json.JSONArray(ch)).put("ver", BuildConfig.VERSION_CODE).toString()
        }
        @JavascriptInterface
        fun ackDiag(): String = getSharedPreferences("fp", Context.MODE_PRIVATE).getString("ack_diag", "") ?: ""
        // Kassenbon direkt auf dem Handy lesen (ML Kit, offline). Ergebnis an window.fpNativeOcr(id, {ok, lines:[{t,x,y,h}]})
        @JavascriptInterface
        fun ocr(id: String, b64: String) {
            val send = { j: org.json.JSONObject -> runOnUiThread { try { web.evaluateJavascript("window.fpNativeOcr&&window.fpNativeOcr(" + org.json.JSONObject.quote(id) + "," + j.toString() + ")", null) } catch (e: Exception) { } } }
            Thread {
                try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("Bild")
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { t ->
                            val arr = org.json.JSONArray()
                            for (b in t.textBlocks) for (l in b.lines) {
                                val r = l.boundingBox
                                arr.put(org.json.JSONObject().put("t", l.text).put("x", r?.left ?: 0).put("y", r?.top ?: 0).put("h", r?.height() ?: 0))
                            }
                            send(org.json.JSONObject().put("ok", true).put("lines", arr))
                        }
                        .addOnFailureListener { e -> send(org.json.JSONObject().put("ok", false).put("err", (e.message ?: "Fehler").take(100))) }
                } catch (e: Exception) { send(org.json.JSONObject().put("ok", false).put("err", (e.message ?: "Fehler").take(100))) }
            }.start()
        }
        // Update-Knopf in der Web-App: neue App-Version suchen (Ergebnis an window.fpUpdMsg)
        @JavascriptInterface
        fun appUpdate() { runOnUiThread { Updater.check(this@MainActivity, true) { m -> try { web.evaluateJavascript("window.fpUpdMsg&&window.fpUpdMsg(" + org.json.JSONObject.quote(m) + ")", null) } catch (e: Exception) { } } } }
        @JavascriptInterface
        fun appVersion(): Int = BuildConfig.VERSION_CODE
        // App-Check-Debug-Token (kommt beim Bau aus dem GitHub-Secret FP_AC_DEBUG), nur für die eigene Seite
        @JavascriptInterface
        fun acDebug(): String = BuildConfig.AC_DEBUG

        // ---- Wecker ----
        @JavascriptInterface
        fun setAlarms(json: String) { FpAlarm.setPlan(applicationContext, json) }
        @JavascriptInterface
        fun alarmCfg(): String {
            val c = applicationContext
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val perm = org.json.JSONObject().put("exact", FpAlarm.canExact(c)).put("notif", nm.areNotificationsEnabled())
                .put("fsi", if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true)
            val j = FpAlarm.cfg(c)
            return org.json.JSONObject().put("snooze", FpAlarm.snoozeMin(c)).put("cats", j.optJSONObject("cats") ?: org.json.JSONObject())
                .put("perm", perm).put("next", FpAlarm.next(c) ?: org.json.JSONObject()).toString()
        }
        @JavascriptInterface
        fun setAlarmCfg(json: String) {
            try {
                val o = org.json.JSONObject(json)
                if (o.has("snooze")) FpAlarm.setSnooze(applicationContext, o.optInt("snooze", 5))
                val cat = o.optString("cat")
                if (cat.isNotEmpty() && FpAlarm.NAMES.containsKey(cat) && o.has("brief")) FpAlarm.setCat(applicationContext, cat) { it.put("brief", o.optBoolean("brief")) }
            } catch (e: Exception) { }
        }
        @JavascriptInterface
        fun alarmTest(cat: String) {
            val c = if (FpAlarm.NAMES.containsKey(cat)) cat else "ev"
            runOnUiThread {
                FpAlarm.ring(this@MainActivity, "test_" + System.currentTimeMillis(), "⏰ Probe-Wecker", FpAlarm.NAMES[c] ?: "", c, false)
                web.postDelayed({ try { startActivity(Intent(this@MainActivity, AlarmActivity::class.java)) } catch (e: Exception) { } }, 600)
            }
        }
        @JavascriptInterface
        fun pickTone(cat: String) { if (FpAlarm.NAMES.containsKey(cat)) runOnUiThread { pickToneUi(cat) } }
        @JavascriptInterface
        fun alarmPerm(which: String) { runOnUiThread { alarmPermUi(which) } }
        // Standort "Immer erlauben": nötig, damit ein Administrator das Teilen bei geschlossener App starten kann
        @JavascriptInterface
        fun bgLoc(): String {
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) return "none"
            if (Build.VERSION.SDK_INT < 29) return "ok"
            return if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) "ok" else "no"
        }
        @JavascriptInterface
        fun askBgLoc() { runOnUiThread {
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) { perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return@runOnUiThread }
            if (Build.VERSION.SDK_INT >= 29) {
                try { ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 7) }
                catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))) } catch (x: Exception) { } }
            }
        } }
    }

    // Weckton aussuchen (Android-Auswahl mit Weck- und Klingeltönen)
    private var toneCat = ""
    private val tonePick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cat = toneCat; toneCat = ""
        if (r.resultCode != Activity.RESULT_OK || cat.isEmpty()) return@registerForActivityResult
        @Suppress("DEPRECATION")
        val u: Uri? = r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val title = try { if (u != null) android.media.RingtoneManager.getRingtone(this, u)?.getTitle(this) ?: "" else "" } catch (e: Exception) { "" }
        FpAlarm.setCat(applicationContext, cat) { o -> if (u == null) { o.remove("tone"); o.remove("title") } else { o.put("tone", u.toString()); o.put("title", title.take(40)) } }
        try { web.evaluateJavascript("window.fpAlarmCfg&&window.fpAlarmCfg()", null) } catch (e: Exception) { }
    }
    private fun pickToneUi(cat: String) {
        toneCat = cat
        val cur = FpAlarm.cat(applicationContext, cat).optString("tone")
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Weckton: " + (FpAlarm.NAMES[cat] ?: ""))
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (cur.isNotEmpty()) Uri.parse(cur) else android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
        try { tonePick.launch(i) } catch (e: Exception) { toneCat = "" }
    }
    private fun alarmPermUi(which: String) {
        val pkg = Uri.parse("package:" + packageName)
        val i = when {
            which == "exact" && Build.VERSION.SDK_INT >= 31 -> Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            which == "fsi" && Build.VERSION.SDK_INT >= 34 -> Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            Build.VERSION.SDK_INT >= 26 -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try { startActivity(i) } catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (x: Exception) { } }
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
        Updater.check(this, false)
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

    private fun setLocShareUi(on: Boolean) {
        if (on == LocService.running && (!on || LocService.cb != null)) return
        val i = Intent(this, LocService::class.java)
        if (on) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
            LocService.cb = { l ->
                val js = "window.fpNativeLoc&&window.fpNativeLoc(" + l.latitude + "," + l.longitude + "," + Math.round(l.accuracy) + ")"
                runOnUiThread { try { web.evaluateJavascript(js, null) } catch (e: Exception) { } }
            }
            try { ContextCompat.startForegroundService(this, i) } catch (e: Exception) { }
        } else {
            LocService.cb = null
            stopService(i)
            LocService.running = false
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
        // Der Standort-Dienst läuft weiter, auch wenn die App geschlossen wird. Er endet nur über den Schalter.
        LocService.cb = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // zurück aus den Android-Einstellungen: Wecker-Anzeige auffrischen
        try { if (pageReady) web.evaluateJavascript("window.fpAlarmCfg&&window.fpAlarmCfg()", null) } catch (e: Exception) { }
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); web.saveState(o) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
