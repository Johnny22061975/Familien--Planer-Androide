package de.familienplaner.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
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

            val rwIds = intArrayOf(R.id.rw0, R.id.rw1, R.id.rw2, R.id.rw3, R.id.rw4, R.id.rw5)
            val rbIds = intArrayOf(R.id.rb0, R.id.rb1, R.id.rb2, R.id.rb3, R.id.rb4, R.id.rb5)
            val reIds = intArrayOf(R.id.re0, R.id.re1, R.id.re2, R.id.re3, R.id.re4, R.id.re5)
            for (i in 0 until 6) {
                if (i >= c.rows.size) { v.setViewVisibility(rwIds[i], View.GONE); continue }
                val r = c.rows[i]
                v.setViewVisibility(rwIds[i], View.VISIBLE)
                if (r.bins) {
                    v.setViewVisibility(rbIds[i], View.GONE)
                    val sb = SpannableStringBuilder()
                    for (col in r.binColors) {
                        val st = sb.length
                        sb.append('\u25A0')
                        sb.setSpan(ForegroundColorSpan(col), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    v.setTextViewText(reIds[i], sb)
                } else {
                    if (r.code.isNotEmpty()) {
                        v.setViewVisibility(rbIds[i], View.VISIBLE)
                        v.setTextViewText(rbIds[i], r.code)
                        if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(rbIds[i], "setBackgroundTintList", ColorStateList.valueOf(r.color))
                        else v.setInt(rbIds[i], "setBackgroundColor", r.color)
                    } else v.setViewVisibility(rbIds[i], View.GONE)
                    v.setTextViewText(reIds[i], r.emo.joinToString(""))
                }
            }

            if (Build.VERSION.SDK_INT >= 31) {
                v.setViewLayoutHeight(R.id.cCell, rowHeightDp(cells.size / 7), TypedValue.COMPLEX_UNIT_DIP)
            }

            val fill = Intent().putExtra("day", c.d)
            v.setOnClickFillInIntent(R.id.cCell, fill)
            return v
        }
    }
}
