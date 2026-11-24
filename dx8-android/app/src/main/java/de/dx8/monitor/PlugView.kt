package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Selbst gezeichnetes Stecker-Symbol fuer die Eingangsquelle. */
class PlugView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        fill.color = Ui.t.barText; line.color = Ui.t.barText
        val w = width.toFloat(); val h = height.toFloat(); val u = minOf(w, h) / 24f
        val ox = (w - 24 * u) / 2; val oy = (h - 24 * u) / 2
        fun x(v: Float) = ox + v * u
        fun y(v: Float) = oy + v * u
        // zwei Stifte
        c.drawRoundRect(RectF(x(8f), y(2f), x(10f), y(8f)), u, u, fill)
        c.drawRoundRect(RectF(x(14f), y(2f), x(16f), y(8f)), u, u, fill)
        // Steckerkoerper
        c.drawRoundRect(RectF(x(5f), y(7.5f), x(19f), y(15f)), 2 * u, 2 * u, fill)
        c.drawRoundRect(RectF(x(9f), y(14f), x(15f), y(18f)), u, u, fill)
        // Kabel
        line.strokeWidth = 2.2f * u
        c.drawLine(x(12f), y(18f), x(12f), y(22.5f), line)
    }
}
