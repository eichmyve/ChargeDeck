package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Selbst gezeichnetes Bediensymbol: Play, Pause oder Stopp. */
class MediaIconView(ctx: Context) : View(ctx) {
    enum class Kind { PLAY, PAUSE, STOP }

    var kind = Kind.PLAY
        set(v) { if (field != v) { field = v; invalidate() } }
    var color = Color.WHITE
        set(v) { if (field != v) { field = v; paint.color = v; invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val h = s / 2f
        val rr = s * 0.08f
        when (kind) {
            Kind.PLAY -> c.drawPath(Path().apply {
                moveTo(cx - h * 0.62f, cy - h * 0.8f)
                lineTo(cx + h * 0.85f, cy)
                lineTo(cx - h * 0.62f, cy + h * 0.8f)
                close()
            }, paint)
            Kind.PAUSE -> {
                c.drawRoundRect(RectF(cx - h * 0.7f, cy - h * 0.8f, cx - h * 0.18f, cy + h * 0.8f), rr, rr, paint)
                c.drawRoundRect(RectF(cx + h * 0.18f, cy - h * 0.8f, cx + h * 0.7f, cy + h * 0.8f), rr, rr, paint)
            }
            Kind.STOP -> c.drawRoundRect(RectF(cx - h * 0.7f, cy - h * 0.7f, cx + h * 0.7f, cy + h * 0.7f), rr, rr, paint)
        }
    }
}
