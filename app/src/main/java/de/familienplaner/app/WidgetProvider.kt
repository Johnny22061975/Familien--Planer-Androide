package de.familienplaner.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, build(ctx))
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val title = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date())
            var body = "Öffne die App, damit hier die Termine erscheinen."
            try {
                val raw = ctx.getSharedPreferences("fp", Context.MODE_PRIVATE).getString("today", null)
                if (raw != null) {
                    val o = JSONObject(raw)
                    if (o.getString("d") == today) {
                        val a = o.getJSONArray("e")
                        body = if (a.length() == 0) "Heute keine Termine." else (0 until a.length()).joinToString("\n") { i ->
                            val e = a.getJSONObject(i)
                            val t = e.optString("t", "")
                            (if (t.isEmpty()) "•" else t) + "  " + e.optString("n", "")
                        }
                    }
                }
            } catch (_: Exception) { }
            v.setTextViewText(R.id.wTitle, title)
            v.setTextViewText(R.id.wBody, body)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.wRoot, pi)
            return v
        }
    }
}
