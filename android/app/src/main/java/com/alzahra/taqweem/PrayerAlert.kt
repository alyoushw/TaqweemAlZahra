package com.alzahra.taqweem

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/**
 * Prayer alerts: the notification (with «إيقاف» and «تأجيل ١٠ دقائق»), the optional full-screen
 * page, and the reminder that follows a snooze.
 */
object PrayerAlert {
    const val SNOOZE_MIN = 10
    const val NOTIF_ID = 300
    private const val CH_REMIND = "prayer_reminder"

    const val ACTION_STOP = "com.alzahra.taqweem.PRAYER_STOP"
    const val ACTION_SNOOZE = "com.alzahra.taqweem.PRAYER_SNOOZE"
    const val ACTION_SNOOZE_FIRE = "com.alzahra.taqweem.PRAYER_SNOOZE_FIRE"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_TIME = "time"
    const val EXTRA_SNOOZED = "snoozed"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    fun fullScreenOn(ctx: Context) = sp(ctx).getBoolean("adhan_fullscreen", false)
    fun setFullScreen(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("adhan_fullscreen", on).apply()

    /** Android 14+ lets the user withhold full-screen alerts; true when we are allowed to show them. */
    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** «Display over other apps»: lets the adhan page open on top of whatever app is in use. */
    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openOverlaySettings(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${activity.packageName}")))
        }
    }

    /** Opens the full-screen adhan page right away (like an alarm clock), when enabled and allowed. */
    fun launchScreen(ctx: Context, p: Prayer, time: String, snoozed: Boolean) {
        if (!fullScreenOn(ctx)) return
        runCatching {
            ctx.startActivity(
                Intent(ctx, AdhanActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    .putExtra(EXTRA_PRAYER, p.name).putExtra(EXTRA_TIME, time).putExtra(EXTRA_SNOOZED, snoozed),
            )
        }
    }

    fun openFullScreenSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${activity.packageName}")))
            }
        }
    }

    private fun ensureReminderChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_REMIND) == null) {
            val ch = NotificationChannel(CH_REMIND, "تذكير بعد تأجيل الصلاة", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "يصل بعد ١٠ دقائق من الضغط على «تأجيل»"
            ch.enableVibration(true)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun actionIntent(ctx: Context, action: String, p: Prayer, time: String, code: Int) =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, PrayerActionReceiver::class.java).setAction(action).putExtra(EXTRA_PRAYER, p.name).putExtra(EXTRA_TIME, time),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun screenIntent(ctx: Context, p: Prayer, time: String, snoozed: Boolean) =
        PendingIntent.getActivity(
            ctx, if (snoozed) 41 else 40,
            Intent(ctx, AdhanActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PRAYER, p.name).putExtra(EXTRA_TIME, time).putExtra(EXTRA_SNOOZED, snoozed),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun title(p: Prayer) = if (p == Prayer.IMSAK) "حان وقت الإمساك" else "حان الآن موعد ${prayerName(p)}"
    fun prayerName(p: Prayer) = when (p) {
        Prayer.FAJR -> "صلاة الصبح"; Prayer.DHUHR -> "صلاة الظهر"; Prayer.MAGHRIB -> "صلاة المغرب"; else -> p.label
    }
    const val SNOOZE_MESSAGE = "مضت ١٠ دقائق على دخول الوقت.\nاترك ما بيدك وأدِّ صلاتك المفروضة، فالصلاة في أول وقتها أحبّ إلى الله.\n﴿إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَوْقُوتًا﴾"

    /** The first alert at the prayer time. Used by the adhan service and by the silent mode. */
    fun prayerNotification(ctx: Context, channel: String, p: Prayer, time: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🕌 ${title(p)}")
            .setContentText("حسب توقيت الأحساء $time — تقويم الزهراء")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(if (fullScreenOn(ctx)) screenIntent(ctx, p, time, false) else open)
            .setDeleteIntent(actionIntent(ctx, ACTION_STOP, p, time, 30))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .addAction(Notification.Action.Builder(null, "⏹ إيقاف", actionIntent(ctx, ACTION_STOP, p, time, 31)).build())
            .addAction(Notification.Action.Builder(null, "⏰ تأجيل ١٠ دقائق", actionIntent(ctx, ACTION_SNOOZE, p, time, 32)).build())
        if (fullScreenOn(ctx)) b.setFullScreenIntent(screenIntent(ctx, p, time, false), true)
        return b.build()
    }

    fun scheduleSnooze(ctx: Context, p: Prayer, time: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
        val pi = actionIntent(ctx, ACTION_SNOOZE_FIRE, p, time, 33)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching { if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** The reminder 10 minutes after «تأجيل»: different look, firm wording. */
    fun showSnoozed(ctx: Context, p: Prayer, time: String) {
        ensureReminderChannel(ctx)
        val b = Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("⏰ أخّرت ${prayerName(p)} ١٠ دقائق")
            .setContentText("اترك ما بيدك وأدِّ صلاتك المفروضة")
            .setStyle(Notification.BigTextStyle().bigText("${prayerName(p)} — دخل وقتها $time\n\n$SNOOZE_MESSAGE"))
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(Color.parseColor("#C62828"))
            .setAutoCancel(true)
            .setContentIntent(screenIntent(ctx, p, time, true))
            .addAction(Notification.Action.Builder(null, "✓ سأصلي الآن", actionIntent(ctx, ACTION_STOP, p, time, 34)).build())
            .addAction(Notification.Action.Builder(null, "⏰ تأجيل ١٠ دقائق", actionIntent(ctx, ACTION_SNOOZE, p, time, 35)).build())
        if (fullScreenOn(ctx)) b.setFullScreenIntent(screenIntent(ctx, p, time, true), true)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 2, b.build())
        launchScreen(ctx, p, time, true)
    }

    fun clear(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(NOTIF_ID, NOTIF_ID + 1, NOTIF_ID + 2).forEach { nm.cancel(it) }
        Prayer.entries.forEach { nm.cancel(100 + it.ordinal) }
    }
}

class PrayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = intent.getStringExtra(PrayerAlert.EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() } ?: Prayer.DHUHR
        val time = intent.getStringExtra(PrayerAlert.EXTRA_TIME) ?: ""
        when (intent.action) {
            PrayerAlert.ACTION_STOP -> { AdhanService.stop(context); PrayerAlert.clear(context) }
            PrayerAlert.ACTION_SNOOZE -> {
                AdhanService.stop(context)
                PrayerAlert.clear(context)
                PrayerAlert.scheduleSnooze(context, p, time)
                android.widget.Toast.makeText(context, "سيتم تذكيرك بعد ١٠ دقائق", android.widget.Toast.LENGTH_SHORT).show()
            }
            PrayerAlert.ACTION_SNOOZE_FIRE -> PrayerAlert.showSnoozed(context, p, time)
        }
    }
}

/** Full-screen adhan page (shown over the lock screen when «الواجهة الكاملة للأذان» is on). */
class AdhanActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    /** Like an alarm clock: the page closes only with «إيقاف» or «تأجيل». */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun render(i: Intent) {
        val ui = Ui(this)
        val p = i.getStringExtra(PrayerAlert.EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() } ?: Prayer.DHUHR
        val time = i.getStringExtra(PrayerAlert.EXTRA_TIME) ?: ""
        val snoozed = i.getBooleanExtra(PrayerAlert.EXTRA_SNOOZED, false)
        val day = CalendarData.byDate(this, LocalDate.now())

        val bg = if (snoozed) Color.parseColor("#2A0E10") else ui.theme.primaryDark
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
            setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(32))
        }
        col.addView(ui.text(if (snoozed) "⏰" else "🕌", 64f, Color.WHITE).apply { gravity = Gravity.CENTER })
        if (day != null) col.addView(ui.text("${CalendarData.weekday(day)} • ${CalendarData.hijriLabel(day)}", 16f, Color.parseColor("#D9FFFFFF")).apply { gravity = Gravity.CENTER }, ui.match(0, 12, 0, 0))
        col.addView(ui.text(if (snoozed) "أخّرت ${PrayerAlert.prayerName(p)}\n١٠ دقائق" else PrayerAlert.title(p), 34f, ui.theme.accent, bold = true).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f)
        }, ui.match(0, 18, 0, 0))
        col.addView(ui.text(time, 52f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }, ui.match(0, 8, 0, 0))
        col.addView(ui.text("حسب توقيت الأحساء — تقويم الزهراء", 14f, Color.parseColor("#B3FFFFFF")).apply { gravity = Gravity.CENTER })
        if (snoozed) {
            col.addView(ui.text(PrayerAlert.SNOOZE_MESSAGE, 20f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
                setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16))
                background = ui.rounded(Color.parseColor("#33FFFFFF"), 18)
            }, ui.match(0, 24, 0, 0))
        }
        col.addView(View(this), android.widget.LinearLayout.LayoutParams(1, 0, 1f))

        fun big(label: String, fill: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; setTextColor(fg); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, ui.dp(18), 0, ui.dp(18))
            background = ui.rounded(fill, 20)
            setOnClickListener { onClick() }
        }
        col.addView(big(if (snoozed) "✓ سأصلي الآن" else "⏹ إيقاف الأذان", ui.theme.accent, Color.parseColor("#2A1A00")) {
            AdhanService.stop(this); PrayerAlert.clear(this); finish()
        }, ui.match(0, 0, 0, 10))
        col.addView(big("⏰ تأجيل ١٠ دقائق", Color.parseColor("#33FFFFFF"), Color.WHITE) {
            AdhanService.stop(this); PrayerAlert.clear(this); PrayerAlert.scheduleSnooze(this, p, time)
            android.widget.Toast.makeText(this, "سيتم تذكيرك بعد ١٠ دقائق", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }, ui.match(0, 0, 0, 0))
        setContentView(col)
        Immersive.padBottom(col)
        Immersive.apply(window)
    }
}
