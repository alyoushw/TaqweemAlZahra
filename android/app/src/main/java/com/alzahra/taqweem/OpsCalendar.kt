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
    /** cells: date -> [left, top, width, height] as fractions of the picture, to mark and tap each day. */
    data class Year(val year: Int, val image: String, val hijri: String, val cells: Map<String, List<Double>>)

    @Volatile private var cache: Pair<List<Year>, List<Mark>>? = null

    private fun load(ctx: Context): Pair<List<Year>, List<Mark>> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val o = JSONObject(ctx.assets.open("ops/ops.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val ys = o.getJSONArray("years").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { y ->
                    val c = y.optJSONObject("cells")
                    val cells = HashMap<String, List<Double>>()
                    c?.keys()?.forEach { k -> val v = c.getJSONArray(k); cells[k] = (0 until v.length()).map { v.getDouble(it) } }
                    Year(y.getInt("year"), y.getString("image"), y.getString("hijri"), cells)
                }
            }
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
    /** A day off begins (not a return to school). */
    fun isTimeOff(m: Mark) = m.kind in setOf("holiday", "resched", "national", "founding") || m.title.startsWith("إجازة") || m.title.startsWith("بداية إجازة")
    fun icon(m: Mark): String = when {
        m.kind == "ramadan" -> "🌙"
        m.kind == "national" || m.kind == "founding" -> "🇸🇦"
        isTimeOff(m) -> "🏖"
        else -> "📚"
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
        val nextHoliday = load(activity).second.filter { it.remind && isTimeOff(it) && !it.date.isBefore(today) }.minByOrNull { it.date } ?: list.first()
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

    /** What a tapped day holds: its holiday/school mark and the Zahra calendar's occasion that day. */
    fun dayInfo(activity: Activity, d: LocalDate) {
        val zahra = CalendarData.byDate(activity, d)
        Dialogs.sheet(activity, "📅", dateText(d), zahra?.let { CalendarData.hijriLabel(it) }) { col, dialog ->
            val ui = Ui(activity)
            val marks = marksOn(activity, d)
            if (marks.isEmpty()) col.addView(ui.text("يوم عادي في تقويم أرامكو (لا إجازة ولا حدث دراسي)", 14.5f, Colors.MUTED))
            marks.forEach { m ->
                col.addView(ui.text("${icon(m)}  ${m.title}", 16f, Color.WHITE, bold = true).apply {
                    setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.rounded(color(m.kind), 14)
                }, ui.match(0, 4, 0, 4))
            }
            if (zahra != null) {
                col.addView(ui.text("تقويم الزهراء", 13f, Colors.MUTED).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
                col.addView(ui.text(if (zahra.occasions.isEmpty()) "لا توجد مناسبة" else zahra.occasions.joinToString("\n") { it.text }, 15f, Colors.TEXT, bold = true).apply { setLineSpacing(0f, 1.25f) })
                col.addView(ui.outlineButton("تفاصيل اليوم في تقويم الزهراء") { dialog.dismiss(); Dialogs.day(activity, zahra) }, ui.match(0, 10, 0, 0))
            }
        }
    }

    /** The full published page, one per year, zoomable with two fingers. */
    @SuppressLint("SetJavaScriptEnabled")
    fun showPage(activity: Activity, focus: LocalDate? = null) {
        val ui = Ui(activity)
        val ys = years(activity)
        if (ys.isEmpty()) return
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.WHITE) }
        val head = ui.row().apply { setBackgroundColor(ui.theme.primary); setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12)) }
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(ui.text("تقويم أرامكو التشغيلي والمدرسي", 19f, ui.theme.accent, bold = true))
        ht.addView(ui.text("إعداد: أحمد الدحيم • اضغط أي يوم لتفاصيله", 12.5f, Color.parseColor("#E6FFFFFF")))
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
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun day(iso: String) {
                activity.runOnUiThread { runCatching { dayInfo(activity, LocalDate.parse(iso)) } }
            }
        }, "Z")
        fun show(y: Year) {
            chipViews.forEach { v ->
                val on = v.tag == y.year
                v.setTextColor(if (on) Color.WHITE else Colors.TEXT)
                v.background = ui.rounded(if (on) ui.theme.primary else Color.WHITE, 14, if (on) ui.theme.primary else Colors.STROKE, 1)
            }
            // The page as published, with an invisible tap area on every day; the chosen (or today's) day is framed.
            val mark = (focus ?: LocalDate.now()).toString()
            val zoomIn = focus != null && y.cells.containsKey(mark)
            val boxes = buildString {
                y.cells.forEach { (iso, c) ->
                    val cls = if (iso == mark) "d sel" else "d"
                    append("<div class='$cls' id='d$iso' style='left:${c[0] * 100}%;top:${c[1] * 100}%;width:${c[2] * 100}%;height:${c[3] * 100}%' onclick=\"Z.day('$iso')\"></div>")
                }
            }
            web.loadDataWithBaseURL("file:///android_asset/",
                "<html><head><meta name='viewport' content='width=device-width, initial-scale=${if (zoomIn) "2.4" else "1"}, minimum-scale=1, maximum-scale=6, user-scalable=yes'>" +
                    "<style>body{margin:0;background:#fff}#p{position:relative}#p img{width:100%;display:block}" +
                    ".d{position:absolute;box-sizing:border-box;border-radius:3px}" +
                    ".d.sel{border:3px solid #D81B60;box-shadow:0 0 0 3px #F8BBD0aa,0 0 12px #D81B60;animation:b 1.2s ease-in-out 3}" +
                    "@keyframes b{50%{box-shadow:0 0 0 6px #F8BBD0,0 0 18px #D81B60}}</style></head>" +
                    "<body><div id='p'><img src='${y.image}' onload='go()'>$boxes</div>" +
                    "<script>function go(){var e=document.querySelector('.sel');if(e&&$zoomIn)e.scrollIntoView({block:'center',inline:'center'});}</script></body></html>",
                "text/html", "utf-8", null)
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
        show(ys.firstOrNull { it.year == (focus ?: LocalDate.now()).year } ?: ys.last())
        dialog.setContentView(root)
        Immersive.padBottom(root)
        dialog.setOnDismissListener { web.destroy() }
        dialog.showImmersive()
    }
}
