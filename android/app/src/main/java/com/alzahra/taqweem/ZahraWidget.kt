package com.alzahra.taqweem

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The four widget sizes offered in the widget settings screen. */
enum class WidgetSize(val title: String, val cells: String, val provider: Class<out ZahraWidget>) {
    STANDARD("القياسي", "4 × 2", ZahraWidgetStandard::class.java),
    BAR("الشريطي", "4 × 1", ZahraWidgetBar::class.java),
    SMALL("المصغر", "2 × 2", ZahraWidgetSmall::class.java),
    LARGE("الموسع الشامل", "4 × 3", ZahraWidgetLarge::class.java),
}

object WidgetPrefs {
    val BACKGROUNDS = listOf(
        Triple("glass", "شفاف زجاجي", Color.parseColor("#59000000")),
        Triple("black", "أسود داكن", Color.parseColor("#F0121212")),
        Triple("maroon", "ماروني زهرائي", Color.parseColor("#F06B1723")),
        Triple("green", "أخضر زمردي", Color.parseColor("#F01B5E3F")),
        Triple("navy", "كحلي ملكي", Color.parseColor("#F01C2C4C")),
    )
    val OPTIONS = listOf(
        "countdown" to "إظهار العد التنازلي للصلاة القادمة (الوقت المتبقي)",
        "times" to "إظهار شريط مواقيت الصلاة",
        "occasion" to "إظهار مناسبة اليوم وثواب الأعمال",
        "hijama" to "إظهار حالة الحجامة وعقد النكاح",
        "uq" to "إظهار تاريخ أم القرى بجانب تاريخ الزهراء",
        "tale" to "إظهار طالع اليوم البرجي",
    )
    private val DEFAULT_ON = setOf("countdown", "times", "occasion")
    private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    fun bg(ctx: Context) = sp(ctx).getString("widget_bg", "glass") ?: "glass"
    fun setBg(ctx: Context, id: String) = sp(ctx).edit().putString("widget_bg", id).apply()
    fun on(ctx: Context, key: String) = sp(ctx).getBoolean("widget_$key", key in DEFAULT_ON)
    fun set(ctx: Context, key: String, v: Boolean) = sp(ctx).edit().putBoolean("widget_$key", v).apply()
}

open class ZahraWidget(private val size: WidgetSize) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, size)) }
        AlarmScheduler.scheduleNext(context)
    }

    companion object {
        const val ACTION_WIDGET_SETTINGS = "com.alzahra.taqweem.WIDGET_SETTINGS"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            WidgetSize.entries.forEach { s ->
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, s.provider))
                if (ids.isNotEmpty()) {
                    val views = build(ctx, s)
                    ids.forEach { mgr.updateAppWidget(it, views) }
                }
            }
        }

        private fun build(ctx: Context, size: WidgetSize): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val bg = WidgetPrefs.BACKGROUNDS.firstOrNull { it.first == WidgetPrefs.bg(ctx) } ?: WidgetPrefs.BACKGROUNDS[0]
            v.setInt(R.id.w_bg, "setColorFilter", bg.third or (0xFF shl 24))
            v.setInt(R.id.w_bg, "setImageAlpha", Color.alpha(bg.third))

            val day = CalendarData.byDate(ctx, LocalDate.now())
            val bar = size == WidgetSize.BAR
            val small = size == WidgetSize.SMALL
            val large = size == WidgetSize.LARGE

            v.setViewVisibility(R.id.w_title, if (bar || small) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_gear, if (bar) View.GONE else View.VISIBLE)
            v.setTextViewTextSize(R.id.w_hijri, android.util.TypedValue.COMPLEX_UNIT_SP, if (large) 21f else if (small) 15f else 18f)

            if (day == null) {
                v.setTextViewText(R.id.w_hijri, "تقويم الزهراء ١٤٤٨هـ")
                v.setTextViewText(R.id.w_greg, "")
                v.setTextViewText(R.id.w_event, "التاريخ الحالي خارج سنة ١٤٤٨هـ")
                listOf(R.id.w_extra, R.id.w_times, R.id.w_count_row).forEach { v.setViewVisibility(it, View.GONE) }
            } else {
                v.setTextViewText(R.id.w_greg, "${CalendarData.weekday(day)} ${CalendarData.gregLabel(day.greg)} م")
                var hijri = "${day.zahra} ${CalendarData.MONTHS[day.month - 1]} ${CalendarData.ar(CalendarData.YEAR)} هـ"
                if (!small) hijri += " (الزهراء)"
                if (WidgetPrefs.on(ctx, "uq")) hijri += " • أم القرى ${day.ummAlQura}"
                // Colour the day number the way the calendar does (black = sorrow, yellow = joy).
                val span = SpannableString(hijri)
                if (day.isJoy) span.setSpan(ForegroundColorSpan(Colors.JOY), 0, day.zahra.toString().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.setTextViewText(R.id.w_hijri, span)

                val showOcc = WidgetPrefs.on(ctx, "occasion") && !bar && day.occasions.isNotEmpty()
                v.setViewVisibility(R.id.w_event, if (showOcc) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.w_event, day.occasions.joinToString(" / ") { it.text })
                v.setInt(R.id.w_event, "setMaxLines", if (large) 3 else if (small) 2 else 1)

                val extras = buildList {
                    if (WidgetPrefs.on(ctx, "hijama")) {
                        add("الحجامة: ${day.hijama}")
                        if (day.mark.isNotBlank()) add("عقد النكاح: ${day.mark}")
                    }
                    if (WidgetPrefs.on(ctx, "tale")) add("الطالع: ${day.tale}")
                }
                v.setViewVisibility(R.id.w_extra, if (extras.isEmpty() || bar) View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.w_extra, extras.joinToString("  •  "))

                val showTimes = WidgetPrefs.on(ctx, "times") && !bar && !small
                v.setViewVisibility(R.id.w_times, if (showTimes) View.VISIBLE else View.GONE)
                val t = day.times
                listOf(
                    R.id.w_t1 to ("الفجر" to t.fajr), R.id.w_t2 to ("الشروق" to t.sunrise), R.id.w_t3 to ("الظهر" to t.dhuhr),
                    R.id.w_t4 to ("المغرب" to t.maghrib), R.id.w_t5 to ("المنتصف" to t.midnight),
                ).forEach { (id, lt) ->
                    val s = SpannableString("${lt.first}\n${lt.second}")
                    s.setSpan(RelativeSizeSpan(0.8f), 0, lt.first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(Color.parseColor("#CCFFFFFF")), 0, lt.first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    v.setTextViewText(id, s)
                }
            }

            val next = CalendarData.nextPrayer(ctx)
            val showCount = WidgetPrefs.on(ctx, "countdown") && next != null
            v.setViewVisibility(R.id.w_count_row, if (showCount) View.VISIBLE else View.GONE)
            if (next != null) {
                val label = if (next.prayer == Prayer.SUNRISE || next.prayer == Prayer.MIDNIGHT) next.prayer.label else "صلاة ${next.prayer.label}"
                v.setTextViewText(R.id.w_next, "⏳ باقي على $label:")
                val remaining = Duration.between(LocalDateTime.now(), next.at).toMillis()
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() + remaining, null, true)
                v.setChronometerCountDown(R.id.w_timer, true)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            val settings = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).setAction(ACTION_WIDGET_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_gear, settings)
            return v
        }
    }
}

class ZahraWidgetStandard : ZahraWidget(WidgetSize.STANDARD)
class ZahraWidgetBar : ZahraWidget(WidgetSize.BAR)
class ZahraWidgetSmall : ZahraWidget(WidgetSize.SMALL)
class ZahraWidgetLarge : ZahraWidget(WidgetSize.LARGE)
