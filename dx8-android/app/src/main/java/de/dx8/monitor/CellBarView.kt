package de.dx8.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Zellbalken, der die freie Breite der Zeile ausfuellt (Fuellgrad 0..1). */
class CellBarView(ctx: Context) : View(ctx) {
    var fill = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        track.color = Ui.t.divider; bar.color = Ui.t.accent
        val h = height.toFloat(); val rad = h / 2f
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, rad, rad, track)
        if (fill > 0f) {
            r.set(0f, 0f, maxOf(h, width * fill), h)
            c.drawRoundRect(r, rad, rad, bar)
        }
    }
}
