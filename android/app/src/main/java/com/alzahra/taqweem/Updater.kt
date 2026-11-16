package com.alzahra.taqweem

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates: every time the app comes to the front it reads release/latest.json from GitHub.
 * When a newer versionCode is published it shows a banner and a dialog; "تحديث الآن" downloads the
 * APK inside the app and hands it to Android's installer.
 */
object Updater {
    private const val REPO = "alyoushw/TaqweemAlZahra"
    private const val BRANCH = "claude/zahra-calendar-errors-91f4b1"
    private const val API_JSON = "https://api.github.com/repos/$REPO/contents/release/latest.json?ref=$BRANCH"
    private const val RAW_JSON = "https://raw.githubusercontent.com/$REPO/$BRANCH/release/latest.json"
    const val APK_URL = "https://github.com/$REPO/raw/$BRANCH/release/taqweem-alzahra.apk"

    data class Release(val code: Int, val name: String, val apk: String, val notes: String)

    private var lastCheck = 0L
    private var dialogShownFor = 0
    var available: Release? = null
        private set

    /**
     * @param manual true when the user pressed "check for updates" (always reports a result).
     * @param onFound called on the main thread when a newer version exists (used for the banner).
     */
    fun check(activity: Activity, manual: Boolean, onFound: (Release) -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastCheck < 60_000) {
            available?.let(onFound)
            return
        }
        lastCheck = now
        Thread {
            val rel = runCatching { fetch(API_JSON, github = true) }.recoverCatching { fetch(RAW_JSON, github = false) }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                when {
                    rel == null -> if (manual) Toast.makeText(activity, "تعذر الاتصال للتحقق من التحديثات", Toast.LENGTH_LONG).show()
                    rel.code > BuildConfig.VERSION_CODE -> {
                        available = rel
                        onFound(rel)
                        if (manual || dialogShownFor != rel.code) {
                            dialogShownFor = rel.code
                            offer(activity, rel)
                        }
                    }
                    manual -> Toast.makeText(activity, "لديك أحدث نسخة (${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetch(url: String, github: Boolean): Release {
        val conn = URL(if (github) url else "$url?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        if (github) conn.setRequestProperty("Accept", "application/vnd.github.raw")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)
            return Release(o.getInt("versionCode"), o.getString("versionName"), o.optString("apkUrl", APK_URL), o.optString("notes", ""))
        } finally {
            conn.disconnect()
        }
    }

    fun offer(activity: Activity, rel: Release) {
        val d = AlertDialog.Builder(activity)
            .setTitle("🔔 تحديث جديد متوفر (${rel.name})")
            .setMessage((if (rel.notes.isNotBlank()) rel.notes + "\n\n" else "") + "نسختك الحالية: ${BuildConfig.VERSION_NAME}")
            .setPositiveButton("تحديث الآن") { _, _ -> install(activity, rel) }
            .setNegativeButton("لاحقاً", null)
            .create()
        d.show()
    }

    /** Downloads the APK inside the app, then opens Android's install screen. */
    fun install(activity: Activity, rel: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("السماح بتثبيت التحديث")
                .setMessage("لتثبيت التحديثات من داخل التطبيق، فعّل خيار «السماح من هذا المصدر» لتطبيق تقويم الزهراء ثم ارجع واضغط «تحديث الآن».")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("تنزيل من المتصفح") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk))) }
                .show()
            return
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; setPadding(48, 24, 48, 24) }
        val progress = AlertDialog.Builder(activity).setTitle("جاري تنزيل التحديث…").setView(bar).setCancelable(false).show()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    var conn = URL(rel.apk).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    // github.com/raw redirects to another host; follow it by hand if needed.
                    var hops = 0
                    while (conn.responseCode in 300..399 && hops++ < 5) {
                        val next = conn.getHeaderField("Location")
                        conn.disconnect()
                        conn = URL(URL(rel.apk), next).openConnection() as HttpURLConnection
                    }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        session.openWrite("taqweem.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) main.post { bar.progress = (done * 100 / total).toInt() }
                            }
                            session.fsync(out)
                        }
                    }
                    conn.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(activity, 7, Intent(activity, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            }
            main.post {
                runCatching { progress.dismiss() }
                result.onFailure {
                    Toast.makeText(activity, "تعذر تنزيل التحديث، سيتم فتحه في المتصفح", Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk)))
                }
            }
        }.start()
    }
}

/** Receives the installer's result; shows Android's confirmation screen when it asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(context, "لم يكتمل التثبيت: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}", Toast.LENGTH_LONG).show()
        }
    }
}
