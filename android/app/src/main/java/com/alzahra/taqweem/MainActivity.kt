package com.alzahra.taqweem

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Three tabs:
 *  0  تقويم الزهراء المرفق — month bar + the zoomable month table (main screen)
 *  1  التقويم التفاعلي     — today, duas, and the month's occasions log
 *  2  التقويم الكامل       — the scanned pages
 * Everything else (theme, widget, notifications, qibla, updates, contact) lives behind ⚙ الإعدادات.
 */
class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var player: MediaPlayer? = null
    private var updateBanner: TextView? = null

    private var tab = 0
    private var month = 1
    private var pagesWeb: WebView? = null
    private var tableWeb: WebView? = null
    private var pagesBar: MonthBar? = null

    // ================= lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        Notifier.ensureChannels(this)
        month = savedInstanceState?.getInt("month") ?: CalendarData.currentMonth(this)
        tab = savedInstanceState?.getInt("tab") ?: 0

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

        if (savedInstanceState == null) askNotificationPermission()
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

    override fun onResume() {
        super.onResume()
        Updater.check(this, manual = false) { showUpdateBanner(it) }
        Recitations.refresh(this)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21) Qibla.onPermissionResult()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun showUpdateBanner(rel: Updater.Release) {
        updateBanner?.apply {
            text = "🔔 تحديث جديد متوفر (${rel.name}) — اضغط هنا للتحديث"
            visibility = View.VISIBLE
            setOnClickListener { Updater.install(this@MainActivity, rel) }
        }
    }

    // ================= header + tabs =================

    private fun header(): View {
        val t = ui.theme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(t.primary)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(12))
        }
        val r1 = ui.row()
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ui.text("تقويم الزهراء - الأحساء", 21f, t.accent, bold = true))
        val today = CalendarData.byDate(this, LocalDate.now())
        val sub = if (today != null) "${CalendarData.weekday(today)} • ${today.zahra} ${CalendarData.MONTHS[today.month - 1]} ${CalendarData.ar(CalendarData.YEAR)}هـ" else "١٤٤٨هـ"
        titles.addView(ui.text(sub, 14f, Color.parseColor("#E6FFFFFF")).apply {
            setPadding(0, ui.dp(2), 0, 0)
            setOnClickListener { goToday() }
        })
        r1.addView(titles, ui.weight())
        r1.addView(ui.text("اليوم", 14f, Color.parseColor("#2A1A00"), bold = true).apply {
            setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
            background = ui.rounded(t.accent, 12)
            setOnClickListener { goToday() }
        }, ui.wrap(0, 0, 8, 0))
        r1.addView(KaabaButton(this, t.accent).apply {
            background = ui.rounded(Color.parseColor("#33FFFFFF"), 12)
            contentDescription = "اتجاه القبلة"
            setOnClickListener { Qibla.show(this@MainActivity) }
        }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { marginEnd = ui.dp(8) })
        r1.addView(ui.text("⚙", 24f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Color.parseColor("#33FFFFFF"), 12)
            setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        box.addView(r1)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.rounded(t.primaryDark, 14)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        listOf("تقويم الزهراء المرفق", "التقويم التفاعلي", "التقويم الكامل").forEachIndexed { i, label ->
            val v = ui.text(label, 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabViews += v
            tabs.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(tabs, ui.match(0, 12, 0, 0))
        return box
    }

    private fun selectTab(i: Int) {
        tab = i
        stopTicker()
        player?.release(); player = null
        tabViews.forEachIndexed { j, v ->
            v.background = if (j == i) ui.rounded(ui.theme.accent, 11) else null
            v.setTextColor(if (j == i) Color.parseColor("#2A1A00") else Color.parseColor("#D9FFFFFF"))
        }
        content.removeAllViews()
        pagesWeb = null
        tableWeb = null
        pagesBar = null
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
            else -> { month = now; selectTab(tab) }
        }
    }

    // ================= settings =================

    private fun settings() {
        Dialogs.sheet(this, "⚙", "الإعدادات", null) { col, dialog ->
            fun row(icon: String, title: String, subtitle: String, action: () -> Unit) {
                val r = ui.row().apply {
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    background = ui.rounded(Colors.CARD, 16, Colors.STROKE, 1)
                    setOnClickListener { dialog.dismiss(); action() }
                }
                r.addView(ui.text(icon, 22f, ui.ink).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
                val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
                tx.addView(ui.text(title, 16f, Colors.TEXT, bold = true))
                tx.addView(ui.text(subtitle, 12.5f, Colors.MUTED))
                r.addView(tx, ui.weight())
                r.addView(ui.text("‹", 26f, Colors.MUTED))
                col.addView(r, ui.match(0, 4, 0, 4))
            }
            fun group(title: String) = col.addView(ui.text(title, 13f, Colors.MUTED, bold = true), ui.match(4, 12, 4, 2))

            group("المظهر والشاشة الرئيسية")
            row("🎨", "مظهر التطبيق", "اختيار اللون: ${ui.theme.title}") { Dialogs.theme(this) { recreate() } }
            row("▦", "ودجت الشاشة الرئيسية", "المقاس والألوان والمحتوى") { Dialogs.widgetSettings(this) }
            group("الصلاة")
            // Fajr precaution toggle with an info button.
            val fr = ui.row().apply {
                setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10))
                background = ui.rounded(Colors.CARD, 16, Colors.STROKE, 1)
            }
            fr.addView(ui.text("🌅", 22f, ui.ink).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            val ft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
            ft.addView(ui.text("تأخير صلاة الفجر ١٣ دقيقة للاحتياط", 15.5f, Colors.TEXT, bold = true))
            ft.addView(ui.text("يُطبَّق على التقويم والتنبيهات والودجت", 12.5f, Colors.MUTED))
            fr.addView(ft, ui.weight())
            fr.addView(ui.text("؟", 18f, ui.onFill, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.rounded(ui.fill, 18)
                setOnClickListener { Dialogs.fajrInfo(this@MainActivity) }
            }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
            val sw = android.widget.Switch(this)
            fr.setOnClickListener { sw.toggle() }
            fr.addView(sw.apply {
                isChecked = CalendarData.fajrDelayOn(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    CalendarData.setFajrDelay(this@MainActivity, on)
                    AlarmScheduler.scheduleNext(this@MainActivity)
                    ZahraWidget.updateAll(this@MainActivity)
                    Toast.makeText(this@MainActivity, if (on) "تم تأخير وقت الفجر ١٣ دقيقة" else "تم إرجاع وقت الفجر كما في التقويم", Toast.LENGTH_SHORT).show()
                    dialog.setOnDismissListener { selectTab(tab) }
                }
            })
            col.addView(fr, ui.match(0, 4, 0, 4))
            row("🔔", "الإشعارات والأذان", "تنبيهات الصلاة ومناسبة اليوم") { Dialogs.notifications(this) }
            row("🧭", "اتجاه القبلة", "البوصلة وخريطة الأقمار الصناعية") { Qibla.show(this) }
            row("⚠", "تنبيه السيستاني", "الاحتياط في الإمساك وصلاة الصبح") { Dialogs.sistani(this) }
            row("📄", "صفحة التنبيهات والإرشادات", "شرح رموز وألوان التقويم") { Dialogs.page(this, 13) }
            group("التطبيق")
            row("⬆", "التحديثات", "الإصدار الحالي ${BuildConfig.VERSION_NAME} — اضغط للتحقق") {
                Updater.check(this, manual = true) { showUpdateBanner(it) }
            }
            row("🔗", "مشاركة التطبيق", "إرسال رابط التحميل") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "تطبيق تقويم الزهراء - الأحساء ١٤٤٨هـ\n${Updater.APK_URL}")
                }, "مشاركة التطبيق"))
            }
            row("📞", "التواصل والدعم", Dialogs.CONTACT_NAME) { Dialogs.contact(this) }
        }
    }

    // ================= month bar =================

    /** Month chips; [select] re-highlights a month (and scrolls it into view) without rebuilding. */
    private inner class MonthBar(selected: Int, onPick: (Int) -> Unit) {
        val chips = ArrayList<TextView>()
        val view: HorizontalScrollView
        private var current = selected

        init {
            val bar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
            }
            CalendarData.MONTHS.forEachIndexed { i, name ->
                val c = ui.chip(name, i + 1 == selected) { onPick(i + 1) }
                chips += c
                bar.addView(c)
            }
            view = HorizontalScrollView(this@MainActivity).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                isHorizontalScrollBarEnabled = false
                addView(bar)
            }
            view.post { center(selected, false) }
        }

        fun select(m: Int) {
            if (m !in 1..12 || m == current) return
            current = m
            chips.forEachIndexed { i, c ->
                val sel = i + 1 == m
                c.setTextColor(if (sel) ui.onFill else Colors.TEXT)
                c.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                c.background = if (sel) ui.rounded(ui.fill, 14) else ui.rounded(Colors.CARD, 14, Colors.STROKE, 1)
            }
            center(m, true)
        }

        private fun center(m: Int, smooth: Boolean) {
            val v = chips.getOrNull(m - 1) ?: return
            val x = (v.left - view.width / 2 + v.width / 2).coerceAtLeast(0)
            if (smooth) view.smoothScrollTo(x, 0) else view.scrollTo(x, 0)
        }
    }

    // ================= tab 1: the table =================

    private fun tableTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(MonthBar(month) { m -> month = m; selectTab(0) }.view)
        // Pinch with two fingers to zoom; drag to move in any direction; tap a day for its details.
        val web = TableWeb.create(this, month) {}
        tableWeb = web
        col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ================= tab 2: interactive =================

    private fun interactiveTab(): View {
        val col = ui.column(14)
        val day = CalendarData.byDate(this, LocalDate.now())

        // Today
        if (day != null) {
            val card = ui.card(ui.theme.primary, ui.theme.primary).apply { setOnClickListener { Dialogs.day(this@MainActivity, day) } }
            val r = ui.row()
            r.addView(Dialogs.dayCircle(this, day, 58, onDark = true))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, 0, 0) }
            info.addView(ui.text("${CalendarData.weekday(day)} • ${CalendarData.gregLabel(day.greg)}م", 14f, Color.parseColor("#D9FFFFFF")))
            info.addView(ui.text("${CalendarData.ar(day.zahra)} ${CalendarData.MONTHS[day.month - 1]} ${CalendarData.ar(CalendarData.YEAR)}هـ", 21f, Color.WHITE, bold = true))
            r.addView(info, ui.weight())
            card.addView(r)

            val next = ui.text("", 15f, ui.theme.accent, bold = true).apply { setPadding(0, ui.dp(12), 0, ui.dp(8)) }
            card.addView(next)
            startTicker {
                CalendarData.nextPrayer(this)?.let { n ->
                    val s = Duration.between(LocalDateTime.now(), n.at).seconds.coerceAtLeast(0)
                    next.text = "⏳ باقي على ${n.prayer.label}:  %02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                }
            }
            val strip = ui.row().apply { background = ui.rounded(Color.parseColor("#33000000"), 12); setPadding(0, ui.dp(8), 0, ui.dp(8)) }
            listOf("الصبح" to day.times.fajr, "الشروق" to day.times.sunrise, "الظهر" to day.times.dhuhr, "المغرب" to day.times.maghrib, "المنتصف" to day.times.midnight).forEach { (l, v) ->
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                c.addView(ui.text(l, 11.5f, Color.parseColor("#CCFFFFFF")).apply { gravity = Gravity.CENTER })
                c.addView(ui.text(v, 16f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
                strip.addView(c, ui.weight())
            }
            card.addView(strip)
            if (day.occasions.isNotEmpty()) {
                card.addView(ui.text(day.occasions.joinToString("\n") { it.text }, 15f, Color.WHITE, bold = true).apply {
                    setPadding(0, ui.dp(10), 0, 0); setLineSpacing(0f, 1.25f)
                })
            }
            col.addView(card)
        }

        // Duas and ziyarat: two big buttons, each opens a list; a text opens on its own page.
        val pr = ui.row().apply { setPadding(0, ui.dp(14), 0, 0) }
        fun bigButton(icon: String, label: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ui.rounded(Colors.CARD, 18, ui.theme.accent, 1)
            setPadding(ui.dp(8), ui.dp(16), ui.dp(8), ui.dp(16))
            addView(ui.text(icon, 28f, ui.ink).apply { gravity = Gravity.CENTER })
            addView(ui.text(label, 17f, ui.ink, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text(sub, 12.5f, Colors.MUTED).apply { gravity = Gravity.CENTER })
            setOnClickListener { onClick() }
        }
        pr.addView(bigButton("🤲", "الأدعية", "${CalendarData.ar(Prayers.duas(this).size)} دعاء كامل") { prayerList("dua") }, ui.weight().apply { setMargins(0, 0, ui.dp(6), 0) })
        pr.addView(bigButton("🕌", "الزيارات", "${CalendarData.ar(Prayers.ziyarat(this).size)} زيارات كاملة") { prayerList("ziyarat") }, ui.weight().apply { setMargins(ui.dp(6), 0, 0, 0) })
        col.addView(pr)

        // Month occasions log
        val logTitle = ui.text("", 17f, ui.ink, bold = true)
        col.addView(logTitle, ui.match(4, 18, 4, 2))
        val logHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var bar: MonthBar
        fun fillLog(m: Int) {
            logTitle.text = "سجل مناسبات شهر ${CalendarData.MONTHS[m - 1]} ${CalendarData.ar(CalendarData.YEAR)} هـ"
            logHost.removeAllViews()
            CalendarData.month(this, m).forEach { d -> logHost.addView(logRow(d)) }
        }
        bar = MonthBar(month) { m -> month = m; bar.select(m); fillLog(m) }
        col.addView(bar.view, ui.match(-14, 0, -14, 4))
        col.addView(logHost)
        fillLog(month)
        return ui.scroll(col)
    }

    /** One day in the month log, styled like the original app (black card for sorrow days). */
    private fun logRow(d: Day): View {
        val isToday = d.greg == LocalDate.now()
        val dark = d.isSorrow
        val bg = when { dark -> Color.parseColor("#1E1E1E"); d.isJoy -> Colors.JOY_SOFT; isToday -> Colors.TODAY_SOFT; else -> Colors.CARD }
        val stroke = if (isToday) ui.theme.accent else if (dark) Color.parseColor("#3A3A3A") else Colors.STROKE
        val card = ui.row().apply {
            gravity = Gravity.TOP
            background = ui.rounded(bg, 16, stroke, if (isToday) 2 else 1)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            setOnClickListener { Dialogs.day(this@MainActivity, d) }
        }
        card.addView(Dialogs.dayCircle(this, d, 46, onDark = dark))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, 0, 0) }
        val top = ui.row()
        top.addView(ui.text("${CalendarData.weekday(d)} • ${d.greg.dayOfMonth}/${d.greg.monthValue}", 13f, if (dark) Color.parseColor("#BFBFBF") else Colors.MUTED), ui.weight())
        val tag = when {
            dark -> "🏴 شهادة / وفاة" to Color.WHITE
            d.isJoy -> "🌸 مولد / فرح" to Color.parseColor("#9A7400")
            d.mark.isNotBlank() -> "عقد النكاح: ${d.mark}" to (if (d.mark == "جيد") Colors.GOOD else Colors.BAD)
            else -> null
        }
        tag?.let { (s, c) -> top.addView(ui.text(s, 12.5f, c, bold = true)) }
        body.addView(top)
        val sb = SpannableStringBuilder()
        d.occasions.forEachIndexed { i, o ->
            if (i > 0) sb.append("\n")
            val s = sb.length
            sb.append(o.text)
            val c = if (dark) Color.WHITE else Colors.occasion(o.color)
            sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.addView(ui.text(sb, 15.5f, Colors.TEXT, bold = true).apply { setPadding(0, ui.dp(6), 0, 0); setLineSpacing(0f, 1.25f) })
        card.addView(body, ui.weight())
        val wrap = FrameLayout(this)
        wrap.addView(card)
        wrap.layoutParams = ui.match(0, 5, 0, 5)
        return wrap
    }

    private fun prayerList(kind: String) {
        val items = if (kind == "dua") Prayers.duas(this) else Prayers.ziyarat(this)
        Dialogs.sheet(this, if (kind == "dua") "🤲" else "🕌", if (kind == "dua") "الأدعية" else "الزيارات", "النص الكامل من مفاتيح الجنان") { col, dialog ->
            items.forEach { item ->
                val r = ui.row().apply {
                    background = ui.rounded(Colors.CARD, 14, Colors.STROKE, 1)
                    setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12))
                    // Keep the list open underneath, so "back" from the dua returns to it.
                    setOnClickListener { ReaderActivity.open(this@MainActivity, item.id) }
                }
                val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(ui.text(item.title, 17f, ui.ink, bold = true))
                if (item.subtitle.isNotBlank()) tx.addView(ui.text(item.subtitle, 13f, Colors.MUTED))
                r.addView(tx, ui.weight())
                val recs = Recitations.forPrayer(this, item.id)
                if (recs.isNotEmpty()) {
                    // Reciter choice on the left of the card.
                    r.addView(ui.text("🎙 اختر القارئ\n${CalendarData.ar(recs.size)} ${if (recs.size > 2) "قرّاء" else "قارئ"}", 12.5f, ui.ink, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                        background = ui.rounded(Colors.SOFT, 12, ui.theme.accent, 1)
                        setOnClickListener {
                            Dialogs.sheet(this@MainActivity, "🎙", "اختر القارئ", item.title) { c2, d2 ->
                                recs.forEach { rc ->
                                    c2.addView(ui.outlineButton("▶  ${rc.label}") {
                                        d2.dismiss(); ReaderActivity.open(this@MainActivity, item.id, rc.reciter)
                                    })
                                }
                            }
                        }
                    }, ui.wrap(8, 0, 0, 0))
                }
                col.addView(r, ui.match(0, 4, 0, 4))
            }
        }
    }

    // ================= tab 3: full scanned calendar =================

    private fun pagesTab(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var bar: MonthBar
        val web = PagesView.all(this, month, {}) { visible ->
            if (visible in 1..12) { month = visible; bar.select(visible) }
        }
        pagesWeb = web
        bar = MonthBar(month) { m -> month = m; bar.select(m); PagesView.scrollTo(web, m) }
        pagesBar = bar
        box.addView(bar.view)
        box.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
