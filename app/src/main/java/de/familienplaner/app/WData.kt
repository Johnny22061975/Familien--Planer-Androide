package de.familienplaner.app

import android.content.Context
import org.json.JSONObject

class Cell(
    val d: String,
    val inMonth: Boolean,
    val shifts: List<Pair<String, Int>>,
    val dots: List<Int>,
    val bins: List<Int>,
    val holiday: Boolean
)

object WData {
    private const val PREF = "fp"

    fun offset(ctx: Context): Int = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("off", 0).coerceIn(-1, 1)

    fun setOffset(ctx: Context, v: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("off", v.coerceIn(-1, 1)).apply()
    }

    private fun color(s: String, fallback: Int): Int = try { android.graphics.Color.parseColor(s) } catch (e: Exception) { fallback }

    /** Liefert Monatsname + Zellen des gewählten Monats (leer, wenn die App noch nichts gemeldet hat). */
    fun load(ctx: Context): Pair<String, List<Cell>> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("months", null) ?: return Pair("", emptyList())
        try {
            val o = JSONObject(raw)
            val months = o.getJSONArray("months")
            val idx = (1 + offset(ctx)).coerceIn(0, months.length() - 1)
            val m = months.getJSONObject(idx)
            val key = m.getString("key")
            val names = arrayOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
            val title = names[key.substring(5, 7).toInt() - 1] + " " + key.substring(0, 4)
            val days = m.getJSONArray("days")
            val out = ArrayList<Cell>()
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val ds = d.getString("d")
                val sh = ArrayList<Pair<String, Int>>()
                val sa = d.getJSONArray("sh")
                for (j in 0 until sa.length()) {
                    val p = sa.getJSONArray(j)
                    sh.add(Pair(p.getString(0), color(p.getString(1), 0xFFCCCCCC.toInt())))
                }
                val dots = ArrayList<Int>()
                val da = d.getJSONArray("dt")
                for (j in 0 until da.length()) dots.add(color(da.getString(j), 0xFFCCCCCC.toInt()))
                val bins = ArrayList<Int>()
                val ba = d.getJSONArray("bn")
                for (j in 0 until ba.length()) bins.add(color(ba.getString(j), 0xFF888888.toInt()))
                out.add(Cell(ds, ds.startsWith(key), sh, dots, bins, d.optInt("h", 0) == 1))
            }
            return Pair(title, out)
        } catch (e: Exception) {
            return Pair("", emptyList())
        }
    }
}
