package de.familienplaner.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

// Eigene Symbole statt Handy-Emojis: ein Bogen mit allen Bildern (assets/emo_sheet.png), Reihenfolge in emo_keys.txt
object EmoIcons {
    @Volatile private var map: Map<String, Bitmap>? = null
    private fun key(e: String) = e.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }
    fun get(ctx: Context, e: String): Bitmap? {
        var m = map
        if (m == null) {
            m = try {
                val keys = ctx.assets.open("emo_keys.txt").bufferedReader().use { it.readLines() }
                val sh = ctx.assets.open("emo_sheet.png").use { BitmapFactory.decodeStream(it) }
                val out = HashMap<String, Bitmap>()
                keys.forEachIndexed { i, k -> if (k.isNotBlank()) out[k.trim()] = Bitmap.createBitmap(sh, (i % 16) * 40, (i / 16) * 40, 40, 40) }
                out
            } catch (e: Exception) { HashMap() }
            map = m
        }
        return m?.get(key(e))
    }
}

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
                    val riIds = arrayOf(intArrayOf(R.id.ri0_0, R.id.ri0_1, R.id.ri0_2), intArrayOf(R.id.ri1_0, R.id.ri1_1, R.id.ri1_2), intArrayOf(R.id.ri2_0, R.id.ri2_1, R.id.ri2_2),
                        intArrayOf(R.id.ri3_0, R.id.ri3_1, R.id.ri3_2), intArrayOf(R.id.ri4_0, R.id.ri4_1, R.id.ri4_2), intArrayOf(R.id.ri5_0, R.id.ri5_1, R.id.ri5_2))[i]
                    val rest = StringBuilder(); var n = 0
                    for (e in r.emo) {
                        val bm = if (n < 3) EmoIcons.get(ctx, e) else null
                        if (bm != null) { v.setImageViewBitmap(riIds[n], bm); v.setViewVisibility(riIds[n], View.VISIBLE); n++ } else rest.append(e)
                    }
                    for (j in n until 3) v.setViewVisibility(riIds[j], View.GONE)
                    v.setTextViewText(reIds[i], rest.toString())
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
