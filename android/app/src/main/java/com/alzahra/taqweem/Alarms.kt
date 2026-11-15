package com.alzahra.taqweem

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Prefs {
    private const val FILE = "zahra_prefs"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Prayers that ring by default: the three Shia prayer times. */
    private val DEFAULT_ON = setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.MAGHRIB)

    fun prayerOn(ctx: Context, p: Prayer) = sp(ctx).getBoolean("notify_${p.key}", p in DEFAULT_ON)
    fun setPrayerOn(ctx: Context, p: Prayer, on: Boolean) = sp(ctx).edit().putBoolean("notify_${p.key}", on).apply()

    fun occasionOn(ctx: Context) = sp(ctx).getBoolean("notify_occasion", true)
    fun setOccasionOn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("notify_occasion", on).apply()

    fun lastUpdateCheck(ctx: Context) = sp(ctx).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(ctx: Context, t: Long) = sp(ctx).edit().putLong("last_update_check", t).apply()
}

object Notifier {
    const val CH_ADHAN = "adhan"
    const val CH_OCCASION = "occasion"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ADHAN) == null) {
            val ch = NotificationChannel(CH_ADHAN, "مواقيت الصلاة", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "تنبيه عند دخول وقت الصلاة"
            ch.enableVibration(true)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_OCCASION) == null) {
            val ch = NotificationChannel(CH_OCCASION, "مناسبة اليوم", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "إشعار صباحي بمناسبات اليوم"
            nm.createNotificationChannel(ch)
        }
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun showPrayer(ctx: Context, p: Prayer, time: String) {
        ensureChannels(ctx)
        val title = if (p == Prayer.IMSAK) "حان وقت الإمساك" else "حان الآن موعد ${p.label}"
        val n = Notification.Builder(ctx, CH_ADHAN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText("حسب توقيت الأحساء $time — تقويم الزهراء")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(100 + p.ordinal, n)
    }

    fun showOccasion(ctx: Context, day: Day) {
        if (day.occasions.isEmpty()) return
        ensureChannels(ctx)
        val body = day.occasions.joinToString("\n") { it.text }
        val n = Notification.Builder(ctx, CH_OCCASION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${CalendarData.weekday(day)} ${CalendarData.hijriLabel(day)}")
            .setContentText(day.occasions.first().text)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(200, n)
    }
}

/**
 * Keeps exactly one alarm pending: the next prayer the user enabled, the 7:00 occasion
 * notice, or the after-midnight widget refresh — whichever comes first. Each firing
 * schedules the following one.
 */
object AlarmScheduler {
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_PRAYER = "prayer"
    private const val EXTRA_TIME = "time"
    private const val KIND_PRAYER = "prayer"
    private const val KIND_OCCASION = "occasion"
    private const val KIND_REFRESH = "refresh"
    private val OCCASION_TIME: LocalTime = LocalTime.of(7, 0)

    private data class Next(val at: LocalDateTime, val kind: String, val prayer: Prayer? = null, val label: String = "")

    private fun findNext(ctx: Context, now: LocalDateTime): Next? {
        val candidates = ArrayList<Next>()
        for (offset in 0L..1L) {
            val date = now.toLocalDate().plusDays(offset)
            candidates += Next(date.atTime(0, 0, 30), KIND_REFRESH)
            val day = CalendarData.byDate(ctx, date) ?: continue
            for (p in Prayer.entries) {
                val t = CalendarData.timeOf(day, p) ?: continue
                // Every time is a wake-up: either it rings, or it just moves the widget's countdown on.
                candidates += if (Prefs.prayerOn(ctx, p)) Next(date.atTime(t), KIND_PRAYER, p, rawTime(day, p))
                else Next(date.atTime(t), KIND_REFRESH)
            }
            if (Prefs.occasionOn(ctx) && day.occasions.isNotEmpty()) {
                candidates += Next(date.atTime(OCCASION_TIME), KIND_OCCASION)
            }
        }
        return candidates.filter { it.at.isAfter(now.plusSeconds(5)) }.minByOrNull { it.at }
    }

    private fun rawTime(day: Day, p: Prayer) = when (p) {
        Prayer.IMSAK -> day.times.imsak
        Prayer.FAJR -> day.times.fajr
        Prayer.SUNRISE -> day.times.sunrise
        Prayer.DHUHR -> day.times.dhuhr
        Prayer.MAGHRIB -> day.times.maghrib
        Prayer.MIDNIGHT -> day.times.midnight
    }

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = findNext(ctx, LocalDateTime.now()) ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_KIND, next.kind)
            .putExtra(EXTRA_PRAYER, next.prayer?.name)
            .putExtra(EXTRA_TIME, next.label)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val millis = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun handle(ctx: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_PRAYER -> {
                val p = intent.getStringExtra(EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
                if (p != null) Notifier.showPrayer(ctx, p, intent.getStringExtra(EXTRA_TIME) ?: "")
            }
            KIND_OCCASION -> CalendarData.byDate(ctx, LocalDateTime.now().toLocalDate())?.let { Notifier.showOccasion(ctx, it) }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.handle(context, intent)
        ZahraWidget.updateAll(context)
        AlarmScheduler.scheduleNext(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ZahraWidget.updateAll(context)
        AlarmScheduler.scheduleNext(context)
    }
}
