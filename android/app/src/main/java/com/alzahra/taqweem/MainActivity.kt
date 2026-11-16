package com.alzahra.taqweem

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var player: MediaPlayer? = null

    private var tab = 0
    private var month = 1
    private var zoom = 1f
    private var pagesWeb: WebView? = null
    private var tableWeb: WebView? = null
    private var pagesBar: MonthBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        Notifier.ensureChannels(this)
        month = savedInstanceState?.getInt("month") ?: CalendarData.currentMonth(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        zoom = savedInstanceState?.getFloat("zoom") ?: 1f

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ui.theme.pageBg)
        }
        root.addView(header())
        updateBanner = ui.text("", 15f, Color.parseColor("#2A1A00"), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setBackgroundColor(ui.theme.accent)
            visibility = View.GONE
        }
        root.addView(updateBanner)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Immersive.apply(window)
        selectTab(tab)

        if (savedInstanceState == null) {
            askNotificationPermission()
        }
        AlarmScheduler.scheduleNext(this)
        ZahraWidget.updateAll(this)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(window)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("month", month)
        out.putInt("tab", tab)
        out.putFloat("zoom", zoom)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ZahraWidget.ACTION_WIDGET_SETTINGS) {
            i.action = null
            content.post { Dialogs.widgetSettings(this) }
        }
    }

    private var updateBanner: TextView? = null

    private fun showUpdateBanner(rel: Updater.Release) {
        updateBanner?.apply {
            text = "🔔 تحديث جديد متوفر (${rel.name}) — اضغط هنا للتحديث"
            visibility = View.VISIBLE
            setOnClickListener { Updater.install(this@MainActivity, rel) }
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.check(this, manual = false) { showUpdateBanner(it) }
        if (tab == 1) selectTab(1)
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    // ================= header + tabs =================

    private fun header(): View {
        val t = ui.theme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(t.primary)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        val r1 = ui.row()
        r1.addView(ui.text("تقويم الزهراء - الأحساء", 22f, t.accent, bold = true), ui.weight())
        r1.addView(icon("🧭", 22f) { Qibla.show(this) })
        r1.addView(icon("🎨", 22f) { Dialogs.theme(this) { recreate() } })
        r1.addView(icon("▦", 24f, t.accent) { Dialogs.widgetSettings(this) })
        box.addView(r1)

        val r2 = ui.row()
        r2.addView(ui.text("للتواصل والدعم", 14f, Color.parseColor("#E6FFFFFF")).apply {
            setPadding(0, ui.dp(4), ui.dp(8), ui.dp(4))
            setOnClickListener { Dialogs.contact(this@MainActivity) }
        }, ui.weight())
        val today = CalendarData.byDate(this, LocalDate.now())
        val pill = if (today != null) "اليوم: ${today.zahra} ${CalendarData.MONTHS[today.month - 1]} (${CalendarData.weekday(today)})" else "تقويم ١٤٤٨هـ"
        r2.addView(ui.text(pill, 14f, Color.parseColor("#2A1A00"), bold = true).apply {
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            background = ui.rounded(t.accent, 12)
            setOnClickListener { goToday() }
        })
        box.addView(r2, ui.match(0, 4, 0, 10))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.rounded(t.primaryDark, 16, t.accent, 1)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        listOf("تقويم الزهراء المرفق", "التقويم التفاعلي", "التقويم الكامل").forEachIndexed { i, label ->
            val v = ui.text(label, 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                setOnClickListener { selectTab(i) }
            }
            tabViews += v
            tabs.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(tabs)
        return box
    }

    private fun icon(glyph: String, size: Float, color: Int = Color.WHITE, onClick: () -> Unit) = ui.text(glyph, size, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        setOnClickListener { onClick() }
    }

    private fun selectTab(i: Int) {
        tab = i
        stopTicker()
        player?.release(); player = null
        tabViews.forEachIndexed { j, v ->
            v.background = if (j == i) ui.rounded(ui.theme.accent, 12) else null
            v.setTextColor(if (j == i) Color.parseColor("#2A1A00") else Color.WHITE)
        }
        content.removeAllViews()
        pagesWeb = null
        tableWeb = null
        content.addView(
            when (i) {
                0 -> tableTab()
                1 -> interactiveTab()
                else -> pagesTab()
            },
        )
    }

    private fun goToday() {
        val now = CalendarData.currentMonth(this)
        when {
            tab == 2 -> pagesWeb?.let { PagesView.scrollTo(it, now); pagesBar?.select(now) }
            tab == 0 && month == now && tableWeb != null -> tableWeb?.let { w -> w.evaluateJavascript("goToday()") { TableWeb.toRightEdge(w) } }
            else -> { month = now; selectTab(0) }
        }
    }

    // ================= tab 1: the PDF-like table =================

    /** Month chips; [select] re-highlights a month (and scrolls it into view) without rebuilding. */
    private inner class MonthBar(selected: Int, includeNotes: Boolean, onPick: (Int) -> Unit) {
        val chips = ArrayList<TextView>()
        val view: HorizontalScrollView
        private var current = selected

        init {
            val bar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
            }
            CalendarData.MONTHS.forEachIndexed { i, name ->
                val c = ui.chip(name, i + 1 == selected) { onPick(i + 1) }
                chips += c
                bar.addView(c)
            }
            if (includeNotes) bar.addView(ui.chip("ⓘ التنبيهات", false) { onPick(13) })
            view = HorizontalScrollView(this@MainActivity).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#F1E9DC"))
                addView(bar)
            }
            view.post { center(selected, false) }
        }

        fun select(m: Int) {
            if (m !in 1..12 || m == current) return
            current = m
            chips.forEachIndexed { i, c ->
                val sel = i + 1 == m
                c.setTextColor(if (sel) Color.WHITE else Colors.TEXT)
                c.typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                c.background = if (sel) ui.rounded(ui.theme.primary, 14) else ui.rounded(Color.WHITE, 14, Color.parseColor("#E3D6C2"), 1)
            }
            center(m, true)
        }

        private fun center(m: Int, smooth: Boolean) {
            val v = chips.getOrNull(m - 1) ?: return
            val x = (v.left - view.width / 2 + v.width / 2).coerceAtLeast(0)
            if (smooth) view.smoothScrollTo(x, 0) else view.scrollTo(x, 0)
        }
    }

    private fun tableTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(MonthBar(month, true) { m ->
            if (m == 13) Dialogs.page(this, 13) else { month = m; selectTab(0) }
        }.view)

        // Toolbar
        val tools = ui.row().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8)) }
        tools.addView(ui.text("⚠ تنبيه السيستاني", 14f, Color.WHITE, bold = true).apply {
            setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            background = ui.rounded(Color.parseColor("#D32F2F"), 12)
            setOnClickListener { Dialogs.sistani(this@MainActivity) }
        }, ui.wrap(0, 0, 6, 0))
        tools.addView(ui.text("📅 اليوم الحالي", 14f, Color.WHITE, bold = true).apply {
            setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            background = ui.rounded(ui.theme.primary, 12)
            setOnClickListener { goToday() }
        }, ui.wrap(0, 0, 6, 0))
        val p = ui.theme.primary
        tools.addView(icon("🧭", 20f) { Qibla.show(this) })
        tools.addView(icon("🎨", 20f) { Dialogs.theme(this) { recreate() } })
        tools.addView(icon("🔔", 20f) { Dialogs.notifications(this) })
        tools.addView(icon("▦", 22f, Color.parseColor("#C9A227")) { Dialogs.widgetSettings(this) })
        tools.addView(icon("＋", 22f, p) { tableWeb?.zoomIn() })
        tools.addView(icon("－", 22f, p) { tableWeb?.zoomOut() })
        tools.addView(icon("⟳", 22f, p) { selectTab(0) })
        col.addView(HorizontalScrollView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isHorizontalScrollBarEnabled = false
            addView(tools)
        })

        // Banner (as printed across the top of each page)
        val banner = ui.row().apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = ui.rounded(Color.parseColor("#FFF8EA"), 12, ui.theme.accent, 2)
        }
        fun box(s: String, bg: Int, fg: Int, w: Float) = banner.addView(ui.text(s, 12.5f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = ui.rounded(bg, 8, Color.parseColor("#D9CBB3"), 1)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        box("سما العمران للسياحة • الأحساء", Color.parseColor("#F5EFE3"), ui.theme.primary, 1f)
        box("تقويم الزهراء (سنة ٤١)", ui.theme.primary, ui.theme.accent, 1.1f)
        box("شهر ${CalendarData.MONTHS[month - 1]} ${CalendarData.ar(CalendarData.YEAR)} هـ", Color.parseColor("#F5EFE3"), ui.theme.primary, 1f)
        col.addView(banner, ui.match(8, 0, 8, 6))

        // The table itself: pinch with two fingers to zoom, drag to move in any direction.
        val web = TableWeb.create(this, month) {}
        tableWeb = web
        col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ================= tab 2: interactive =================

    private fun interactiveTab(): View {
        val col = ui.column(12)
        val day = CalendarData.byDate(this, LocalDate.now())
        if (day != null) {
            col.addView(ui.card().apply {
                val r = ui.row()
                val (bg, fg) = Colors.zahraCell(day.zahraColor)
                r.addView(ui.text(CalendarData.ar(day.zahra), 30f, fg, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(if (bg == Color.TRANSPARENT) Color.parseColor("#F5EFE3") else bg, 12, Color.parseColor("#D9CBB3"), 1)
                }, LinearLayout.LayoutParams(ui.dp(66), ui.dp(66)))
                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, 0, 0) }
                info.addView(ui.text(CalendarData.weekday(day), 15f, Colors.MUTED))
                info.addView(ui.text(CalendarData.hijriLabel(day), 21f, ui.theme.primary, bold = true))
                info.addView(ui.text("${CalendarData.gregLabel(day.greg)}م  •  أم القرى: ${CalendarData.ar(day.ummAlQura)}", 14f, Colors.MUTED))
                r.addView(info, ui.weight())
                addView(r)
                setOnClickListener { Dialogs.day(this@MainActivity, day) }
            })

            val nextName = ui.text("", 16f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            val nextTimer = ui.text("--:--:--", 32f, ui.theme.accent, bold = true).apply { gravity = Gravity.CENTER }
            col.addView(ui.card(ui.theme.primary, ui.theme.accent).apply { addView(nextName); addView(nextTimer) })
            startTicker {
                val next = CalendarData.nextPrayer(this)
                if (next != null) {
                    val t = next.at.toLocalTime()
                    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
                    nextName.text = "⏳ الوقت القادم: ${next.prayer.label} ($h:${"%02d".format(t.minute)})"
                    val s = Duration.between(LocalDateTime.now(), next.at).seconds.coerceAtLeast(0)
                    nextTimer.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                }
            }

            if (day.occasions.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                day.occasions.forEachIndexed { i, o ->
                    if (i > 0) sb.append("\n")
                    val s = sb.length
                    sb.append("• ").append(o.text)
                    sb.setSpan(ForegroundColorSpan(Colors.occasion(o.color)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                col.addView(ui.card().apply {
                    addView(ui.title("مناسبة اليوم"))
                    addView(ui.text(sb, 16f, Colors.TEXT, bold = true).apply { setLineSpacing(0f, 1.3f) })
                })
            }

            col.addView(ui.card().apply {
                addView(ui.title("مواقيت اليوم - الأحساء"))
                val t = day.times
                listOf(listOf("الإمساك" to t.imsak, "الصبح" to t.fajr, "الشروق" to t.sunrise), listOf("الظهر" to t.dhuhr, "المغرب" to t.maghrib, "منتصف الليل" to t.midnight)).forEach { chunk ->
                    val r = ui.row()
                    chunk.forEach { (l, v) ->
                        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ui.rounded(Color.parseColor("#F5EFE3"), 10); setPadding(0, ui.dp(8), 0, ui.dp(8)) }
                        c.addView(ui.text(l, 12f, Colors.MUTED).apply { gravity = Gravity.CENTER })
                        c.addView(ui.text(v, 19f, ui.theme.primary, bold = true).apply { gravity = Gravity.CENTER })
                        r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
                    }
                    addView(r)
                }
                addView(ui.text("عقد النكاح: ${day.mark.ifBlank { "-" }}   •   السكن: ${day.sakan.ifBlank { "-" }}   •   الحجامة: ${day.hijama}   •   الطالع: ${day.tale}", 13f, Colors.TEXT, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
            })
        }

        col.addView(ui.card(Color.parseColor("#FFFBF3"), ui.theme.primary).apply {
            addView(ui.title("🔔 إشعارات مواقيت الصلاة (خارج التطبيق)"))
            Dialogs.notificationSettings(this@MainActivity, this)
        })

        col.addView(ui.card().apply {
            addView(ui.title("🤲 الأدعية والزيارات"))
            Duas.ALL.forEach { dua -> addView(ui.outlineButton(dua.title) { showDua(dua) }) }
        })

        col.addView(ui.card().apply {
            addView(ui.title("التحديثات"))
            addView(ui.text("الإصدار الحالي: ${BuildConfig.VERSION_NAME}", 14f, Colors.TEXT))
            addView(ui.primaryButton("التحقق من وجود تحديث") { Updater.check(this@MainActivity, manual = true) { showUpdateBanner(it) } })
            addView(ui.outlineButton("مشاركة رابط تحميل التطبيق") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "تطبيق تقويم الزهراء - الأحساء ١٤٤٨هـ\n${Updater.APK_URL}")
                }, "مشاركة التطبيق"))
            })
            addView(ui.outlineButton("للتواصل والدعم") { Dialogs.contact(this@MainActivity) })
        })
        return ui.scroll(col)
    }

    private fun showDua(dua: Dua) {
        Dialogs.sheet(this, "🤲", dua.title, null) { col, dialog ->
            val hasAudio = runCatching { assets.list("audio")?.contains(dua.audio) == true }.getOrDefault(false)
            val play = ui.primaryButton(if (hasAudio) "▶ تشغيل الصوت" else "الصوت سيضاف في التحديث القادم") {}
            if (hasAudio) play.setOnClickListener {
                val pl = player
                if (pl != null && pl.isPlaying) { pl.pause(); play.text = "▶ تشغيل الصوت" }
                else if (pl != null) { pl.start(); play.text = "⏸ إيقاف مؤقت" }
                else runCatching {
                    val afd = assets.openFd("audio/${dua.audio}")
                    player = MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                        setOnCompletionListener { play.text = "▶ تشغيل الصوت" }
                        prepare(); start()
                    }
                    play.text = "⏸ إيقاف مؤقت"
                }.onFailure { Toast.makeText(this, "تعذر تشغيل الصوت", Toast.LENGTH_LONG).show() }
            }
            col.addView(play)
            col.addView(ui.text(dua.text, 21f, Colors.TEXT).apply { typeface = android.graphics.Typeface.SERIF; setLineSpacing(0f, 1.6f); setPadding(0, ui.dp(8), 0, 0) })
            dialog.setOnDismissListener { player?.release(); player = null }
        }
    }

    // ================= tab 3: full scanned calendar =================

    private fun pagesTab(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val zoomLabel = ui.text("100%", 15f, Color.parseColor("#2A1A00"), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            background = ui.rounded(ui.theme.accent, 16)
        }
        lateinit var bar: MonthBar
        val web = PagesView.all(this, month, { pct -> zoomLabel.text = "$pct%" }) { visible ->
            if (visible in 1..12) { month = visible; bar.select(visible) }
        }
        pagesWeb = web
        bar = MonthBar(month, true) { m ->
            if (m in 1..12) { month = m; bar.select(m) }
            PagesView.scrollTo(web, m)
        }
        pagesBar = bar
        box.addView(bar.view)

        val frame = FrameLayout(this)
        frame.addView(web)
        val zbar = ui.row().apply {
            background = ui.rounded(Color.parseColor("#E61E1E1E"), 30, ui.theme.accent, 1)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        }
        zbar.addView(icon("🔍+", 18f) { web.zoomIn() })
        zbar.addView(zoomLabel, ui.wrap(6, 0, 6, 0))
        zbar.addView(icon("🔍−", 18f) { web.zoomOut() })
        frame.addView(zbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(16) })
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return box
    }

    // ================= ticker =================

    private fun startTicker(tick: () -> Unit) {
        stopTicker()
        val r = object : Runnable {
            override fun run() { tick(); handler.postDelayed(this, 1000) }
        }
        ticker = r
        r.run()
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }
}
