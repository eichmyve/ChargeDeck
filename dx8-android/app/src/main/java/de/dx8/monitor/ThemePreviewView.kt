package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Verkleinerte Hauptseite in den Farben eines Designs: Titelleiste, Rundanzeige, zwei Kanal-Karten, untere Leiste. */
class ThemePreviewView(ctx: Context, private val th: AppTheme) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, stroke: Int = 0, sw: Float = 0f) {
        fill.color = color
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
        if (stroke != 0) { line.color = stroke; line.strokeWidth = sw; c.drawRoundRect(RectF(l, t, r, b), rad, rad, line) }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 100f
        // Seite
        rr(c, 0f, 0f, w, h, 3 * u, th.page)
        // Titelleiste
        fill.color = th.bar; c.drawRect(0f, 0f, w, 9 * u, fill)
        rr(c, 4 * u, 3 * u, 22 * u, 6 * u, 1 * u, th.barText)
        // Karte Eingang/Geraet mit zwei Boegen
        val cs = if (th.cardStroke != 0) th.cardStroke else 0
        rr(c, 3 * u, 12 * u, 97 * u, 30 * u, 2.5f * u, th.card, cs, 0.5f * u)
        for (cx in listOf(28 * u, 72 * u)) {
            line.strokeWidth = 2.2f * u
            line.color = th.gaugeTrack
            c.drawArc(RectF(cx - 7 * u, 14.5f * u, cx + 7 * u, 28.5f * u), 150f, 240f, false, line)
            line.color = th.ok
            c.drawArc(RectF(cx - 7 * u, 14.5f * u, cx + 7 * u, 28.5f * u), 150f, 130f, false, line)
            rr(c, cx - 3 * u, 20.5f * u, cx + 3 * u, 22.5f * u, 0.6f * u, th.gaugeValue)
        }
        // Zwei Kanal-Karten
        for ((i, l) in listOf(3 * u, 51 * u).withIndex()) {
            val r = l + 46 * u
            rr(c, l, 33 * u, r, 52 * u, 2.5f * u, th.card, cs, 0.5f * u)
            rr(c, l + 3 * u, 35.5f * u, l + 15 * u, 38 * u, 0.6f * u, th.text)                          // "Kanal N"
            val sc = if (i == 0) th.ok else th.warn
            rr(c, l + 17 * u, 35.2f * u, l + 28 * u, 38.4f * u, 1.6f * u, argb(50, sc), sc, 0.4f * u)  // Status
            rr(c, l + 3 * u, 41 * u, l + 22 * u, 42.6f * u, 0.5f * u, th.textDim)                       // Werte
            rr(c, l + 3 * u, 45 * u, l + 33 * u, 50 * u, 1.2f * u, if (i == 0) th.holdFill else th.startFill) // Hauptknopf
            rr(c, l + 35 * u, 45 * u, r - 3 * u, 50 * u, 1.2f * u, th.card, th.error, 0.5f * u)       // Stopp
        }
        // untere Leiste mit Zahnrad-Punkt
        fill.color = th.bar; c.drawRect(0f, h - 8 * u, w, h, fill)
        rr(c, 4 * u, h - 6 * u, 18 * u, h - 2 * u, 1 * u, th.barChip)
        fill.color = th.barText; c.drawCircle(94 * u, h - 4 * u, 1.8f * u, fill)
    }
}
