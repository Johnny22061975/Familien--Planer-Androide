package de.familienplaner.app

import android.content.Context
import org.json.JSONObject

class Row(val bins: Boolean, val code: String, val color: Int, val emo: List<String>, val binColors: List<Int>)

class Cell(
    val d: String,
    val inMonth: Boolean,
    val rows: List<Row>,
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
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("months", null) ?: return plain(ctx)
        try {
            val o = JSONObject(raw)
            val months = o.getJSONArray("months")
            val idx = (1 + offset(ctx)).coerceIn(0, months.length() - 1)
            val m = months.getJSONObject(idx)
            val key = m.getString("key")
            val names = arrayOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
            val title = names[key.substring(5, 7).toInt() - 1] + " " + key.substring(0, 4)
            val kinds = m.getJSONArray("rows")
            val rcs = m.getJSONArray("rc")
            val days = m.getJSONArray("days")
            val out = ArrayList<Cell>()
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val ds = d.getString("d")
                val ra = d.getJSONArray("r")
                val rows = ArrayList<Row>()
                for (j in 0 until ra.length()) {
                    val r = ra.getJSONObject(j)
                    if (kinds.getString(j) == "b") {
                        val bl = ArrayList<Int>()
                        val ba = r.getJSONArray("b")
                        for (q in 0 until ba.length()) bl.add(color(ba.getString(q), 0xFF888888.toInt()))
                        rows.add(Row(true, "", 0, emptyList(), bl))
                    } else {
                        val el = ArrayList<String>()
                        val ea = r.getJSONArray("e")
                        for (q in 0 until ea.length()) el.add(ea.getString(q))
                        rows.add(Row(false, r.optString("c", ""), color(rcs.getString(j), 0xFF888888.toInt()), el, emptyList()))
                    }
                }
                out.add(Cell(ds, ds.startsWith(key), rows, d.optInt("h", 0) == 1))
            }
            return Pair(title, out)
        } catch (e: Exception) {
            return plain(ctx)
        }
    }

    /** Reiner Kalender ohne Daten, solange die App noch nichts gemeldet hat. */
    private fun plain(ctx: Context): Pair<String, List<Cell>> {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.add(java.util.Calendar.MONTH, offset(ctx))
        val y = c.get(java.util.Calendar.YEAR)
        val mo = c.get(java.util.Calendar.MONTH)
        val names = arrayOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        val key = String.format("%04d-%02d", y, mo + 1)
        val lead = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(java.util.Calendar.DAY_OF_MONTH, -lead)
        val tt = java.util.Calendar.getInstance(); tt.set(y, mo, 1)
        val len = tt.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val rows = (lead + len + 6) / 7
        val out = ArrayList<Cell>()
        for (i in 0 until rows * 7) {
            val ds = String.format("%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            out.add(Cell(ds, ds.startsWith(key), emptyList(), false))
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return Pair(names[mo] + " " + y, out)
    }
}
