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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("fp_loc", "Standort teilen", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fp_loc") else Notification.Builder(this)
        val n = b.setContentTitle("Familien-Planer")
            .setContentText("Dein Standort wird mit der Familie geteilt")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            // Weggewischt: Benachrichtigung kommt sofort wieder, das Teilen läuft weiter
            .setDeleteIntent(PendingIntent.getService(this, 2, Intent(this, LocService::class.java).setAction(ACT_SHOW), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "Ausschalten", PendingIntent.getService(this, 1, Intent(this, LocService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(7, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (i?.action != ACT_SHOW || lm == null) startUpdates()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        try { m.removeUpdates(listener) } catch (e: Exception) {}
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000L, 30f, listener, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120000L, 0f, listener, Looper.getMainLooper())
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

    private fun upload(l: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSent < 60000L) return
        if (lastSent > 0) {
            val d = FloatArray(1)
            Location.distanceBetween(lastLat, lastLon, l.latitude, l.longitude, d)
            if (d[0] < 30f && now - lastSent < 600000L) return
        }
        lastSent = now; lastLat = l.latitude; lastLon = l.longitude
        val lat = l.latitude; val lon = l.longitude; val acc = Math.round(l.accuracy)
        Thread { try { send(lat, lon, acc, now) } catch (e: Exception) { lastSent = 0L } }.start()
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

    private fun send(lat: Double, lon: Double, acc: Int, ts: Long) {
        write(JSONObject()
            .put("on", JSONObject().put("booleanValue", true))
            .put("lat", JSONObject().put("doubleValue", Math.round(lat * 100000.0) / 100000.0))
            .put("lon", JSONObject().put("doubleValue", Math.round(lon * 100000.0) / 100000.0))
            .put("acc", JSONObject().put("integerValue", acc.toString()))
            .put("ts", JSONObject().put("integerValue", ts.toString()))
            .put("src", JSONObject().put("stringValue", "app")))
    }

    private fun write(f: JSONObject) {
        val p = getSharedPreferences("fp", MODE_PRIVATE)
        val a = JSONObject(p.getString("loc_auth", null) ?: return)
        val key = a.optString("apiKey"); val proj = a.optString("projectId"); val me = a.optString("me")
        var rt = p.getString("loc_rt", null) ?: a.optString("refreshToken")
        if (key.isEmpty() || proj.isEmpty() || me.isEmpty() || rt.isEmpty()) return
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
        val name = "projects/" + proj + "/databases/(default)/documents/settings/loc_" + me
        val body = JSONObject().put("writes", JSONArray().put(JSONObject().put("update", JSONObject().put("name", name).put("fields", f))))
        try {
            http("https://firestore.googleapis.com/v1/projects/" + proj + "/databases/(default)/documents:commit", body.toString(), "application/json", h)
        } catch (e: Exception) { idTok = ""; throw e }
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        @Volatile var cb: ((Location) -> Unit)? = null
        const val ORIGIN = "https://johnny22061975.github.io/"
        const val ACT_STOP = "de.familienplaner.app.LOC_STOP"
        const val ACT_SHOW = "de.familienplaner.app.LOC_SHOW"
    }
}
