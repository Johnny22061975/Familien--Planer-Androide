package de.familienplaner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class StopEx : Exception()

class LocService : Service() {
    private var lm: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { cb?.invoke(l); upload(l) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        // Knopf "Ausschalten" in der Benachrichtigung: Teilen beenden und der Familie "aus" melden
        if (i?.action == ACT_STOP) {
            try { lm?.removeUpdates(listener) } catch (e: Exception) {}
            Thread { try { write(JSONObject().put("on", JSONObject().put("booleanValue", false)).put("ts", JSONObject().put("integerValue", System.currentTimeMillis().toString()))) } catch (e: Exception) {} }.start()
            cb = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Von einem Administrator ausgeschaltet: beenden, ohne etwas zu schreiben
        if (i?.action == ACT_HALT) { halt(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("fp_loc", "Standort teilen", NotificationManager.IMPORTANCE_LOW))
        }
        val n = note("Dein Standort wird mit der Familie geteilt")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(7, n)
        } catch (e: Exception) {
            // Android lässt den Start aus dem Hintergrund nicht zu: Hinweis zum Antippen
            if (i?.action == ACT_REMOTE) tapHint()
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        if (lastChk == 0L) lastChk = System.currentTimeMillis() // erste Prüfung nach einer Minute
        if (i?.action != ACT_SHOW || lm == null) startUpdates()
        return START_STICKY
    }

    // Benachrichtigung; der Text zeigt, wann zuletzt gesendet wurde (oder warum es nicht klappt)
    private fun note(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fp_loc") else Notification.Builder(this)
        return b.setContentTitle("Familien-Planer · Standort geteilt")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            // Weggewischt: Benachrichtigung kommt sofort wieder, das Teilen läuft weiter
            .setDeleteIntent(PendingIntent.getService(this, 2, Intent(this, LocService::class.java).setAction(ACT_SHOW), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "Ausschalten", PendingIntent.getService(this, 1, Intent(this, LocService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun tapHint() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("fp_rem", "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fp_rem") else Notification.Builder(this)
            nm.notify(8, b.setContentTitle("📍 Standort teilen").setContentText("Tippe hier, damit das Teilen startet.").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }

    private fun halt() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) {}
        cb = null; running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun status(text: String) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7, note(text)) } catch (e: Exception) {}
    }

    @Suppress("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        try { m.removeUpdates(listener) } catch (e: Exception) {}
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) { }
    }

    // ---- Standort direkt an Firebase senden, auch wenn die App geschlossen ist ----
    private var lastSent = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var idTok = ""
    private var idExp = 0L
    private var acTok = ""
    private var acExp = 0L
    private var lastChk = 0L
    private var lastGps = 0L

    private fun upload(l: Location) {
        val now = System.currentTimeMillis()
        // GPS ist genauer: ungenaue Funknetz-Werte nicht nehmen, solange GPS frische Werte liefert
        if (l.provider == LocationManager.GPS_PROVIDER) lastGps = now
        else if (now - lastGps < 20000L) return
        if (now - lastSent < 5000L) return // höchstens alle 5 Sekunden
        if (lastSent > 0) {
            val d = FloatArray(1)
            Location.distanceBetween(lastLat, lastLon, l.latitude, l.longitude, d)
            if (d[0] < 15f && now - lastSent < 120000L) return // steht still: nur alle 2 Minuten senden
        }
        lastSent = now; lastLat = l.latitude; lastLon = l.longitude
        val lat = l.latitude; val lon = l.longitude; val acc = Math.round(l.accuracy)
        Thread {
            try {
                var off = false
                if (now - lastChk > 60000L) { lastChk = now; off = stillOn() == false }
                if (off) { android.os.Handler(Looper.getMainLooper()).post { halt() }; throw StopEx() }
                val ok = send(lat, lon, acc, now)
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
                status(if (ok) "Zuletzt gesendet um $t" else "Nicht gesendet: App einmal öffnen")
            } catch (e: StopEx) { } catch (e: Exception) { lastSent = 0L; status("Senden klappt nicht: " + (e.message ?: "Fehler").take(60)) }
        }.start()
    }

    private fun http(url: String, body: String, type: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", type)
        c.setRequestProperty("Referer", ORIGIN)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) throw Exception("HTTP " + code + ": " + txt.take(200))
        return txt
    }

    private fun send(lat: Double, lon: Double, acc: Int, ts: Long): Boolean {
        return write(JSONObject()
            .put("lat", JSONObject().put("doubleValue", Math.round(lat * 100000.0) / 100000.0))
            .put("lon", JSONObject().put("doubleValue", Math.round(lon * 100000.0) / 100000.0))
            .put("acc", JSONObject().put("integerValue", acc.toString()))
            .put("ts", JSONObject().put("integerValue", ts.toString()))
            .put("src", JSONObject().put("stringValue", "app")), listOf("lat", "lon", "acc", "ts", "src"))
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 20000
        c.setRequestProperty("Referer", ORIGIN)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) throw Exception("HTTP " + code + ": " + txt.take(200))
        return txt
    }

    // Ist das Teilen noch eingeschaltet? (null = unbekannt, dann weitermachen)
    private fun stillOn(): Boolean? {
        return try {
            val a = auth() ?: return null
            val j = JSONObject(get("https://firestore.googleapis.com/v1/projects/" + a.first + "/databases/(default)/documents/settings/loc_" + a.second, a.third))
            val on = j.optJSONObject("fields")?.optJSONObject("on") ?: return null
            on.optBoolean("booleanValue", true)
        } catch (e: Exception) { if ((e.message ?: "").startsWith("HTTP 404")) false else null }
    }

    // Anmeldung: Projekt, Name, Kopfzeilen (Token und App-Check)
    private fun auth(): Triple<String, String, Map<String, String>>? {
        val p = getSharedPreferences("fp", MODE_PRIVATE)
        val a = JSONObject(p.getString("loc_auth", null) ?: return null)
        val key = a.optString("apiKey"); val proj = a.optString("projectId"); val me = a.optString("me")
        var rt = p.getString("loc_rt", null) ?: a.optString("refreshToken")
        if (key.isEmpty() || proj.isEmpty() || me.isEmpty() || rt.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (idTok.isEmpty() || now > idExp) {
            val r = JSONObject(http("https://securetoken.googleapis.com/v1/token?key=" + key,
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), "application/x-www-form-urlencoded"))
            idTok = r.getString("id_token"); idExp = now + (r.optLong("expires_in", 3600L) - 300L) * 1000L
            val nrt = r.optString("refresh_token"); if (nrt.isNotEmpty() && nrt != rt) { rt = nrt; p.edit().putString("loc_rt", nrt).apply() }
        }
        val h = HashMap<String, String>(); h["Authorization"] = "Bearer " + idTok
        val appId = a.optString("appId")
        if (BuildConfig.AC_DEBUG.isNotEmpty() && appId.isNotEmpty()) {
            try {
                if (acTok.isEmpty() || now > acExp) {
                    val r = JSONObject(http("https://firebaseappcheck.googleapis.com/v1/projects/" + proj + "/apps/" + appId + ":exchangeDebugToken?key=" + key,
                        JSONObject().put("debugToken", BuildConfig.AC_DEBUG).toString(), "application/json"))
                    acTok = r.getString("token")
                    val ttl = r.optString("ttl", "3600s").removeSuffix("s").toDoubleOrNull() ?: 3600.0
                    acExp = now + ((ttl - 300.0) * 1000.0).toLong()
                }
                h["X-Firebase-AppCheck"] = acTok
            } catch (e: Exception) { }
        }
        return Triple(proj, me, h)
    }

    private fun write(f: JSONObject, mask: List<String>? = null): Boolean {
        val a = auth() ?: return false
        val proj = a.first; val me = a.second; val h = a.third
        val name = "projects/" + proj + "/databases/(default)/documents/settings/loc_" + me
        val w = JSONObject().put("update", JSONObject().put("name", name).put("fields", f))
        if (mask != null) w.put("updateMask", JSONObject().put("fieldPaths", JSONArray(mask)))
        val body = JSONObject().put("writes", JSONArray().put(w))
        try {
            http("https://firestore.googleapis.com/v1/projects/" + proj + "/databases/(default)/documents:commit", body.toString(), "application/json", h)
        } catch (e: Exception) { idTok = ""; throw e }
        return true
    }

    override fun onDestroy() {
        running = false
        try { lm?.removeUpdates(listener) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        @Volatile var cb: ((Location) -> Unit)? = null
        @Volatile var running = false
        const val ACT_REMOTE = "de.familienplaner.app.LOC_REMOTE"
        const val ACT_HALT = "de.familienplaner.app.LOC_HALT"
        const val ORIGIN = "https://johnny22061975.github.io/"
        const val ACT_STOP = "de.familienplaner.app.LOC_STOP"
        const val ACT_SHOW = "de.familienplaner.app.LOC_SHOW"
    }
}
