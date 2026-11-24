package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Ein Messpunkt im Verlauf eines Programms. */
data class Pt(val tSec: Double, val u: Double, val i: Double, val cap: Long, val cells: List<Int>)

/** Verlauf je Kanal: wird bei neuem Programmstart (Laufzeit springt zurueck) geleert. */
class History(private val max: Int = 4000) {
    val points = ArrayList<Pt>()

    fun add(c: ChannelData) {
        val t = c.ts / 1000.0
        val lastPt = points.lastOrNull()
        if (lastPt != null && t < lastPt.tSec - 1.5) points.clear()      // neuer Lauf
        if (lastPt != null && t == lastPt.tSec && points.size > 1) return // gleiche Sekunde
        points += Pt(t, c.uoutV, c.currentA, c.capMah, c.cells)
        if (points.size > max) {                                           // ausduennen
            val keep = points.filterIndexed { idx, _ -> idx % 2 == 0 || idx == points.size - 1 }
            points.clear(); points.addAll(keep)
        }
    }
}

/** Liniendiagramm ueber die Zeit mit linker und rechter Achse. */
class ChartView(ctx: Context) : View(ctx) {

    class Series(val name: String, val color: Int, val rightAxis: Boolean, val values: (Pt) -> Double?)

    var points: List<Pt> = emptyList()
    var series: List<Series> = emptyList()
    var leftUnit = ""
    var rightUnit = ""

    private val dens = resources.displayMetrics.density
    private val grid = Paint().apply { color = Color.rgb(60, 66, 74); strokeWidth = 1f * dens }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 178, 188); textSize = 11f * dens }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dens }

    fun update(p: List<Pt>) { points = ArrayList(p); invalidate() }

    private fun range(right: Boolean): Pair<Double, Double>? {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (s in series.filter { it.rightAxis == right }) for (p in points) {
            val v = s.values(p) ?: continue
            if (v < lo) lo = v; if (v > hi) hi = v
        }
        if (lo == Double.MAX_VALUE) return null
        if (hi - lo < 1e-6) { lo -= 0.5; hi += 0.5 }
        val pad = (hi - lo) * 0.08
        return Pair(lo - pad, hi + pad)
    }

    override fun onDraw(c: Canvas) {
        grid.color = Ui.t.chartGrid; txt.color = Ui.t.chartText
        super.onDraw(c)
        val l = 46f * dens; val r = width - 46f * dens; val t = 10f * dens; val b = height - 22f * dens
        if (points.size < 2) {
            c.drawText("noch keine Verlaufsdaten – Aufzeichnung startet mit dem Programm", l, (t + b) / 2, txt)
            return
        }
        val t0 = points.first().tSec; val t1 = maxOf(points.last().tSec, t0 + 1)
        fun x(tt: Double) = (l + (tt - t0) / (t1 - t0) * (r - l)).toFloat()

        // Gitter + Zeitachse
        for (g in 0..4) { val y = t + (b - t) * g / 4f; c.drawLine(l, y, r, y, grid) }
        txt.textAlign = Paint.Align.LEFT; c.drawText(fmtTime(t0), l, height - 6f * dens, txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText(fmtTime(t1), r, height - 6f * dens, txt)

        for (right in listOf(false, true)) {
            val (lo, hi) = range(right) ?: continue
            fun y(v: Double) = (b - (v - lo) / (hi - lo) * (b - t)).toFloat()
            // Achsenbeschriftung
            txt.textAlign = if (right) Paint.Align.LEFT else Paint.Align.RIGHT
            val ax = if (right) r + 4f * dens else l - 4f * dens
            val unit = if (right) rightUnit else leftUnit
            c.drawText("%.2f".format(hi), ax, t + 10f * dens, txt)
            c.drawText("%.2f".format(lo), ax, b, txt)
            c.drawText(unit, ax, (t + b) / 2, txt)
            for (s in series.filter { it.rightAxis == right }) {
                line.color = s.color
                val path = Path(); var started = false
                for (p in points) {
                    val v = s.values(p) ?: continue
                    if (!started) { path.moveTo(x(p.tSec), y(v)); started = true } else path.lineTo(x(p.tSec), y(v))
                }
                c.drawPath(path, line)
            }
        }
    }

    private fun fmtTime(s: Double): String { val v = s.toLong(); return "%d:%02d:%02d".format(v / 3600, (v / 60) % 60, v % 60) }
}
