package com.alzahra.taqweem

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks release/latest.json in the GitHub repository. When its versionCode is newer than the
 * installed one, offers to download the new APK (the same fixed link every time).
 */
object Updater {
    private const val BRANCH = "claude/zahra-calendar-errors-91f4b1"
    const val LATEST_JSON = "https://raw.githubusercontent.com/alyoushw/TaqweemAlZahra/$BRANCH/release/latest.json"
    const val APK_URL = "https://github.com/alyoushw/TaqweemAlZahra/raw/$BRANCH/release/taqweem-alzahra.apk"

    data class Release(val code: Int, val name: String, val apk: String, val notes: String)

    fun check(activity: Activity, manual: Boolean) {
        if (!manual && System.currentTimeMillis() - Prefs.lastUpdateCheck(activity) < 6 * 3600_000L) return
        Thread {
            val rel = runCatching { fetch() }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing) return@post
                Prefs.setLastUpdateCheck(activity, System.currentTimeMillis())
                when {
                    rel == null -> if (manual) Toast.makeText(activity, "تعذر الاتصال للتحقق من التحديثات", Toast.LENGTH_LONG).show()
                    rel.code > BuildConfig.VERSION_CODE -> offer(activity, rel)
                    manual -> Toast.makeText(activity, "لديك أحدث نسخة (${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetch(): Release {
        val conn = URL(LATEST_JSON + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)
            return Release(
                o.getInt("versionCode"), o.getString("versionName"),
                o.optString("apkUrl", APK_URL), o.optString("notes", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(activity: Activity, rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle("تحديث جديد متوفر (${rel.name})")
            .setMessage(
                (if (rel.notes.isNotBlank()) rel.notes + "\n\n" else "") +
                    "سيتم تنزيل النسخة الجديدة، وبعد اكتمال التنزيل افتح الملف واضغط «تثبيت».",
            )
            .setPositiveButton("تنزيل التحديث") { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk)))
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }
}
