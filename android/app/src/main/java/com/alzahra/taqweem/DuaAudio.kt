package com.alzahra.taqweem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * The recitation player lives here, outside the dua page, so the recitation keeps playing when the
 * user leaves the app or turns the screen off. [DuaAudioService] keeps it alive with a notification
 * (pause/resume, stop). Leaving the dua page with «عودة» or back stops it.
 */
object DuaAudio {
    var player: MediaPlayer? = null
    var prepared = false
    /** The dua or ziyarah the current player belongs to. */
    var prayerId: String? = null
    var reciter: String? = null
    var title = ""
    var reciterName = ""

    fun isPlaying() = prepared && player?.isPlaying == true

    /** A new player for [id]; any other recitation stops first. */
    fun newPlayer(ctx: Context, id: String, title: String, reciter: String?, reciterName: String): MediaPlayer {
        release(ctx)
        prayerId = id; this.title = title; this.reciter = reciter; this.reciterName = reciterName
        return MediaPlayer().apply {
            setWakeMode(ctx.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player = this
        }
    }

    fun release(ctx: Context) {
        player?.release(); player = null; prepared = false; prayerId = null; reciter = null
        ctx.stopService(Intent(ctx, DuaAudioService::class.java))
    }

    fun toggle(ctx: Context) {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.start()
        update(ctx)
    }

    /** Show or refresh the playback notification. */
    fun update(ctx: Context) {
        if (player == null) return
        val i = Intent(ctx, DuaAudioService::class.java).setAction(DuaAudioService.ACTION_UPDATE)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

class DuaAudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> DuaAudio.toggle(this)
            ACTION_STOP -> { DuaAudio.release(this); stopSelf(); return START_NOT_STICKY }
        }
        if (DuaAudio.player == null) { stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        // Paused: the notification can be swiped away; playing: it stays.
        if (!DuaAudio.isPlaying() && Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "تلاوة الأدعية", NotificationManager.IMPORTANCE_LOW).apply {
                description = "تشغيل الدعاء في الخلفية"
                setShowBadge(false)
            })
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 40, it, flags)
        }
        val toggle = PendingIntent.getService(this, 41, Intent(this, DuaAudioService::class.java).setAction(ACTION_TOGGLE), flags)
        val stop = PendingIntent.getService(this, 42, Intent(this, DuaAudioService::class.java).setAction(ACTION_STOP), flags)
        val playing = DuaAudio.isPlaying()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🎙 ${DuaAudio.title}")
            .setContentText(DuaAudio.reciterName)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, if (playing) "⏸ إيقاف مؤقت" else "▶ تشغيل", toggle).build())
            .addAction(Notification.Action.Builder(null, "⏹ إنهاء", stop).build())
            .build()
    }

    companion object {
        const val CHANNEL = "dua_audio"
        private const val NOTIF_ID = 310
        const val ACTION_UPDATE = "com.alzahra.taqweem.DUA_UPDATE"
        const val ACTION_TOGGLE = "com.alzahra.taqweem.DUA_TOGGLE"
        const val ACTION_STOP = "com.alzahra.taqweem.DUA_STOP"
    }
}
