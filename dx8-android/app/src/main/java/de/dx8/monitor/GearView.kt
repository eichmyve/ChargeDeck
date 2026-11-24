package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Selbst gezeichnetes Zahnrad-Symbol (sieht auf jedem Geraet gleich aus). */
class GearView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 40); style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        fill.color = Ui.t.barText; hole.color = Ui.t.bar
        val cx = width / 2f; val cy = height / 2f
        val rOuter = min(width, height) / 2f * 0.92f
        val rInner = rOuter * 0.74f
        val teeth = 8
        val path = Path()
        // Zahnkranz: abwechselnd aussen (Zahn) und innen (Luecke)
        val steps = teeth * 4
        for (s in 0..steps) {
            val a = (s.toDouble() / steps) * 2 * Math.PI - Math.PI / steps
            val r = if ((s % 4) == 1 || (s % 4) == 2) rOuter else rInner
            val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, fill)
        c.drawCircle(cx, cy, rInner * 0.45f, hole)
    }
}
