package com.alzahra.taqweem

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Qibla direction from Al-Ahsa (25.383°N, 49.586°E): 248° (WSW), 1088 km to Makkah. */
object Qibla {
    const val BEARING = 248.1f
    const val DISTANCE_KM = 1088

    fun show(activity: Activity) {
        val ui = Ui(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = ui.column(18)
        col.background = ui.rounded(Color.parseColor("#FAF6EE"), 24)
        val head = ui.row()
        head.addView(ui.text("🕋", 30f, Colors.TEXT), ui.wrap(0, 0, 0, 0))
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
        ht.addView(ui.text("تحديد القبلة المشرفة", 20f, ui.theme.primary, bold = true))
        ht.addView(ui.text("الأحساء • المسافة للكعبة: $DISTANCE_KM كم", 13f, Colors.MUTED))
        head.addView(ht, ui.weight())
        head.addView(ui.text("✕", 24f, Colors.TEXT).apply { setPadding(ui.dp(10), 0, ui.dp(10), 0); setOnClickListener { dialog.dismiss() } })
        col.addView(head)

        val hint = ui.text("", 17f, Color.parseColor("#E65100"), bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Color.parseColor("#FFF3E0"), 14)
            setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
        }
        col.addView(hint, ui.match(0, 12, 0, 8))

        val compass = CompassView(activity, ui.theme.accent)
        col.addView(compass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)))
        col.addView(ui.text("زاوية القبلة للأحساء: ${BEARING.roundToInt()}° WSW", 14f, Colors.MUTED, bold = true).apply { gravity = Gravity.CENTER }, ui.match(0, 8, 0, 4))
        col.addView(ui.text("💡 للحصول على أعلى دقة: حرّك الهاتف في الهواء على شكل الرقم 8 بعيداً عن المعادن", 12f, Colors.MUTED).apply { gravity = Gravity.CENTER }, ui.match(0, 4, 0, 8))
        col.addView(ui.primaryButton("تم") { dialog.dismiss() })

        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val ori = FloatArray(3)
            private var smooth = Float.NaN
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                var az = Math.toDegrees(ori[0].toDouble()).toFloat()
                if (az < 0) az += 360f
                smooth = if (smooth.isNaN()) az else {
                    var d = az - smooth
                    if (d > 180) d -= 360f
                    if (d < -180) d += 360f
                    (smooth + d * 0.15f + 360f) % 360f
                }
                compass.heading = smooth
                compass.invalidate()
                var diff = BEARING - smooth
                if (diff > 180) diff -= 360f
                if (diff < -180) diff += 360f
                hint.text = when {
                    abs(diff) <= 5 -> "✓ أنت الآن متجه نحو القبلة"
                    diff > 0 -> "انعطف يميناً بمقدار ${diff.roundToInt()}°"
                    else -> "انعطف يساراً بمقدار ${(-diff).roundToInt()}°"
                }
                hint.setTextColor(if (abs(diff) <= 5) Color.parseColor("#2E7D32") else Color.parseColor("#E65100"))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor == null) {
            hint.text = "جهازك لا يحتوي على بوصلة. القبلة باتجاه ${BEARING.roundToInt()}° من الشمال"
        } else {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        dialog.setOnDismissListener { sm.unregisterListener(listener) }
        dialog.setContentView(ui.scroll(col))
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}

class CompassView(ctx: Context, private val gold: Int) : View(ctx) {
    var heading = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 12f
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#F3E9D6")
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.color = gold
        c.drawCircle(cx, cy, r, p)
        p.strokeWidth = 2f
        p.color = Color.parseColor("#DDD0B8")
        c.drawCircle(cx, cy, r * 0.62f, p)
        c.drawCircle(cx, cy, r * 0.4f, p)

        // Ticks rotate with the dial (north = -heading).
        for (i in 0 until 72) {
            val a = Math.toRadians((i * 5 - heading - 90).toDouble())
            val len = if (i % 18 == 0) 30f else if (i % 6 == 0) 18f else 9f
            p.color = if (i == 0) Color.parseColor("#1976D2") else Color.parseColor("#A89A80")
            p.strokeWidth = if (i % 6 == 0) 4f else 2f
            c.drawLine(cx + (r - len) * cos(a).toFloat(), cy + (r - len) * sin(a).toFloat(), cx + (r - 4) * cos(a).toFloat(), cy + (r - 4) * sin(a).toFloat(), p)
        }
        // "ش" (north) label
        val na = Math.toRadians((-heading - 90).toDouble())
        p.style = Paint.Style.FILL
        p.textSize = 34f
        p.textAlign = Paint.Align.CENTER
        p.color = Color.parseColor("#1976D2")
        c.drawText("ش", cx + (r - 60) * cos(na).toFloat(), cy + (r - 60) * sin(na).toFloat() + 12, p)

        // Kaaba marker
        val qa = Math.toRadians((Qibla.BEARING - heading - 90).toDouble())
        val kx = cx + (r - 40) * cos(qa).toFloat()
        val ky = cy + (r - 40) * sin(qa).toFloat()
        p.color = gold
        p.strokeWidth = 6f
        c.drawLine(cx, cy, kx, ky, p)
        p.color = Color.parseColor("#1A1A1A")
        c.drawCircle(kx, ky, 34f, p)
        p.color = gold
        c.drawRect(RectF(kx - 18, ky - 4, kx + 18, ky + 3), p)
        p.textSize = 30f
        c.drawText("🕋", kx, ky + 11, p)

        // Phone in the centre
        p.color = Color.parseColor("#1E88E5")
        c.drawCircle(cx, cy, 34f, p)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, 34f, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 6f })
        p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(cx - 10, cy - 17, cx + 10, cy + 17), 4f, 4f, p)
    }
}
