package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Kleines Waage-Symbol: der Balancer gleicht diese Zelle gerade aus (ersetzt den Text "bal"). */
class BalanceIconView(ctx: Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init { contentDescription = "Balancer aktiv" }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        line.color = Ui.t.accent; fill.color = Ui.t.accent
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f; val oy = (height - s) / 2f
        fun x(f: Float) = ox + f * s
        fun y(f: Float) = oy + f * s
        line.strokeWidth = s * 0.08f
        // Saeule, Fuss, Balken
        c.drawLine(x(0.5f), y(0.14f), x(0.5f), y(0.86f), line)
        c.drawLine(x(0.3f), y(0.88f), x(0.7f), y(0.88f), line)
        c.drawLine(x(0.12f), y(0.22f), x(0.88f), y(0.22f), line)
        c.drawCircle(x(0.5f), y(0.14f), s * 0.06f, fill)
        // zwei Schalen: Dreieck-Aufhaengung + Halbkreis
        for (cx in floatArrayOf(0.2f, 0.8f)) {
            line.strokeWidth = s * 0.05f
            c.drawLine(x(cx), y(0.22f), x(cx - 0.13f), y(0.55f), line)
            c.drawLine(x(cx), y(0.22f), x(cx + 0.13f), y(0.55f), line)
            c.drawPath(Path().apply {
                moveTo(x(cx - 0.17f), y(0.55f))
                quadTo(x(cx), y(0.78f), x(cx + 0.17f), y(0.55f))
                close()
            }, fill)
        }
    }
}
