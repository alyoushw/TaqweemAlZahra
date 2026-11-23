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

    const val OCC_SAME = "same"
    const val OCC_BEFORE = "before"
    const val OCC_OFF = "off"
    /** Both: the night before (the Islamic day starts at maghrib) and again in the morning. Default. */
    const val OCC_BOTH = "both"
    /** How religious occasions are reminded: the night before, in the morning, both, or not at all. */
    fun occasionMode(ctx: Context): String = sp(ctx).getString("occasion_mode", null)
        ?: if (sp(ctx).getBoolean("notify_occasion", true)) OCC_BOTH else OCC_OFF
    fun remindsEve(ctx: Context) = occasionMode(ctx).let { it == OCC_BEFORE || it == OCC_BOTH }
    fun remindsMorning(ctx: Context) = occasionMode(ctx).let { it == OCC_SAME || it == OCC_BOTH }
    fun setOccasionMode(ctx: Context, m: String) = sp(ctx).edit().putString("occasion_mode", m).apply()

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
        // Fajr, dhuhr and maghrib play the chosen adhan voice in full.
        val isAdhan = p == Prayer.FAJR || p == Prayer.DHUHR || p == Prayer.MAGHRIB
        if (isAdhan && AdhanVoice.selected(ctx) != null) {
            val ok = runCatching { AdhanService.start(ctx, "🕌 $title", "حسب توقيت الأحساء $time — تقويم الزهراء", prayer = p, time = time) }.isSuccess
            if (ok) return
        }
        // No adhan voice: same alert (stop / snooze / full screen) with the channel's sound.
        val n = PrayerAlert.prayerNotification(ctx, CH_ADHAN, p, time, ongoing = false)
        ctx.getSystemService(NotificationManager::class.java).notify(100 + p.ordinal, n)
        PrayerAlert.launchScreen(ctx, p, time, false)
    }

    /** Births and martyrdoms — the days the calendar marks yellow or black. */
    fun isReligious(day: Day) = (day.isSorrow || day.isJoy) && day.occasions.isNotEmpty()

    /** @param eve true for the evening-before reminder ("ليلة المناسبة"). */
    fun showOccasion(ctx: Context, day: Day, eve: Boolean = false) {
        if (day.occasions.isEmpty()) return
        ensureChannels(ctx)
        val body = day.occasions.joinToString("\n") { it.text }
        val kind = if (day.isSorrow) "🏴" else "🌸"
        val title = if (eve) "$kind الليلة: مناسبة غدٍ ${CalendarData.weekday(day)} ${CalendarData.hijriLabel(day)}"
            else "$kind مناسبة اليوم ${CalendarData.hijriLabel(day)}"
        val n = Notification.Builder(ctx, CH_OCCASION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
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
    private const val KIND_EVE = "eve"
    private val OCCASION_TIME: LocalTime = LocalTime.of(9, 0)
    private val EVE_TIME: LocalTime = LocalTime.of(19, 0)
    /** «الليلة» reminder: 9 in the morning of the day before, so the night's occasion is known in good time. */
    @Suppress("UNUSED_PARAMETER")
    fun eveTime(day: Day): LocalTime = LocalTime.of(9, 0)

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
            if (Prefs.remindsMorning(ctx) && Notifier.isReligious(day)) candidates += Next(date.atTime(OCCASION_TIME), KIND_OCCASION)
            if (Prefs.remindsEve(ctx)) {
                val tomorrow = CalendarData.byDate(ctx, date.plusDays(1))
                if (tomorrow != null && Notifier.isReligious(tomorrow)) candidates += Next(date.atTime(eveTime(day)), KIND_EVE)
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
            KIND_EVE -> CalendarData.byDate(ctx, LocalDateTime.now().toLocalDate().plusDays(1))?.let { Notifier.showOccasion(ctx, it, eve = true) }
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
