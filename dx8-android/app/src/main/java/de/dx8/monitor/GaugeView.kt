package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Selbst gezeichnetes Rundinstrument (Bogen 240°).
 * Farbzonen faerben den Wertbogen, Marken (z. B. Grenzwerte) werden als Striche am Rand gezeichnet.
 */
class GaugeView(ctx: Context) : View(ctx) {
    var title = ""
    var unit = ""
    var min = 0.0
    var max = 100.0
    var decimals = 1
    /** Farbzonen: (ab Wert, Farbe), aufsteigend sortiert. */
    var zones: List<Pair<Double, Int>> = listOf(0.0 to Ui.t.ok)
    /** Marken: (Wert, Farbe). */
    var marks: List<Pair<Double, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    var value: Double? = null
        set(v) { field = v; invalidate() }

    private val sweep = 240f
    private val start = 150f            // 0° = 3 Uhr, Bogen von links unten nach rechts unten
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.rgb(55, 62, 72) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    fun colorFor(v: Double): Int = zones.lastOrNull { v >= it.first }?.second ?: zones.first().second

    private fun frac(v: Double) = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = (w * 0.78f).toInt().coerceAtMost((170 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(w, resolveSize(h, hSpec))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val th = Ui.t
        track.color = th.gaugeTrack
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = 12 * d
        // Hoehe: Rand + Radius + Titel unter dem Mittelpunkt (0.72 r + Schrift)
        val r = minOf(w / 2f - stroke, (h - stroke - 24 * d) / 1.75f)
        val cx = w / 2f; val cy = stroke + r + 4 * d
        val box = RectF(cx - r, cy - r, cx + r, cy + r)

        track.strokeWidth = stroke
        c.drawArc(box, start, sweep, false, track)

        val v = value
        if (v != null) {
            arc.strokeWidth = stroke
            arc.color = colorFor(v)
            val f = frac(v)
            if (f > 0.004f) c.drawArc(box, start, sweep * f, false, arc)
        }

        // Marken (Grenzwerte) als Striche quer ueber den Bogen
        tick.strokeWidth = 3 * d
        for ((mv, col) in marks) {
            if (mv < min || mv > max) continue
            val a = Math.toRadians((start + sweep * frac(mv)).toDouble())
            val r1 = r - stroke * 0.9f; val r2 = r + stroke * 0.9f
            tick.color = col
            c.drawLine(cx + r1 * Math.cos(a).toFloat(), cy + r1 * Math.sin(a).toFloat(),
                cx + r2 * Math.cos(a).toFloat(), cy + r2 * Math.sin(a).toFloat(), tick)
        }

        // Wert in der Mitte
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.textSize = r * 0.42f
        txt.color = if (v == null) th.gaugeNoValue else th.gaugeValue
        val vs = if (v == null) "–" else "%.${decimals}f".format(v)
        c.drawText(vs, cx, cy + txt.textSize * 0.25f, txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = r * 0.2f
        txt.color = th.gaugeLabel
        c.drawText(unit, cx, cy + r * 0.38f, txt)

        // Min/Max an den Bogenenden, Titel unten
        txt.textSize = 11 * d
        // Bogenenden liegen bei 150° bzw. 30°: x = ±cos30°·r, y = +sin30°·r
        val yEnd = cy + r * 0.5f + stroke + 10 * d
        val fmt = { x: Double -> if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x) }
        c.drawText(fmt(min), cx - r * 0.866f, yEnd, txt)
        c.drawText(fmt(max), cx + r * 0.866f, yEnd, txt)
        txt.textSize = 13 * d
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.color = th.gaugeValue
        c.drawText(title, cx, minOf(h - 4 * d, cy + r * 0.72f + 8 * d), txt)
    }
}
