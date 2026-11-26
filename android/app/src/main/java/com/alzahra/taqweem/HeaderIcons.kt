package com.alzahra.taqweem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Line icons for the header buttons, drawn in the same thin white style (no emoji). */
class LineIcon(ctx: Context, private val kind: Kind) : View(ctx) {
    enum class Kind { GPS, GEAR }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val u = min(width, height) * 0.6f / 24f // the icon is 24 units across, 60% of the button
        p.strokeWidth = 1.8f * u
        when (kind) {
            Kind.GPS -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, 6.5f * u, p)
                listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                    c.drawLine(cx + dx * 6.5f * u, cy + dy * 6.5f * u, cx + dx * 9.5f * u, cy + dy * 9.5f * u, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, 2.3f * u, p)
            }
            Kind.GEAR -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, 3.2f * u, p)
                c.drawCircle(cx, cy, 6.5f * u, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(cx + (6.5f * u * cos(a)).toFloat(), cy + (6.5f * u * sin(a)).toFloat(),
                        cx + (9.3f * u * cos(a)).toFloat(), cy + (9.3f * u * sin(a)).toFloat(), p)
                }
            }
        }
    }
}
