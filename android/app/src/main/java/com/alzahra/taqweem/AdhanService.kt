package com.alzahra.taqweem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder

/** The adhan voices the user can pick from (files in res/raw). */
enum class AdhanVoice(val id: String, val title: String, val res: Int) {
    SHIA("shia", "الأذان الشيعي كاملاً", R.raw.adhan_shia),
    ABATHER("abather", "أباذر الحلواجي", R.raw.adhan_abather),
    REZAIYAN("rezaiyan", "حسن رضائيان", R.raw.adhan_rezaiyan),
    SHARIF("sharif", "حسين شريف", R.raw.adhan_sharif),
    KAZEM_ZADEH("kazem_zadeh", "كاظم زاده", R.raw.adhan_kazem_zadeh),
    // Full Shia adhans from the shiafy library.
    AHSAI("ahsai", "أبو حسن الأحسائي — أذان كامل", R.raw.adhan_ahsai),
    ABBAS_YOUSIF("abbas_yousif", "عباس يوسف — أذان الشيعة كامل", R.raw.adhan_abbas_yousif),
    ABATHER_KHASHI("abather_khashi", "أباذر الحلواجي — الأذان الخاشع كامل", R.raw.adhan_abather_khashi),
    ;

    companion object {
        const val SILENT = "silent"
        private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
        /** Selected voice id, or [SILENT] for a plain notification. */
        fun selectedId(ctx: Context) = sp(ctx).getString("adhan_voice", ABATHER.id) ?: ABATHER.id
        fun select(ctx: Context, id: String) = sp(ctx).edit().putString("adhan_voice", id).apply()
        fun selected(ctx: Context): AdhanVoice? = entries.firstOrNull { it.id == selectedId(ctx) }
    }
}

/**
 * Plays the chosen adhan in full when a prayer time arrives (the adhan is longer than a notification
 * sound may be). Runs as a short foreground service with a "stop" button in its notification.
 */
class AdhanService : Service() {
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        val voice = AdhanVoice.entries.firstOrNull { it.id == intent?.getStringExtra(EXTRA_VOICE) } ?: AdhanVoice.selected(this)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "حان وقت الصلاة"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        val prayer = intent?.getStringExtra(EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        val ptime = intent?.getStringExtra(EXTRA_PTIME) ?: ""
        // A real prayer gets the stop/snooze notification (and full screen if enabled); a voice test keeps the simple one.
        ensureChannel(this)
        val n = if (prayer != null) PrayerAlert.prayerNotification(this, CHANNEL, prayer, ptime, ongoing = true) else notification(title, text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)

        if (prayer != null) PrayerAlert.launchScreen(this, prayer, ptime, false)

        player?.release()
        if (voice == null) { stopAll(); return START_NOT_STICKY }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                setDataSource(this@AdhanService, Uri.parse("android.resource://$packageName/${voice.res}"))
                setOnCompletionListener { stopAll(keepNotification = true, title = title, text = text) }
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) stopAll(keepNotification = true, title = title, text = text)
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String): Notification {
        ensureChannel(this)
        val stop = PendingIntent.getService(this, 3, Intent(this, AdhanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "⏹ إيقاف الأذان", stop).build())
            .build()
    }

    private fun stopAll(keepNotification: Boolean = false, title: String = "", text: String = "") {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (keepNotification) {
            // Leave a normal, dismissible notification behind once the adhan finishes.
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(open).build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        }
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "adhan_voice"
        private const val NOTIF_ID = 300
        const val ACTION_STOP = "com.alzahra.taqweem.STOP_ADHAN"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PRAYER = "prayer_name"
        const val EXTRA_PTIME = "prayer_time"

        /** Silent channel: the service plays the sound itself. */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "الأذان", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "تشغيل صوت الأذان عند دخول وقت الصلاة"
                ch.setSound(null, null)
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }

        fun start(ctx: Context, title: String, text: String, voiceId: String? = null, prayer: Prayer? = null, time: String = "") {
            val i = Intent(ctx, AdhanService::class.java)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            if (prayer != null) i.putExtra(EXTRA_PRAYER, prayer.name).putExtra(EXTRA_PTIME, time)
            if (voiceId != null) i.putExtra(EXTRA_VOICE, voiceId)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AdhanService::class.java).setAction(ACTION_STOP))
        }
    }
}
