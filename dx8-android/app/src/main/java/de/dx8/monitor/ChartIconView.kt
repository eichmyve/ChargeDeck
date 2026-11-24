package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Selbst gezeichnetes Verlaufs-Symbol: Achsen + Kurve. */
class ChartIconView(ctx: Context) : View(ctx) {
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 190); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 200, 255); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        axis.color = Ui.t.textDim; line.color = Ui.t.accent
        val s = minOf(width, height).toFloat(); val u = s / 24f
        val ox = (width - s) / 2; val oy = (height - s) / 2
        fun x(v: Float) = ox + v * u
        fun y(v: Float) = oy + v * u
        axis.strokeWidth = 1.8f * u
        c.drawLine(x(4f), y(3f), x(4f), y(20f), axis)
        c.drawLine(x(4f), y(20f), x(21f), y(20f), axis)
        line.strokeWidth = 2.2f * u
        val p = Path().apply {
            moveTo(x(6f), y(16f)); lineTo(x(10f), y(11f)); lineTo(x(13.5f), y(13.5f)); lineTo(x(19.5f), y(6f))
        }
        c.drawPath(p, line)
    }
}
