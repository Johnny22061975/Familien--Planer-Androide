package de.familienplaner.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Firebase für die Android-App (Werte aus google-services.json der Android-App "de.familienplaner.app")
object FpFire {
    const val APP_ID = "1:298526562858:android:0d159b884dc797b1eec153"
    const val API_KEY = "AIzaSyAFznqFqiVLPe6hmeUYimG-ZpFudRLwHds"
    const val PROJECT = "familien-planner"
    const val SENDER = "298526562858"

    fun init(ctx: Context): Boolean {
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return true
        return try {
            FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                .setApplicationId(APP_ID).setApiKey(API_KEY).setProjectId(PROJECT).setGcmSenderId(SENDER).build())
            true
        } catch (e: Exception) { false }
    }
}

class FpApp : Application() {
    override fun onCreate() { super.onCreate(); FpFire.init(this) }
}

// Empfängt die Benachrichtigungen (Chat, Erinnerungen, Wecker) auch bei geschlossener App
class FpMsgService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putString("push_tok", token).apply()
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        val title = d["title"] ?: m.notification?.title ?: "Familien-Planer"
        val body = d["body"] ?: m.notification?.body ?: ""
        val kind = d["kind"] ?: ""
        val tag = d["tag"] ?: ("fp_" + System.currentTimeMillis())
        // Für die Prüfung in der App: wann zuletzt etwas angekommen ist
        try { getSharedPreferences("fp", Context.MODE_PRIVATE).edit().putString("rx_last", JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("title", title.take(60)).toString()).apply() } catch (e: Exception) { }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = if (kind == "chat") "fp_chat" else "fp_rem"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(ch, if (kind == "chat") "Chat-Nachrichten" else "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fp_push", kind)
        val pi = PendingIntent.getActivity(this, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        val n = b.setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(if (kind == "chat") android.R.drawable.sym_action_chat else android.R.drawable.ic_popup_reminder)
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(tag, 1, n) } catch (e: Exception) { }
        // Empfangsbestätigung an den Helfer (wie im Browser), damit die Absenderin "angekommen" sieht
        val url = d["ack"] ?: ""; val mid = d["mid"] ?: ""; val me = d["me"] ?: ""
        if (kind == "chat") {
            val p = getSharedPreferences("fp", Context.MODE_PRIVATE)
            if (mid.isNotEmpty() && me.isNotEmpty() && Regex("^https://[^/]+\\.workers\\.dev$").matches(url)) {
                Thread {
                    val res = try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 15000
                        c.setRequestProperty("Content-Type", "text/plain")
                        c.outputStream.use { it.write(JSONObject().put("ack", mid).put("me", me).toString().toByteArray()) }
                        "Antwort " + c.responseCode
                    } catch (e: Exception) { "Fehler: " + (e.message ?: "").take(80) }
                    p.edit().putString("ack_diag", JSONObject().put("t", System.currentTimeMillis()).put("me", me).put("mid", mid).put("res", res).toString()).apply()
                }.start()
            } else {
                p.edit().putString("ack_diag", JSONObject().put("t", System.currentTimeMillis()).put("me", me).put("mid", mid).put("res", "nicht gesendet").toString()).apply()
            }
        }
    }
}
