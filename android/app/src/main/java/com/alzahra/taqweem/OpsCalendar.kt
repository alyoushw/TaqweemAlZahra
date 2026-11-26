package com.alzahra.taqweem

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The operational and school calendar (holidays, Ramadan, school terms) by Ahmed Al-Duhaim, used as a
 * reference beside the Zahra calendar: the full page as published, upcoming events, reminders before
 * them, and a small tag in the main table. Data: assets/ops/ops.json + one picture per year.
 */
object OpsCalendar {
    /** k: holiday (green), ramadan (blue), resched (orange: moved day off), national (red), founding (slate), school (grey). */
    data class Mark(val date: LocalDate, val kind: String, val title: String, val remind: Boolean)
    data class Year(val year: Int, val image: String, val hijri: String)

    @Volatile private var cache: Pair<List<Year>, List<Mark>>? = null

    private fun load(ctx: Context): Pair<List<Year>, List<Mark>> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val o = JSONObject(ctx.assets.open("ops/ops.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val ys = o.getJSONArray("years").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Year(it.getInt("year"), it.getString("image"), it.getString("hijri")) } }
            val ds = o.getJSONArray("days").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { Mark(LocalDate.parse(it.getString("d")), it.getString("k"), it.getString("t"), it.optBoolean("r")) }
            }
            ys to ds
        }.getOrDefault(emptyList<Year>() to emptyList()).also { cache = it }
    }

    fun years(ctx: Context) = load(ctx).first
    fun marksOn(ctx: Context, d: LocalDate) = load(ctx).second.filter { it.date == d }
    /** Events worth a reminder (first day of each holiday, school dates…) from [from] on. */
    fun upcoming(ctx: Context, from: LocalDate = LocalDate.now(), n: Int = 5) =
        load(ctx).second.filter { it.remind && !it.date.isBefore(from) }.sortedBy { it.date }.take(n)

    fun color(kind: String): Int = Color.parseColor(when (kind) {
        "holiday" -> "#6AA84F"; "ramadan" -> "#2E75B6"; "resched" -> "#F6B21B"; "national" -> "#E8762D"
        "founding" -> "#6F86A6"; else -> "#9E9E9E"
    })
    fun icon(m: Mark): String = when (m.kind) {
        "ramadan" -> "🌙"; "national", "founding" -> "🇸🇦"; "holiday", "resched" -> "🏖"
        else -> if (m.title.contains("إجازة")) "🏖" else "📚"
    }

    // ---------- settings ----------
    private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    /** Days before an event that a reminder comes, and their names. */
    val LEADS = listOf(30 to "قبل شهر", 14 to "قبل أسبوعين", 7 to "قبل أسبوع", 1 to "قبل يوم")
    private val DEFAULT_LEADS = setOf(14, 1)
    fun leadOn(ctx: Context, days: Int) = sp(ctx).getBoolean("ops_lead_$days", days in DEFAULT_LEADS)
    fun setLead(ctx: Context, days: Int, on: Boolean) = sp(ctx).edit().putBoolean("ops_lead_$days", on).apply()
    fun inTable(ctx: Context) = sp(ctx).getBoolean("ops_in_table", true)
    fun setInTable(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("ops_in_table", on).apply()

    private fun leadText(days: Int) = when (days) { 1 -> "بكرة"; 7 -> "بعد أسبوع"; 14 -> "بعد أسبوعين"; 30 -> "بعد شهر"; else -> "بعد ${CalendarData.ar(days)} يوم" }

    /** Reminders that fall on [date] (for the alarm scheduler). */
    fun remindersOn(ctx: Context, date: LocalDate): List<Pair<Int, Mark>> =
        LEADS.map { it.first }.filter { leadOn(ctx, it) }.flatMap { lead -> load(ctx).second.filter { it.remind && it.date == date.plusDays(lead.toLong()) }.map { lead to it } }

    fun dateText(d: LocalDate) = "${CalendarData.WEEKDAYS[d.dayOfWeek.value] ?: ""} ${CalendarData.ar(d.dayOfMonth)} ${MONTHS_G[d.monthValue - 1]} ${CalendarData.ar(d.year)}"
    private val MONTHS_G = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

    fun showReminders(ctx: Context, date: LocalDate = LocalDate.now()) {
        val list = remindersOn(ctx, date)
        if (list.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "الإجازات والدراسة", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "تذكير قبل الإجازات وبداية الدراسة (التقويم التشغيلي)"
            })
        }
        val open = PendingIntent.getActivity(ctx, 60, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        list.forEach { (lead, m) ->
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("${icon(m)} ${leadText(lead)}: ${m.title}")
                .setContentText("${dateText(m.date)} — حسب التقويم التشغيلي")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(700 + (m.date.toEpochDay() % 200).toInt() * 4 + LEADS.indexOfFirst { it.first == lead }, n)
        }
    }
    private const val CHANNEL = "ops"

    // ---------- views ----------

    /** "The next holiday" card with the coming events, for the interactive tab. */
    fun upcomingCard(activity: Activity): View? {
        val ui = Ui(activity)
        val today = LocalDate.now()
        val list = upcoming(activity, today, 5)
        if (list.isEmpty()) return null
        val card = ui.card()
        val nextHoliday = list.firstOrNull { it.kind != "school" || it.title.contains("إجازة") } ?: list.first()
        val days = ChronoUnit.DAYS.between(today, nextHoliday.date)
        card.addView(ui.text("${icon(nextHoliday)} الإجازة القادمة", 13f, Colors.MUTED))
        card.addView(ui.text(nextHoliday.title, 18f, Color.parseColor("#2E7D32"), bold = true))
        card.addView(ui.text("${dateText(nextHoliday.date)} • " + if (days == 0L) "اليوم" else "باقي ${CalendarData.ar(days.toInt())} يوم", 13.5f, Colors.TEXT))
        card.addView(ui.text("📅 القادم في التقويم التشغيلي", 13f, Colors.MUTED).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
        list.forEach { m ->
            val r = ui.row().apply { setPadding(0, ui.dp(7), 0, ui.dp(7)) }
            r.addView(View(activity).apply { background = ui.rounded(color(m.kind), 4) }, LinearLayout.LayoutParams(ui.dp(11), ui.dp(11)).apply { marginEnd = ui.dp(8) })
            r.addView(ui.text(m.title, 14f, Colors.TEXT), ui.weight())
            r.addView(ui.text("${CalendarData.ar(m.date.dayOfMonth)} ${MONTHS_G[m.date.monthValue - 1]}", 12.5f, ui.ink, bold = true).apply {
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2)); background = ui.rounded(Colors.SOFT, 10)
            })
            card.addView(r)
        }
        card.addView(ui.outlineButton("📅 فتح التقويم التشغيلي كامل") { showPage(activity) }, ui.match(0, 10, 0, 0))
        return card
    }

    /** The full published page, one per year, zoomable with two fingers. */
    @SuppressLint("SetJavaScriptEnabled")
    fun showPage(activity: Activity) {
        val ui = Ui(activity)
        val ys = years(activity)
        if (ys.isEmpty()) return
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.WHITE) }
        val head = ui.row().apply { setBackgroundColor(ui.theme.primary); setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12)) }
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(ui.text("التقويم التشغيلي والمدرسي", 19f, ui.theme.accent, bold = true))
        ht.addView(ui.text("إعداد: أحمد الدحيم", 12.5f, Color.parseColor("#E6FFFFFF")))
        head.addView(ht, ui.weight())
        head.addView(ui.text("✕", 24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        root.addView(head)
        val chips = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        root.addView(chips)
        val web = WebView(activity).apply {
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.WHITE)
            webViewClient = WebViewClient()
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val chipViews = ArrayList<android.widget.TextView>()
        fun show(y: Year) {
            chipViews.forEach { v ->
                val on = v.tag == y.year
                v.setTextColor(if (on) Color.WHITE else Colors.TEXT)
                v.background = ui.rounded(if (on) ui.theme.primary else Color.WHITE, 14, if (on) ui.theme.primary else Colors.STROKE, 1)
            }
            web.loadDataWithBaseURL("file:///android_asset/",
                "<html><head><meta name='viewport' content='width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=6, user-scalable=yes'></head>" +
                    "<body style='margin:0;background:#fff'><img src='${y.image}' style='width:100%;display:block'></body></html>", "text/html", "utf-8", null)
        }
        ys.forEach { y ->
            val v = ui.text("${y.year}", 15f, Colors.TEXT, bold = true).apply {
                tag = y.year
                setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(6))
                setOnClickListener { show(y) }
            }
            chipViews += v
            chips.addView(v, ui.wrap(0, 0, 8, 0))
        }
        chips.addView(ui.text("🤏 قرّب بإصبعين", 12.5f, Colors.MUTED).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }, ui.weight())
        show(ys.firstOrNull { it.year == LocalDate.now().year } ?: ys.last())
        dialog.setContentView(root)
        Immersive.padBottom(root)
        dialog.setOnDismissListener { web.destroy() }
        dialog.showImmersive()
    }
}
