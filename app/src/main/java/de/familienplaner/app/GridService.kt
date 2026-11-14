package de.familienplaner.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GridService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    class Factory(private val ctx: Context, private val wid: Int) : RemoteViewsFactory {
        private var cells: List<Cell> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() { cells = WData.load(ctx).second }
        override fun onDestroy() {}
        override fun getCount(): Int = cells.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = true

        private fun rowHeightDp(rows: Int): Float {
            return try {
                val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(wid)
                val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                if (maxH <= 0 || rows <= 0) 56f else ((maxH - 84f) / rows).coerceIn(44f, 120f)
            } catch (e: Exception) { 56f }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.cell)
            if (position >= cells.size) return v
            val c = cells[position]
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val isToday = c.d == today

            v.setTextViewText(R.id.cNum, c.d.substring(8).toInt().toString())
            val numColor = when {
                !c.inMonth -> Color.parseColor("#6F7898")
                c.holiday -> Color.parseColor("#FF8A80")
                else -> Color.WHITE
            }
            v.setTextColor(R.id.cNum, numColor)
            v.setInt(R.id.cCell, "setBackgroundResource", when {
                isToday -> R.drawable.cell_today
                c.inMonth -> R.drawable.cell_bg
                else -> R.drawable.cell_out
            })

            val sh = SpannableStringBuilder()
            for ((i, p) in c.shifts.withIndex()) {
                if (i > 0) sh.append(' ')
                val st = sh.length
                sh.append(p.first)
                sh.setSpan(ForegroundColorSpan(p.second), st, sh.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            v.setTextViewText(R.id.cSh, sh)

            val dots = SpannableStringBuilder()
            for (col in c.dots) {
                val st = dots.length
                dots.append('●')
                dots.setSpan(ForegroundColorSpan(col), st, dots.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (col in c.bins) {
                val st = dots.length
                dots.append('■')
                dots.setSpan(ForegroundColorSpan(col), st, dots.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            v.setTextViewText(R.id.cDots, dots)

            if (Build.VERSION.SDK_INT >= 31) {
                v.setViewLayoutHeight(R.id.cCell, rowHeightDp(cells.size / 7), TypedValue.COMPLEX_UNIT_DIP)
            }

            val fill = Intent().putExtra("day", c.d)
            v.setOnClickFillInIntent(R.id.cCell, fill)
            return v
        }
    }
}
