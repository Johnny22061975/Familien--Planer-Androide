package de.familienplaner.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Update-Knopf: Die App fragt bei GitHub (Releases des Android-Repos), ob es eine neuere Version gibt,
// lädt sie auf Wunsch herunter und startet die Installation. Kein GitHub-Konto und keine Dateien nötig.
object Updater {
    private const val API = "https://api.github.com/repos/johnny22061975/Familien--Planer-Androide/releases/latest"
    @Volatile private var busy = false

    fun check(a: Activity, manual: Boolean, done: ((String) -> Unit)? = null) {
        if (busy) return
        val p = a.getSharedPreferences("fp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - p.getLong("upd_check", 0L) < 3 * 3600 * 1000L) return // höchstens alle 3 Stunden von selbst
        busy = true
        Thread {
            var msg = ""
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val code = c.responseCode
                if (code != 200) throw Exception(if (code == 404) "Keine Update-Quelle gefunden" else "GitHub antwortet " + code)
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                p.edit().putLong("upd_check", now).apply()
                val tag = j.optString("tag_name")
                val ver = tag.removePrefix("v").toIntOrNull() ?: 0
                var url = ""; var size = 0L
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val x = assets.getJSONObject(i)
                    if (x.optString("name").endsWith(".apk")) { url = x.optString("browser_download_url"); size = x.optLong("size"); break }
                }
                if (ver > BuildConfig.VERSION_CODE && url.isNotEmpty()) {
                    a.runOnUiThread { offer(a, ver, url, size) }
                    msg = "Neue Version " + ver + " gefunden"
                } else msg = "Die App ist aktuell (Version " + BuildConfig.VERSION_CODE + ")"
            } catch (e: Exception) { msg = "Update-Prüfung nicht möglich: " + (e.message ?: "Fehler").take(80) }
            busy = false
            if (done != null) a.runOnUiThread { done(msg) }
        }.start()
    }

    private fun offer(a: Activity, ver: Int, url: String, size: Long) {
        if (a.isFinishing) return
        val mb = if (size > 0) String.format(java.util.Locale.GERMANY, " (%.0f MB)", size / 1048576.0) else ""
        AlertDialog.Builder(a)
            .setTitle("Neue App-Version")
            .setMessage("Version $ver ist da$mb. Jetzt herunterladen und installieren? Deine Daten und die Anmeldung bleiben erhalten.")
            .setPositiveButton("Installieren") { _, _ -> download(a, url) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun download(a: Activity, url: String) {
        // Android fragt einmal, ob der Familien-Planer Apps installieren darf
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Einmal erlauben")
                .setMessage("Damit sich die App selbst aktualisieren kann, im nächsten Fenster „Dieser Quelle vertrauen“ bzw. „Erlauben“ einschalten. Danach zurück und noch einmal auf Installieren tippen.")
                .setPositiveButton("Weiter") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) } catch (e: Exception) { }
                }
                .setNegativeButton("Abbrechen", null).show()
            return
        }
        val dlg = AlertDialog.Builder(a).setTitle("Lade neue Version …").setMessage("0 %").setCancelable(false).show()
        Thread {
            try {
                val dir = File(a.cacheDir, "updates"); dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "familien-planer.apk")
                var c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                var hops = 0
                while (c.responseCode in 301..308 && hops < 5) { // Weiterleitung zum Download-Server
                    val loc = c.getHeaderField("Location") ?: break
                    c = URL(loc).openConnection() as HttpURLConnection; c.connectTimeout = 20000; c.readTimeout = 30000; hops++
                }
                if (c.responseCode != 200) throw Exception("Download " + c.responseCode)
                val total = c.contentLengthLong
                c.inputStream.use { ins -> f.outputStream().use { out ->
                    val buf = ByteArray(65536); var got = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else -1
                        if (pct != last && pct >= 0) { last = pct; a.runOnUiThread { try { dlg.setMessage("$pct %") } catch (e: Exception) { } } }
                    }
                } }
                a.runOnUiThread {
                    try { dlg.dismiss() } catch (e: Exception) { }
                    val u = FileProvider.getUriForFile(a, "de.familienplaner.app.fp", f)
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { a.startActivity(i) } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    try { dlg.dismiss() } catch (x: Exception) { }
                    AlertDialog.Builder(a).setTitle("Update hat nicht geklappt").setMessage((e.message ?: "Fehler").take(120) + "\nBitte später noch einmal versuchen.").setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
}
