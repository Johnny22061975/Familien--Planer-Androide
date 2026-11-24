package de.familienplaner.app

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

// ---------- Wecker der App: Plan speichern, bei Android anmelden, Schlummern ----------
object FpAlarm {
    const val ACT_FIRE = "de.familienplaner.app.AL_FIRE"
    const val SNOOZE_ID = 999
    val NAMES = mapOf("F" to "Frühschicht", "S" to "Spätschicht", "N" to "Nachtschicht", "TD" to "Tagdienst", "ev" to "Termine", "bin" to "Tonnen", "bd" to "Geburtstage")

    private fun p(c: Context) = c.getSharedPreferences("fp_al", Context.MODE_PRIVATE)
    fun cfg(c: Context): JSONObject = try { JSONObject(p(c).getString("cfg", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun saveCfg(c: Context, j: JSONObject) { p(c).edit().putString("cfg", j.toString()).apply() }
    fun cat(c: Context, k: String): JSONObject = cfg(c).optJSONObject("cats")?.optJSONObject(k) ?: JSONObject()
    fun setCat(c: Context, k: String, f: (JSONObject) -> Unit) {
        val j = cfg(c); val cs = j.optJSONObject("cats") ?: JSONObject(); val o = cs.optJSONObject(k) ?: JSONObject()
        f(o); cs.put(k, o); j.put("cats", cs); saveCfg(c, j)
    }
    fun setSnooze(c: Context, m: Int) { val j = cfg(c); j.put("snooze", m.coerceIn(1, 30)); saveCfg(c, j) }
    fun snoozeMin(c: Context) = cfg(c).optInt("snooze", 5).coerceIn(1, 30)
    fun plan(c: Context): JSONObject = try { JSONObject(p(c).getString("plan", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun catOf(k: String): String {
        val m = Regex("^sh_.+_\\d{4}-\\d{2}-\\d{2}_([A-Z]+)").find(k)
        return m?.groupValues?.get(1) ?: if (k.startsWith("bin_")) "bin" else if (k.startsWith("bd_")) "bd" else "ev"
    }
    fun fired(c: Context, k: String) = p(c).getLong("f_" + k, 0L) > 0L
    fun markFired(c: Context, k: String) { p(c).edit().putLong("f_" + k, System.currentTimeMillis()).apply() }
    // Weiß das Handy schon von diesem Wecker? Dann braucht es die Benachrichtigung vom Server nicht.
    fun known(c: Context, k: String): Boolean {
        if (fired(c, k)) return true
        val a = plan(c).optJSONArray("items") ?: return false
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("k") == k) return true
        return false
    }

    fun setPlan(c: Context, json: String) {
        try { JSONObject(json) } catch (e: Exception) { return }
        p(c).edit().putString("plan", json).apply()
        schedule(c)
    }

    private fun fireIntent(c: Context, k: String, t: String, b: String, cat: String, snz: Boolean) =
        Intent(c, AlarmReceiver::class.java).setAction(ACT_FIRE).putExtra("k", k).putExtra("t", t).putExtra("b", b).putExtra("c", cat).putExtra("snz", snz)

    private fun req(k: String) = 1000 + ((k.hashCode() and 0x7fffffff) % 1000000)

    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun at(c: Context, whenMs: Long, pi: PendingIntent) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (canExact(c)) am.setAlarmClock(AlarmManager.AlarmClockInfo(whenMs, show), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi) } catch (x: Exception) { }
        }
    }

    // Alle Wecker aus dem Plan bei Android anmelden (alte vorher abmelden)
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pr = p(c)
        val old = pr.getString("ids", "") ?: ""
        for (s in old.split(",")) {
            val id = s.toIntOrNull() ?: continue
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java).setAction(ACT_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        val now = System.currentTimeMillis()
        val ids = ArrayList<Int>()
        val a = plan(c).optJSONArray("items") ?: JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("k"); val t = o.optLong("at")
            if (k.isEmpty() || t < now - 30000L || fired(c, k)) continue
            val id = req(k)
            val pi = PendingIntent.getBroadcast(c, id, fireIntent(c, k, o.optString("t"), o.optString("b"), o.optString("c", catOf(k)), false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            at(c, maxOf(t, now + 1000L), pi); ids.add(id)
        }
        // alte Merker für erledigte Wecker aufräumen
        val ed = pr.edit()
        for ((key, v) in pr.all) if (key.startsWith("f_") && v is Long && v < now - 3 * 86400000L) ed.remove(key)
        ed.putString("ids", ids.joinToString(",")).apply()
    }

    fun snooze(c: Context, k: String, t: String, b: String, cat: String) {
        val pi = PendingIntent.getBroadcast(c, SNOOZE_ID, fireIntent(c, k, t, b, cat, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        at(c, System.currentTimeMillis() + snoozeMin(c) * 60000L, pi)
    }

    fun next(c: Context): JSONObject? {
        val now = System.currentTimeMillis()
        val a = plan(c).optJSONArray("items") ?: return null
        var best: JSONObject? = null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("at") > now && !fired(c, o.optString("k")) && (best == null || o.optLong("at") < best.optLong("at"))) best = o
        }
        return best
    }

    fun ring(c: Context, k: String, t: String, b: String, cat: String, snz: Boolean) {
        val i = Intent(c, AlarmService::class.java).putExtra("k", k).putExtra("t", t).putExtra("b", b).putExtra("c", cat).putExtra("snz", snz)
        try { ContextCompat.startForegroundService(c, i) } catch (e: Exception) { }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == FpAlarm.ACT_FIRE) {
            val k = i.getStringExtra("k") ?: return
            val snz = i.getBooleanExtra("snz", false)
            if (!snz && FpAlarm.fired(c, k)) return
            FpAlarm.ring(c, k, i.getStringExtra("t") ?: "Wecker", i.getStringExtra("b") ?: "", i.getStringExtra("c") ?: FpAlarm.catOf(k), snz)
        } else FpAlarm.schedule(c) // nach Neustart oder Update wieder anmelden
    }
}

// ---------- Klingeln: Ton, Vibration, Vollbild, danach auf Wunsch Wetter und Nachrichten ----------
class AlarmService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var wl: PowerManager.WakeLock? = null
    private var vol = 0.25f
    private var k = ""; private var t = ""; private var b = ""; private var cat = ""; private var snz = false
    @Volatile private var brief: String? = null
    private var done = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            ACT_OFF -> { finish(true); return START_NOT_STICKY }
            ACT_SNOOZE -> { finish(false); return START_NOT_STICKY }
        }
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        if (mp != null) stopSound() // ein neuer Wecker löst den alten ab
        done = false; brief = null
        k = i.getStringExtra("k") ?: ""; t = i.getStringExtra("t") ?: "Wecker"; b = i.getStringExtra("b") ?: ""; cat = i.getStringExtra("c") ?: "ev"; snz = i.getBooleanExtra("snz", false)
        if (!k.startsWith("test_")) FpAlarm.markFired(this, k)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(9, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(9, note())
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        try { wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fp:alarm").apply { acquire(10 * 60000L) } } catch (e: Exception) { }
        cur = Info(t, b, FpAlarm.snoozeMin(this))
        startSound(); vibrate()
        // Läuft er 5 Minuten ohne Reaktion, schlummert er von selbst
        h.postDelayed({ finish(false) }, 5 * 60000L)
        if (!snz && FpAlarm.cat(this, cat).optBoolean("brief", false)) {
            Thread { brief = try { Brief.build(this) } catch (e: Exception) { Brief.timeOnly() } }.start()
            h.postDelayed({ startBrief(0) }, 7000L)
        }
        return START_NOT_STICKY
    }

    private fun note(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("fp_alarm", "Wecker", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null); ch.enableVibration(false); ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        val full = PendingIntent.getActivity(this, 11, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getService(this, 12, Intent(this, AlarmService::class.java).setAction(ACT_OFF), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sn = PendingIntent.getService(this, 13, Intent(this, AlarmService::class.java).setAction(ACT_SNOOZE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bl = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fp_alarm") else Notification.Builder(this)
        return bl.setContentTitle(t).setContentText(b.ifEmpty { "Wecker" })
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "Schlummern (" + FpAlarm.snoozeMin(this) + " Min.)", sn).build())
            .addAction(Notification.Action.Builder(null, "Aus", off).build())
            .build()
    }

    private fun toneUri(): Uri? {
        val s = FpAlarm.cat(this, cat).optString("tone")
        if (s.isNotEmpty()) return Uri.parse(s)
        return RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun startSound() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val tries = listOf(toneUri(), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        for (u in tries) {
            if (u == null) continue
            try {
                val m = MediaPlayer()
                m.setAudioAttributes(attrs)
                m.setDataSource(this, u)
                m.isLooping = true
                m.prepare()
                vol = 0.25f; m.setVolume(vol, vol)
                m.start()
                mp = m
                ramp()
                return
            } catch (e: Exception) { }
        }
    }

    // langsam lauter werden
    private fun ramp() {
        h.postDelayed({
            val m = mp ?: return@postDelayed
            if (vol < 1f) { vol = minOf(1f, vol + 0.1f); try { m.setVolume(vol, vol) } catch (e: Exception) { }; ramp() }
        }, 3000L)
    }

    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0)) else vibOld(v)
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun vibOld(v: Vibrator) { v.vibrate(longArrayOf(0, 600, 800), 0) }

    // Wetter und Nachrichten vorlesen, währenddessen ist der Weckton leise; danach klingelt er weiter
    private fun startBrief(waited: Int) {
        if (done || mp == null) return
        val text = brief
        if (text == null && waited < 12) { h.postDelayed({ startBrief(waited + 1) }, 1000L); return }
        val say = text ?: Brief.timeOnly()
        try { mp?.pause() } catch (e: Exception) { }
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { st -> onTts(st, say) })
    }

    private fun onTts(st: Int, say: String) {
            val s = tts
            if (st != TextToSpeech.SUCCESS || s == null || done) { resume(); return }
            try {
                s.setLanguage(Locale.GERMANY)
                s.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                s.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { h.post { resume() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { h.post { resume() } }
                })
                val r = s.speak(say, TextToSpeech.QUEUE_FLUSH, Bundle(), "fpbrief")
                if (r != TextToSpeech.SUCCESS) resume()
            } catch (e: Exception) { resume() }
    }

    private fun resume() { if (done) return; try { mp?.start() } catch (e: Exception) { } }

    private fun stopSound() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        try { mp?.stop() } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        try { (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { }
    }

    private fun finish(off: Boolean) {
        if (done && mp == null) { stopSelf(); return }
        done = true
        h.removeCallbacksAndMessages(null)
        stopSound()
        if (!off && !k.startsWith("test_") && k.isNotEmpty()) FpAlarm.snooze(this, k, t, b, cat)
        cur = null
        try { wl?.release() } catch (e: Exception) { }
        wl = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (!done) { done = true; h.removeCallbacksAndMessages(null); stopSound(); cur = null; try { wl?.release() } catch (e: Exception) { } }; super.onDestroy() }

    data class Info(val title: String, val body: String, val snooze: Int)
    companion object {
        const val ACT_OFF = "de.familienplaner.app.AL_OFF"
        const val ACT_SNOOZE = "de.familienplaner.app.AL_SNOOZE"
        @Volatile var cur: Info? = null
    }
}

// ---------- Vollbild über dem Sperrbildschirm: große Knöpfe "Schlummern" und "Aus" ----------
class AlarmActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val info = AlarmService.cur
        if (info == null) { finish(); return }
        val d = resources.displayMetrics.density
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER
        col.setBackgroundColor(Color.parseColor("#14181f"))
        col.setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        val now = Calendar.getInstance()
        fun tv(text: String, size: Float, c: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor(c)); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        col.addView(tv(String.format(Locale.GERMANY, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)), 64f, "#ffffff", true))
        col.addView(tv(info.title, 26f, "#ffffff", true))
        if (info.body.isNotEmpty()) col.addView(tv(info.body, 18f, "#b9c2cf"))
        fun btn(text: String, bg: String, act: String) = Button(this).apply {
            this.text = text; textSize = 22f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(bg)); setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (76 * d).toInt()).apply { topMargin = (18 * d).toInt() }
            setOnClickListener { startService(Intent(this@AlarmActivity, AlarmService::class.java).setAction(act)); finish() }
        }
        col.addView(btn("Schlummern (" + info.snooze + " Min.)", "#3a4556", AlarmService.ACT_SNOOZE))
        col.addView(btn("Aus", "#d2453b", AlarmService.ACT_OFF))
        setContentView(col)
        watch()
    }
    // schließt sich, sobald der Wecker über die Benachrichtigung ausgeschaltet wurde
    private fun watch() { h.postDelayed({ if (AlarmService.cur == null) finish() else watch() }, 1000L) }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { } // nicht aus Versehen wegdrücken
}

// ---------- Was der Wecker vorliest ----------
object Brief {
    fun timeOnly(): String {
        val c = Calendar.getInstance(); val hh = c.get(Calendar.HOUR_OF_DAY); val mm = c.get(Calendar.MINUTE)
        val g = if (hh < 11) "Guten Morgen." else if (hh < 18) "Hallo." else "Guten Abend."
        return g + " Es ist " + hh + " Uhr" + (if (mm > 0) " " + mm else "") + "."
    }
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "FamilienPlaner/1.0")
        if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun deg(v: Double): String { val r = Math.round(v).toInt(); return if (r < 0) "minus " + (-r) else r.toString() }
    private fun wxText(code: Int): String = when (code) {
        0 -> "klar"; 1 -> "überwiegend klar"; 2 -> "teils bewölkt"; 3 -> "bedeckt"; 45, 48 -> "neblig"
        51, 53, 55, 56, 57 -> "Nieselregen"; 61, 63, 80, 81 -> "Regen"; 65, 82 -> "starker Regen"; 66, 67 -> "gefrierender Regen"
        71, 73, 77, 85 -> "Schnee"; 75, 86 -> "starker Schneefall"; 95, 96, 99 -> "Gewitter"; else -> ""
    }
    private fun clean(s: String) = s.replace(Regex("<!\\[CDATA\\[|]]>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("<[^>]+>"), "").trim()

    fun build(c: Context): String {
        val sb = StringBuilder(timeOnly())
        val plan = FpAlarm.plan(c)
        val wx = plan.optJSONObject("wx")
        if (wx != null && wx.has("lat")) {
            try {
                val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + wx.optDouble("lat") + "&longitude=" + wx.optDouble("lon") +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"))
                val cu = j.getJSONObject("current"); val dy = j.getJSONObject("daily")
                val place = wx.optString("place")
                val desc = wxText(cu.optInt("weather_code", -1))
                sb.append(" Das Wetter").append(if (place.isNotEmpty()) " in $place" else "").append(": gerade ").append(deg(cu.optDouble("temperature_2m"))).append(" Grad")
                if (desc.isNotEmpty()) sb.append(", ").append(desc)
                sb.append(". Heute zwischen ").append(deg(dy.getJSONArray("temperature_2m_min").optDouble(0))).append(" und ").append(deg(dy.getJSONArray("temperature_2m_max").optDouble(0))).append(" Grad")
                val rp = dy.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
                if (rp >= 30) sb.append(", Regenwahrscheinlichkeit ").append(rp).append(" Prozent")
                sb.append(".")
            } catch (e: Exception) { }
        }
        try {
            val x = get("https://www.tagesschau.de/index~rss2.xml")
            val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(x).mapNotNull { m ->
                Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1)?.let { clean(it) }
            }.filter { it.length > 3 }.take(5).toList()
            if (items.isNotEmpty()) sb.append(" Die Schlagzeilen der Tagesschau: ").append(items.joinToString(". ") { it.trimEnd('.') }).append(".")
        } catch (e: Exception) { }
        return sb.toString()
    }
}
