package de.familienplaner.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.grid)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        mgr.updateAppWidget(id, build(ctx, id))
        mgr.notifyAppWidgetViewDataChanged(id, R.id.grid)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACT_PREV -> { WData.setOffset(ctx, WData.offset(ctx) - 1); refresh(ctx) }
            ACT_NEXT -> { WData.setOffset(ctx, WData.offset(ctx) + 1); refresh(ctx) }
            ACT_TODAY -> { WData.setOffset(ctx, 0); refresh(ctx) }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACT_PREV = "de.familienplaner.app.PREV"
        const val ACT_NEXT = "de.familienplaner.app.NEXT"
        const val ACT_TODAY = "de.familienplaner.app.TODAY"

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.grid)
        }

        private fun bc(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, WidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun build(ctx: Context, id: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val title = WData.load(ctx).first
            v.setTextViewText(R.id.wTitle, if (title.isEmpty()) "Familien-Planer" else title)
            v.setOnClickPendingIntent(R.id.wPrev, bc(ctx, ACT_PREV, 1))
            v.setOnClickPendingIntent(R.id.wNext, bc(ctx, ACT_NEXT, 2))
            v.setOnClickPendingIntent(R.id.wTitle, bc(ctx, ACT_TODAY, 3))

            val svc = Intent(ctx, GridService::class.java)
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.grid, svc)

            val tpl = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, tpl, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setPendingIntentTemplate(R.id.grid, pi)
            return v
        }
    }
}
