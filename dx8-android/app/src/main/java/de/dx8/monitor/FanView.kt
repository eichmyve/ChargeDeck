package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Selbst gezeichnetes Lüfterrad; dreht sich, solange spinning = true. */
class FanView(ctx: Context) : View(ctx) {
    var spinning = false
        set(v) {
            if (field == v) return
            field = v
            lastFrame = 0L
            invalidate()
        }

    private var angle = 0f
    private var lastFrame = 0L
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(100, 200, 255) }
    private val blade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(100, 200, 255) }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(30, 34, 40) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        ring.color = Ui.t.accent; blade.color = Ui.t.accent; hub.color = Ui.t.card
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = s / 2f * 0.92f
        ring.strokeWidth = s * 0.05f
        c.drawCircle(cx, cy, r, ring)

        // 5 Fluegel, je eine geschwungene Form um die Nabe
        c.save()
        c.rotate(angle, cx, cy)
        val br = r * 0.82f
        for (i in 0 until 5) {
            c.save()
            c.rotate(i * 72f, cx, cy)
            val p = Path().apply {
                moveTo(cx, cy)
                cubicTo(cx + br * 0.15f, cy - br * 0.35f, cx + br * 0.55f, cy - br * 0.95f, cx + br * 0.05f, cy - br)
                cubicTo(cx - br * 0.25f, cy - br * 0.75f, cx - br * 0.2f, cy - br * 0.3f, cx, cy)
                close()
            }
            c.drawPath(p, blade)
            c.restore()
        }
        c.restore()
        c.drawCircle(cx, cy, r * 0.2f, hub)
        c.drawCircle(cx, cy, r * 0.11f, blade)

        if (spinning) {
            val now = System.nanoTime()
            if (lastFrame != 0L) angle = (angle + (now - lastFrame) / 1e9f * 540f) % 360f   // 1,5 U/s
            lastFrame = now
            postInvalidateOnAnimation()
        }
    }
}
