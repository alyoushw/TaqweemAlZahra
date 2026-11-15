package com.alzahra.taqweem

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var player: MediaPlayer? = null
    private var shownMonth = 1
    private var currentTab = 0

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        shownMonth = CalendarData.currentMonth(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Colors.BG)
        }
        root.addView(header())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)

        selectTab(0)
        askNotificationPermission()
        AlarmScheduler.scheduleNext(this)
        ZahraWidget.updateAll(this)
        Updater.check(this, manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == 0) selectTab(0)
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    // ---------- shell ----------

    private fun header(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Colors.PRIMARY)
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        box.addView(text("تقويم الزهراء - الأحساء ${CalendarData.ar(CalendarData.YEAR)}هـ", 19f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
        })
        box.addView(text("للتواصل والدعم", 13f, Colors.GOLD_LIGHT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(2))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { showContact() }
        })
        return box
    }

    private fun bottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Colors.PRIMARY_DARK)
        }
        listOf("اليوم", "التقويم", "الأدعية", "الصفحات", "المزيد").forEachIndexed { i, label ->
            val t = text(label, 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { selectTab(i) }
            }
            tabs += t
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun selectTab(i: Int) {
        currentTab = i
        stopTicker()
        tabs.forEachIndexed { j, t ->
            t.setTextColor(if (j == i) Colors.GOLD_LIGHT else Color.parseColor("#CCFFFFFF"))
            t.setBackgroundColor(if (j == i) Colors.PRIMARY else Color.TRANSPARENT)
        }
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> todayScreen()
                1 -> calendarScreen()
                2 -> duasScreen()
                3 -> pagesScreen()
                else -> moreScreen()
            },
        )
    }

    // ---------- اليوم ----------

    private fun todayScreen(): View {
        val col = column()
        val day = CalendarData.byDate(this, LocalDate.now())
        if (day == null) {
            col.addView(card().apply {
                addView(text("التاريخ الحالي خارج سنة ١٤٤٨هـ في هذا التقويم.\nيبدأ التقويم في ١٧/٦/٢٠٢٦ وينتهي في ٥/٦/٢٠٢٧.", 16f, Colors.TEXT))
            })
            return scroll(col)
        }

        // Date card
        col.addView(card().apply {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(dayBadge(day, 30f, 64))
            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            info.addView(text(CalendarData.weekday(day), 15f, Colors.MUTED))
            info.addView(text(CalendarData.hijriLabel(day), 21f, Colors.PRIMARY, bold = true))
            info.addView(text("${CalendarData.gregLabel(day.greg)}م  •  أم القرى: ${CalendarData.ar(day.ummAlQura)}", 14f, Colors.MUTED))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)
            dayTypeLabels(day).forEach { (label, color) -> addView(chip(label, color)) }
        })

        // Next prayer countdown
        val nextName = text("", 16f, Color.WHITE, bold = true)
        val nextTimer = text("--:--:--", 30f, Colors.GOLD_LIGHT, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(card(Colors.PRIMARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(nextName.apply { gravity = Gravity.CENTER })
            addView(nextTimer)
        })
        startTicker {
            val next = CalendarData.nextPrayer(this)
            if (next == null) {
                nextName.text = "—"
                nextTimer.text = "--:--:--"
            } else {
                val t = next.at.toLocalTime()
                nextName.text = "الوقت القادم: ${next.prayer.label} (${h12(t.hour)}:${"%02d".format(t.minute)})"
                val s = Duration.between(LocalDateTime.now(), next.at).seconds.coerceAtLeast(0)
                nextTimer.text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
            }
        }

        // Occasions
        if (day.occasions.isNotEmpty()) {
            col.addView(card().apply {
                addView(sectionTitle("المناسبات"))
                addView(occasionsText(day, 16f))
            })
        }

        // Times
        col.addView(card().apply {
            addView(sectionTitle("مواقيت الصلاة - الأحساء"))
            addView(timesGrid(day))
        })

        // Other columns
        col.addView(card().apply {
            addView(sectionTitle("معلومات اليوم"))
            addView(infoRows(day))
        })
        return scroll(col)
    }

    private fun dayTypeLabels(day: Day): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        if (day.isSorrow) out += "يوم حزن" to Colors.SORROW
        if (day.isJoy) out += "يوم فرح" to Color.parseColor("#B8860B")
        if (day.gregColor == "G") out += "إجازة رسمية" to Color.parseColor("#558B2F")
        if (day.gregColor == "O") out += "أول يوم في الدوام الرسمي" to Colors.FIRST_WORK
        if (day.ummAlQuraColor == "D") out += "من أفضل أيام السنة للصوم" to Colors.BEST_FAST
        return out
    }

    private fun timesGrid(day: Day): View {
        val t = day.times
        val items = mutableListOf(
            "الإمساك" to t.imsak, "الصبح" to t.fajr, "الشروق" to t.sunrise,
            "الظهر" to t.dhuhr, "المغرب" to t.maghrib, "منتصف الليل" to t.midnight,
        )
        if (t.qibla.isNotBlank()) items += "الشمس باتجاه القبلة" to t.qibla
        if (t.sahur.isNotBlank()) items += "السحور" to t.sahur
        val grid = column(pad = 0)
        items.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (label, value) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(Color.parseColor("#F5EFE3"), 10)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }
                cell.addView(text(label, 12f, Colors.MUTED).apply { gravity = Gravity.CENTER })
                cell.addView(text(value, 18f, Colors.PRIMARY, bold = true).apply { gravity = Gravity.CENTER })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            repeat(3 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        return grid
    }

    private fun infoRows(day: Day): View {
        val c = column(pad = 0)
        fun row(k: String, v: String, color: Int = Colors.TEXT) {
            if (v.isBlank()) return
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(text(k, 15f, Colors.MUTED), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(text(v, 15f, color, bold = true))
            c.addView(r)
        }
        row("عقد النكاح", markLabel(day.mark), markColor(day.mark))
        row("السكن", day.sakan, if (day.sakan.startsWith("ردي")) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
        row("الحجامة", day.hijama, if (day.hijama == "غير صالح") Color.parseColor("#C62828") else Colors.TEXT)
        row("الطالع", day.tale)
        return c
    }

    private fun markLabel(m: String): String {
        if (m.isBlank()) return ""
        if (m == "جيد" || m.startsWith("مكروه")) return m
        val parts = m.split("/").map { it.trim() }
        return m + "  (" + parts.joinToString("، ") { MARKS[it] ?: it } + ")"
    }

    private fun markColor(m: String) = when {
        m == "جيد" -> Color.parseColor("#1565C0")
        m.isBlank() -> Colors.TEXT
        else -> Color.parseColor("#C62828")
    }

    // ---------- التقويم ----------

    private fun calendarScreen(): View {
        val col = column()
        val days = CalendarData.month(this, shownMonth)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Colors.PRIMARY, 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        // RTL: the right-pointing arrow sits on the right and goes back a month.
        val prev = text("▶", 22f, if (shownMonth > 1) Color.WHITE else Color.GRAY, bold = true).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { if (shownMonth > 1) { shownMonth--; selectTab(1) } }
        }
        val next = text("◀", 22f, if (shownMonth < 12) Color.WHITE else Color.GRAY, bold = true).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { if (shownMonth < 12) { shownMonth++; selectTab(1) } }
        }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        title.addView(text("${CalendarData.MONTHS[shownMonth - 1]} ${CalendarData.ar(CalendarData.YEAR)}هـ", 20f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        if (days.isNotEmpty()) {
            title.addView(text("${CalendarData.gregLabel(days.first().greg)} - ${CalendarData.gregLabel(days.last().greg)}", 12f, Colors.GOLD_LIGHT).apply { gravity = Gravity.CENTER })
        }
        nav.addView(prev)
        nav.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next)
        col.addView(nav)

        col.addView(legend())

        val today = LocalDate.now()
        days.forEach { col.addView(dayRow(it, it.greg == today)) }
        val sv = scroll(col)
        // Scroll to today's row when it is in this month.
        val idx = days.indexOfFirst { it.greg == today }
        if (idx >= 0) sv.post { col.getChildAt(2 + idx)?.let { sv.scrollTo(0, (it.top - dp(80)).coerceAtLeast(0)) } }
        return sv
    }

    private fun legend(): View {
        val box = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val flow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun line(vararg items: Pair<Int, String>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            items.forEach { (color, label) ->
                r.addView(View(this).apply { background = rounded(color, 3) }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(4), 0, dp(4), 0) })
                r.addView(text(label, 12f, Colors.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            flow.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        line(Colors.SORROW to "يوم حزن", Colors.JOY to "يوم فرح")
        line(Colors.HOLIDAY to "إجازة رسمية", Colors.FIRST_WORK to "أول يوم دوام")
        line(Colors.BEST_FAST to "أفضل أيام السنة للصوم", Color.parseColor("#C62828") to "ثواب زيارة الحسين (ع)")
        line(Color.parseColor("#1565C0") to "معلومات عامة وفلكية", Color.parseColor("#2E7D32") to "مناسبات «في هذا الشهر»")
        box.addView(flow)
        return box
    }

    private fun dayRow(day: Day, isToday: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Colors.CARD, 12, if (isToday) Colors.GOLD else Color.parseColor("#E6DCCB"), if (isToday) 3 else 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showDay(day) }
        }
        // Date column
        val dates = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        dates.addView(dayBadge(day, 20f, 44))
        dates.addView(text(CalendarData.weekday(day), 11f, if (day.greg.dayOfWeek.value == 5) Color.parseColor("#C62828") else Colors.MUTED).apply { gravity = Gravity.CENTER })
        row.addView(dates, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (gBg, gFg) = Colors.gregCell(day.gregColor)
        chips.addView(miniCell("${day.greg.dayOfMonth}/${day.greg.monthValue}", gBg, gFg))
        val (uBg, uFg) = Colors.ummAlQuraCell(day.ummAlQuraColor)
        chips.addView(miniCell("أم القرى ${CalendarData.ar(day.ummAlQura)}", uBg, uFg))
        if (day.mark.isNotBlank()) chips.addView(miniCell(day.mark, Color.TRANSPARENT, markColor(day.mark)))
        body.addView(chips)
        if (day.occasions.isNotEmpty()) body.addView(occasionsText(day, 14f).apply { setPadding(0, dp(4), 0, 0) })
        body.addView(text("الصبح ${day.times.fajr}  •  الظهر ${day.times.dhuhr}  •  المغرب ${day.times.maghrib}", 12f, Colors.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val wrap = FrameLayout(this)
        wrap.addView(row)
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
        return wrap
    }

    private fun showDay(day: Day) {
        val col = column()
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dayBadge(day, 24f, 52))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(text("${CalendarData.weekday(day)} ${CalendarData.hijriLabel(day)}", 17f, Colors.PRIMARY, bold = true))
                addView(text("${CalendarData.gregLabel(day.greg)}م  •  أم القرى: ${CalendarData.ar(day.ummAlQura)}", 13f, Colors.MUTED))
            })
        })
        dayTypeLabels(day).forEach { (l, c) -> col.addView(chip(l, c)) }
        if (day.occasions.isNotEmpty()) {
            col.addView(sectionTitle("المناسبات"))
            col.addView(occasionsText(day, 15f))
        }
        col.addView(sectionTitle("المواقيت"))
        col.addView(timesGrid(day))
        col.addView(sectionTitle("معلومات اليوم"))
        col.addView(infoRows(day))
        AlertDialog.Builder(this)
            .setView(scroll(col))
            .setPositiveButton("إغلاق", null)
            .show()
    }

    // ---------- الأدعية ----------

    private fun duasScreen(): View {
        val col = column()
        col.addView(sectionTitle("الأدعية والزيارات"))
        Duas.ALL.forEach { dua ->
            col.addView(button("🤲  ${dua.title}") { showDua(dua) })
        }
        return scroll(col)
    }

    private fun showDua(dua: Dua) {
        stopAudio()
        val col = column()
        col.addView(button("◀ رجوع إلى الأدعية") { stopAudio(); selectTab(2) })
        col.addView(text(dua.title, 21f, Colors.PRIMARY, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })

        val hasAudio = runCatching { assets.list("audio")?.contains(dua.audio) == true }.getOrDefault(false)
        val play = button(if (hasAudio) "▶  تشغيل الصوت" else "الصوت سيضاف في تحديث قادم") {}
        if (hasAudio) {
            play.setOnClickListener {
                val p = player
                if (p != null && p.isPlaying) {
                    p.pause(); play.text = "▶  تشغيل الصوت"
                } else if (p != null) {
                    p.start(); play.text = "⏸  إيقاف مؤقت"
                } else {
                    runCatching {
                        val afd = assets.openFd("audio/${dua.audio}")
                        player = MediaPlayer().apply {
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                            setOnCompletionListener { play.text = "▶  تشغيل الصوت" }
                            prepare()
                            start()
                        }
                        play.text = "⏸  إيقاف مؤقت"
                    }.onFailure { Toast.makeText(this, "تعذر تشغيل الصوت", Toast.LENGTH_LONG).show() }
                }
            }
        }
        col.addView(play)
        col.addView(card().apply {
            addView(text(dua.text, 21f, Colors.TEXT).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.6f)
            })
        })
        content.removeAllViews()
        content.addView(scroll(col))
    }

    private fun stopAudio() {
        player?.release()
        player = null
    }

    // ---------- الصفحات ----------

    private fun pagesScreen(): View {
        val col = column()
        col.addView(sectionTitle("صفحات التقويم الأصلية"))
        for (i in 1..13) {
            val label = if (i <= 12) "${CalendarData.ar(i)}. شهر ${CalendarData.MONTHS[i - 1]}" else "١٣. صفحة التنبيهات والإرشادات"
            col.addView(button(label) { showPage(i) })
        }
        return scroll(col)
    }

    private fun showPage(i: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(button("◀ رجوع إلى الصفحات") { selectTab(3) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(8), dp(8), dp(8), dp(4))
        })
        val web = WebView(this).apply {
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = true
            setBackgroundColor(Colors.BG)
            loadDataWithBaseURL(
                "file:///android_asset/",
                "<html><body style='margin:0;background:#FAF6EE'><img src='pages/page_%02d.jpg' style='width:100%%'/></body></html>".format(i),
                "text/html", "utf-8", null,
            )
        }
        box.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(text("قرّب بإصبعين لتكبير الصفحة", 12f, Colors.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) })
        content.removeAllViews()
        content.addView(box)
    }

    // ---------- المزيد ----------

    private fun moreScreen(): View {
        val col = column()
        col.addView(card().apply {
            addView(sectionTitle("التنبيهات"))
            listOf(Prayer.IMSAK, Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.MAGHRIB, Prayer.MIDNIGHT).forEach { p ->
                addView(toggle("تنبيه ${p.label}", Prefs.prayerOn(context, p)) { on ->
                    Prefs.setPrayerOn(context, p, on); AlarmScheduler.scheduleNext(context)
                })
            }
            addView(toggle("إشعار مناسبة اليوم (٧ صباحاً)", Prefs.occasionOn(context)) { on ->
                Prefs.setOccasionOn(context, on); AlarmScheduler.scheduleNext(context)
            })
            addView(text("إذا لم تصلك التنبيهات: من إعدادات الجوال اسمح للتطبيق بالإشعارات واستثنه من توفير البطارية.", 12f, Colors.MUTED).apply { setPadding(0, dp(6), 0, 0) })
            addView(button("فتح إعدادات الإشعارات") {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            })
        })

        col.addView(card().apply {
            addView(sectionTitle("رموز عمود «عقد النكاح»"))
            MARKS.forEach { (k, v) -> addView(text("$k  :  $v", 14f, Colors.TEXT).apply { setPadding(0, dp(2), 0, dp(2)) }) }
            addView(text("جيد: لا مانع من العقد  •  مكروه نهاراً: يوم الأربعاء", 14f, Colors.TEXT))
            addView(text("تنبيه التقويم: مواقيت الصبح احتياطاً تقتضي الإمساك قبل الأذان بـ ٨ دقائق، وتأخير صلاة الصبح عن الأذان بـ ١٣ دقيقة (تقويم مكتب السيد السيستاني).", 12f, Colors.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        })

        col.addView(card().apply {
            addView(sectionTitle("التحديثات"))
            addView(text("الإصدار الحالي: ${BuildConfig.VERSION_NAME}", 14f, Colors.TEXT))
            addView(button("التحقق من وجود تحديث") { Updater.check(this@MainActivity, manual = true) })
            addView(button("مشاركة رابط تحميل التطبيق") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "تطبيق تقويم الزهراء - الأحساء ١٤٤٨هـ\n${Updater.APK_URL}")
                }, "مشاركة التطبيق"))
            })
        })

        col.addView(card().apply {
            addView(sectionTitle("للتواصل والدعم"))
            addView(button("معلومات التواصل") { showContact() })
        })
        return scroll(col)
    }

    private fun showContact() {
        val col = column()
        col.addView(text("المسؤول عن تقويم الزهراء", 13f, Colors.MUTED))
        col.addView(text(CONTACT_NAME, 20f, Colors.PRIMARY, bold = true))
        col.addView(text(CONTACT_PHONE, 17f, Colors.TEXT, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(button("اتصال") { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$CONTACT_PHONE"))) })
        col.addView(button("واتساب") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$CONTACT_WHATSAPP"))) })
        col.addView(button("نسخ الرقم") { copy(CONTACT_PHONE) })
        col.addView(text(CONTACT_EMAIL, 17f, Colors.TEXT, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(button("إرسال بريد") {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "استفسار بخصوص تقويم الزهراء - الأحساء"))
        })
        col.addView(button("نسخ البريد") { copy(CONTACT_EMAIL) })
        AlertDialog.Builder(this).setTitle("معلومات التواصل والدعم").setView(scroll(col)).setPositiveButton("إغلاق", null).show()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("taqweem", s))
        Toast.makeText(this, "تم النسخ: $s", Toast.LENGTH_SHORT).show()
    }

    // ---------- shared pieces ----------

    private fun occasionsText(day: Day, size: Float): TextView {
        val sb = SpannableStringBuilder()
        day.occasions.forEachIndexed { i, o ->
            if (i > 0) sb.append("\n")
            val start = sb.length
            sb.append("• ").append(o.text)
            sb.setSpan(ForegroundColorSpan(Colors.occasion(o.color)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text("", size, Colors.TEXT).apply {
            text = sb
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.25f)
        }
    }

    private fun dayBadge(day: Day, size: Float, boxDp: Int): TextView {
        val (bg, fg) = Colors.zahraCell(day.zahraColor)
        return text(CalendarData.ar(day.zahra), size, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (bg == Color.TRANSPARENT) Color.parseColor("#F5EFE3") else bg, 10, Color.parseColor("#D9CBB3"), 1)
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
        }
    }

    private fun miniCell(s: String, bg: Int, fg: Int) = text(s, 12f, fg, bold = true).apply {
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = rounded(bg, 6, Color.parseColor("#D9CBB3"), 1)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) }
    }

    private fun chip(s: String, color: Int) = text(s, 13f, Color.WHITE, bold = true).apply {
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = rounded(color, 12)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Colors.TEXT)
        isChecked = initial
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun button(label: String, onClick: () -> Unit) = text(label, 15f, Colors.PRIMARY, bold = true).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Colors.CARD, 12, Colors.GOLD, 1)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(5), 0, dp(5)) }
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(s: String) = text(s, 16f, Colors.PRIMARY, bold = true).apply { setPadding(0, dp(6), 0, dp(6)) }

    private fun card(bg: Int = Colors.CARD) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(bg, 16, Color.parseColor("#E6DCCB"), 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun column(pad: Int = 12) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun scroll(v: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(v)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun h12(h: Int) = if (h % 12 == 0) 12 else h % 12

    private fun startTicker(tick: () -> Unit) {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                tick()
                handler.postDelayed(this, 1000)
            }
        }
        ticker = r
        r.run()
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    companion object {
        const val CONTACT_NAME = "حسين اليوسف"
        const val CONTACT_PHONE = "0555328865"
        const val CONTACT_WHATSAPP = "966555328865"
        const val CONTACT_EMAIL = "alyoushw@gmail.com"

        /** Codes used in the "عقد النكاح" column of the printed calendar. */
        val MARKS = linkedMapOf(
            "ك" to "الكوامل - لا ينبغي العقد",
            "ع" to "القمر في العقرب",
            "ع*" to "القمر في العقرب جزءاً من اليوم (انظر الملاحظة الزرقاء)",
            "ق" to "المحاق",
            "و" to "وفاة معصوم",
            "ن" to "ليلة نحسة",
        )
    }
}
