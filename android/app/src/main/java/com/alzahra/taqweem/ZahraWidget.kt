package com.alzahra.taqweem

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class ZahraWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
        AlarmScheduler.scheduleNext(context)
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ZahraWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val day = CalendarData.byDate(ctx, LocalDate.now())
            if (day == null) {
                v.setTextViewText(R.id.w_day, "")
                v.setTextViewText(R.id.w_date, "تقويم الزهراء ١٤٤٨هـ")
                v.setTextViewText(R.id.w_greg, "")
                v.setTextViewText(R.id.w_event, "التاريخ الحالي خارج سنة ١٤٤٨هـ")
                v.setTextViewText(R.id.w_next, "")
                v.setTextViewText(R.id.w_times, "")
            } else {
                v.setTextViewText(R.id.w_day, CalendarData.ar(day.zahra))
                val (bg, fg) = Colors.zahraCell(day.zahraColor)
                v.setInt(R.id.w_day, "setBackgroundColor", if (bg == Color.TRANSPARENT) 0x33FFFFFF else bg)
                v.setTextColor(R.id.w_day, if (bg == Color.TRANSPARENT) Color.WHITE else fg)
                v.setTextViewText(R.id.w_date, "${CalendarData.weekday(day)} ${CalendarData.MONTHS[day.month - 1]} ${CalendarData.ar(CalendarData.YEAR)}")
                v.setTextViewText(R.id.w_greg, CalendarData.gregLabel(day.greg))
                v.setTextViewText(R.id.w_event, day.occasions.joinToString(" • ") { it.text })
                v.setTextViewText(
                    R.id.w_times,
                    "الصبح ${day.times.fajr}   الظهر ${day.times.dhuhr}   المغرب ${day.times.maghrib}",
                )
            }

            val next = CalendarData.nextPrayer(ctx)
            if (next != null) {
                val t = next.at.toLocalTime()
                val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
                v.setTextViewText(R.id.w_next, "القادمة: ${next.prayer.label} ${h12}:${"%02d".format(t.minute)}")
                val remaining = Duration.between(LocalDateTime.now(), next.at).toMillis()
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() + remaining, null, true)
                v.setChronometerCountDown(R.id.w_timer, true)
            } else {
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}
