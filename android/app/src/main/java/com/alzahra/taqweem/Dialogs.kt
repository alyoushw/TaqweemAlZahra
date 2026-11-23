package com.alzahra.taqweem

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

object Dialogs {

    /** A rounded sheet-style dialog with a title row and close button. */
    fun sheet(activity: Activity, icon: String, title: String, subtitle: String?, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val ui = Ui(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = ui.column(18)
        col.background = ui.rounded(Colors.BG, 24)
        val head = ui.row()
        head.addView(ui.text(icon, 26f, ui.ink), ui.wrap())
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
        ht.addView(ui.text(title, 19f, ui.ink, bold = true))
        if (subtitle != null) ht.addView(ui.text(subtitle, 13f, Colors.MUTED))
        head.addView(ht, ui.weight())
        head.addView(ui.text("✕", 24f, Colors.TEXT).apply { setPadding(ui.dp(10), 0, ui.dp(6), 0); setOnClickListener { dialog.dismiss() } })
        col.addView(head, ui.match(0, 0, 0, 10))
        build(col, dialog)
        dialog.setContentView(ui.scroll(col))
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // On a sideways phone keep sheets at a comfortable reading width.
        val dm = activity.resources.displayMetrics
        dialog.window?.setLayout(minOf((dm.widthPixels * 0.94).toInt(), (600 * dm.density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.showImmersive()
        return dialog
    }

    // ---------- الثيمات ----------
    fun theme(activity: Activity, onChanged: () -> Unit) {
        var chosen = Themes.current(activity).id
        sheet(activity, "🎨", "اختر مظهر ولون التطبيق المفضل", "سيتم تطبيق اللون المختار في كامل أرجاء التطبيق، وتلوين صف اليوم الحالي بهذا اللون") { col, dialog ->
            val ui = Ui(activity)
            val rows = ArrayList<Pair<AppTheme, LinearLayout>>()
            fun paint() = rows.forEach { (t, r) ->
                val sel = t.id == chosen
                r.background = ui.rounded(if (sel) Colors.SELECTED else Colors.CARD, 18, if (sel) t.primary else Colors.STROKE, if (sel) 2 else 1)
                (r.getChildAt(2) as android.widget.TextView).text = if (sel) "◉" else "○"
            }
            Themes.ALL.forEach { t ->
                val r = ui.row().apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
                r.addView(View(activity).apply { background = ui.rounded(t.primary, 30, t.accent, 2) }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
                val tx = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(8), 0) }
                tx.addView(ui.text(t.title + if (t.dark) "  🌙" else "", 16f, Colors.TEXT, bold = true))
                tx.addView(ui.text(t.desc, 12f, Colors.MUTED))
                r.addView(tx, ui.weight())
                r.addView(ui.text("○", 26f, t.primary))
                r.setOnClickListener { chosen = t.id; paint() }
                rows += t to r
                col.addView(r, ui.match(0, 5, 0, 5))
            }
            paint()
            col.addView(ui.primaryButton("تم الاعتماد") {
                Themes.set(activity, chosen)
                dialog.dismiss()
                onChanged()
            })
        }
    }

    // ---------- تنبيه السيستاني ----------
    fun sistani(activity: Activity) {
        sheet(activity, "⚠️", "تنبيه مكتب السيد السيستاني", null) { col, dialog ->
            val ui = Ui(activity)
            col.addView(ui.card(Color.parseColor("#FFF3E0"), Color.parseColor("#FFB74D")).apply {
                addView(ui.text(
                    "نظراً لاختلاف الفلكيين في دخول الفجر الصادق، فمقتضى الاحتياط:\n\n" +
                        "• الإمساك للصيام قبل أذان الصبح بـ (٨) دقائق.\n" +
                        "• تأخير أداء صلاة الصبح عن الأذان بـ (١٣) دقيقة.\n\n" +
                        "(تقويم مكتب السيد السيستاني)",
                    16f, Colors.TEXT, bold = true,
                ).apply { setLineSpacing(0f, 1.3f) })
            })
            col.addView(ui.outlineButton("صفحة التنبيهات والإرشادات كاملة") { dialog.dismiss(); page(activity, 13) })
            col.addView(ui.primaryButton("حسناً") { dialog.dismiss() })
        }
    }

    fun fajrInfo(activity: Activity) {
        sheet(activity, "🌅", "تأخير صلاة الفجر للاحتياط", null) { col, dialog ->
            val ui = Ui(activity)
            col.addView(ui.card(Colors.SOFT, ui.theme.accent).apply {
                addView(ui.text(
                    "نظراً لاختلاف الفلكيين في دخول الفجر الصادق، فمقتضى الاحتياط الإمساك للصيام قبل أذان الصبح بـ (٨) دقائق، وتأخير أداء صلاة الصبح عن الأذان بـ (١٣) دقيقة.\n\n(تقويم مكتب سماحة السيد علي السيستاني دام ظله — كما ورد في تنبيهات تقويم الزهراء)",
                    16f, Colors.TEXT, bold = true,
                ).apply { setLineSpacing(0f, 1.35f) })
            })
            col.addView(ui.text("عند تفعيل الخيار يُضاف ١٣ دقيقة إلى وقت الفجر في الجدول وتفاصيل اليوم والعد التنازلي والأذان والودجت. الإمساك لا يتغير.", 13.5f, Colors.MUTED).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
            col.addView(ui.primaryButton("حسناً") { dialog.dismiss() })
        }
    }

    // ---------- الإشعارات ----------
    fun notifications(activity: Activity) {
        sheet(activity, "🔔", "إشعارات مواقيت الصلاة", "تنبيهات تظهر على شاشة القفل وستارة الإشعارات في جوالك") { col, _ ->
            notificationSettings(activity, col)
        }
    }

    fun notificationSettings(activity: Activity, col: LinearLayout) {
        val ui = Ui(activity)
        val today = CalendarData.byDate(activity, java.time.LocalDate.now())
        // Full-screen adhan (first option).
        val fsCard = ui.card(Colors.SOFT, ui.theme.accent)
        val fsStatus = ui.text("", 13f, Colors.TEXT, bold = true)
        fun paintStatus() {
            val on = PrayerAlert.fullScreenOn(activity)
            val ok = PrayerAlert.canOverlay(activity)
            fsStatus.text = when {
                !on -> ""
                ok -> "✓ إذن «الظهور فوق التطبيقات» مفعّل — سيغطي الأذان الشاشة دائماً"
                else -> "⚠ يلزم تفعيل إذن «الظهور فوق التطبيقات» — اضغط هنا"
            }
            fsStatus.setTextColor(if (ok) Colors.GOOD else Colors.BAD)
            fsStatus.visibility = if (on) View.VISIBLE else View.GONE
        }
        fsStatus.setOnClickListener { PrayerAlert.openOverlaySettings(activity) }
        val fsSwitch = ui.toggle("📱 الواجهة الكاملة للأذان", PrayerAlert.fullScreenOn(activity)) { on ->
            PrayerAlert.setFullScreen(activity, on)
            if (on && !PrayerAlert.canOverlay(activity)) {
                android.app.AlertDialog.Builder(activity)
                    .setTitle("السماح بالظهور فوق التطبيقات")
                    .setMessage("حتى يظهر الأذان كشاشة كاملة مثل المنبّه وأنت تستخدم الجوال، فعّل لتطبيق «تقويم الزهراء» خيار «السماح بالظهور فوق التطبيقات الأخرى» ثم ارجع.")
                    .setPositiveButton("فتح الإعدادات") { _, _ -> PrayerAlert.openOverlaySettings(activity) }
                    .setNegativeButton("لاحقاً", null)
                    .show()
            }
            if (on && !PrayerAlert.canFullScreen(activity)) PrayerAlert.openFullScreenSettings(activity)
            paintStatus()
        }
        fsCard.addView(fsSwitch)
        fsCard.addView(ui.text("عند دخول وقت الصلاة يغطي الأذان شاشة الجوال كاملة مثل المنبّه (حتى وأنت تستخدم الجوال)، ولا يُغلق إلا بزر «إيقاف» أو «تأجيل ١٠ دقائق».", 12.5f, Colors.MUTED))
        fsCard.addView(fsStatus)
        paintStatus()
        col.addView(fsCard)
        col.addView(ui.primaryButton("🔔 تجربة أذان الصلاة الآن") {
            Notifier.showPrayer(activity, Prayer.DHUHR, today?.times?.dhuhr ?: "")
        })
        listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.MAGHRIB, Prayer.IMSAK, Prayer.SUNRISE, Prayer.MIDNIGHT).forEach { p ->
            val time = today?.let { d ->
                when (p) {
                    Prayer.IMSAK -> d.times.imsak; Prayer.FAJR -> d.times.fajr; Prayer.SUNRISE -> d.times.sunrise
                    Prayer.DHUHR -> d.times.dhuhr; Prayer.MAGHRIB -> d.times.maghrib; Prayer.MIDNIGHT -> d.times.midnight
                }
            }
            val label = when (p) {
                Prayer.FAJR, Prayer.DHUHR, Prayer.MAGHRIB -> "أذان ${p.label}"
                else -> "تنبيه ${p.label}"
            } + (time?.let { " ($it)" } ?: "")
            col.addView(ui.toggle(label, Prefs.prayerOn(activity, p)) { on -> Prefs.setPrayerOn(activity, p, on); AlarmScheduler.scheduleNext(activity) })
        }
        col.addView(ui.text("📿 تذكير المناسبات الدينية (المواليد والشهادات)", 16f, ui.ink, bold = true), ui.match(0, 14, 0, 4))
        val modeViews = ArrayList<Pair<String, android.widget.TextView>>()
        fun paintModes() = modeViews.forEach { (id, v) ->
            val sel = Prefs.occasionMode(activity) == id
            v.text = (if (sel) "◉  " else "○  ") + v.tag as String
            v.background = ui.rounded(if (sel) Colors.SELECTED else Colors.CARD, 14, if (sel) ui.fill else Colors.STROKE, if (sel) 2 else 1)
        }
        listOf(
            Prefs.OCC_BOTH to "ليلة المناسبة بعد المغرب + صباح يومها (٧ صباحاً)",
            Prefs.OCC_BEFORE to "ليلة المناسبة فقط (بعد المغرب بعشرين دقيقة)",
            Prefs.OCC_SAME to "صباح يوم المناسبة فقط (٧ صباحاً)",
            Prefs.OCC_OFF to "بدون تذكير",
        ).forEach { (id, title) ->
            val v = ui.text(title, 15f, Colors.TEXT, bold = true).apply {
                tag = title
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                setOnClickListener { Prefs.setOccasionMode(activity, id); paintModes(); AlarmScheduler.scheduleNext(activity); ZahraWidget.updateAll(activity) }
            }
            modeViews += id to v
            col.addView(v, ui.match(0, 3, 0, 3))
        }
        paintModes()
        col.addView(ui.text("مع تذكير الليلة يعرض الودجت مناسبة اليوم ومناسبة الليلة (مناسبة الغد).", 12f, Colors.MUTED).apply { setPadding(0, ui.dp(4), 0, 0) })
        // Next religious occasion and when its reminder will arrive, plus a test button.
        val nextInfo = ui.text("", 14f, Colors.TEXT, bold = true).apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.rounded(Colors.SOFT, 14)
        }
        fun nextOccasion(): Day? {
            val today = java.time.LocalDate.now()
            return CalendarData.days(activity).firstOrNull { it.greg.isAfter(today) && Notifier.isReligious(it) }
        }
        fun paintNext() {
            val d = nextOccasion()
            nextInfo.text = if (d == null) "لا توجد مناسبات دينية قادمة في هذه السنة" else {
                val icon = if (d.isSorrow) "🏴" else "🌸"
                val eveDay = CalendarData.byDate(activity, d.greg.minusDays(1))
                val e = d.greg.minusDays(1)
                val eveText = "ليلة ${CalendarData.WEEKDAYS[d.greg.dayOfWeek.value]} (${e.dayOfMonth}/${e.monthValue}) الساعة " +
                    (eveDay?.let { AlarmScheduler.eveTime(it) }?.let { "${if (it.hour > 12) it.hour - 12 else it.hour}:${"%02d".format(java.util.Locale.US, it.minute)} مساءً" } ?: "٧ مساءً")
                val mornText = "${CalendarData.weekday(d)} ${d.greg.dayOfMonth}/${d.greg.monthValue} الساعة ٧ صباحاً"
                val whenText = when (Prefs.occasionMode(activity)) {
                    Prefs.OCC_BEFORE -> eveText
                    Prefs.OCC_SAME -> mornText
                    Prefs.OCC_BOTH -> "$eveText\nو $mornText"
                    else -> "التذكير متوقف"
                }
                "المناسبة القادمة: $icon ${d.occasions.first().text}\n${CalendarData.weekday(d)} ${CalendarData.hijriLabel(d)}\n⏰ موعد التذكير: $whenText"
            }
        }
        paintNext()
        modeViews.forEach { (id, v) ->
            v.setOnClickListener { Prefs.setOccasionMode(activity, id); paintModes(); paintNext(); AlarmScheduler.scheduleNext(activity); ZahraWidget.updateAll(activity) }
        }
        col.addView(nextInfo, ui.match(0, 8, 0, 4))
        col.addView(ui.outlineButton("🔔 تجربة إشعار المناسبة الآن") {
            val d = nextOccasion()
            if (d == null) Toast.makeText(activity, "لا توجد مناسبة قادمة للتجربة", Toast.LENGTH_SHORT).show()
            else {
                Notifier.showOccasion(activity, d, eve = Prefs.occasionMode(activity) != Prefs.OCC_SAME)
                Toast.makeText(activity, "تم إرسال إشعار تجريبي — انظر شريط الإشعارات", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(ui.text("🔊 صوت الأذان (الصبح والظهر والمغرب)", 16f, ui.ink, bold = true), ui.match(0, 14, 0, 4))
        val rows = ArrayList<Pair<String, android.widget.TextView>>()
        fun paint() = rows.forEach { (id, v) ->
            val sel = AdhanVoice.selectedId(activity) == id
            v.text = (if (sel) "◉  " else "○  ") + v.tag as String
            v.background = ui.rounded(if (sel) Colors.SELECTED else Colors.CARD, 14, if (sel) ui.fill else Colors.STROKE, if (sel) 2 else 1)
        }
        val choices = AdhanVoice.entries.map { it.id to it.title } + (AdhanVoice.SILENT to "إشعار فقط بدون صوت أذان")
        choices.forEach { (id, title) ->
            val r = ui.row()
            val v = ui.text(title, 15f, Colors.TEXT, bold = true).apply {
                tag = title
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                setOnClickListener { AdhanVoice.select(activity, id); paint() }
            }
            rows += id to v
            r.addView(v, ui.weight())
            if (id != AdhanVoice.SILENT) {
                r.addView(ui.text("▶", 18f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(ui.theme.primary, 14)
                    setOnClickListener { AdhanService.start(activity, "🔊 تجربة صوت الأذان", title, id) }
                }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { setMargins(ui.dp(6), 0, 0, 0) })
            }
            col.addView(r, ui.match(0, 3, 0, 3))
        }
        paint()
        col.addView(ui.outlineButton("⏹ إيقاف التجربة") { AdhanService.stop(activity) })
        col.addView(ui.outlineButton("فتح إعدادات الإشعارات في الجوال") {
            activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
        })
    }

    // ---------- ضبط الودجت ----------
    fun widgetSettings(activity: Activity) {
        sheet(activity, "▦", "ضبط ودجات الشاشة الرئيسية", "تخصيص الألوان والمحتوى والعد التنازلي والمقاسات") { col, dialog ->
            val ui = Ui(activity)
            var size = WidgetSize.STANDARD
            col.addView(ui.text("١. اختر مقاس وشكل الودجت:", 16f, Colors.TEXT, bold = true))
            val sizes = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val sizeViews = ArrayList<Pair<WidgetSize, android.widget.TextView>>()
            val installBtn = ui.primaryButton("") {}
            fun paintSizes() {
                sizeViews.forEach { (s, v) ->
                    val sel = s == size
                    v.setTextColor(if (sel) ui.onFill else Colors.TEXT)
                    v.background = if (sel) ui.rounded(ui.fill, 14) else ui.rounded(Colors.CARD, 14, Colors.STROKE, 1)
                }
                installBtn.text = "➕ تثبيت ودجت (${size.title} ${size.cells}) على شاشة الهاتف"
            }
            WidgetSize.entries.chunked(2).forEach { pair ->
                val r = ui.row()
                pair.forEach { s ->
                    val v = ui.text("${s.title}\n${s.cells}", 15f, Colors.TEXT, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                        setOnClickListener { size = s; paintSizes() }
                    }
                    sizeViews += s to v
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                sizes.addView(r)
            }
            col.addView(sizes)

            // What the one-row bar widget shows.
            col.addView(ui.text("محتوى الودجت الشريطي (4 × 1):", 15f, Colors.TEXT, bold = true), ui.match(0, 12, 0, 4))
            val modeViews = ArrayList<Pair<String, android.widget.TextView>>()
            fun paintModes() = modeViews.forEach { (id, v) ->
                val sel = WidgetPrefs.barMode(activity) == id
                v.text = (if (sel) "◉  " else "○  ") + WidgetPrefs.BAR_MODES.first { it.first == id }.second
                v.background = ui.rounded(if (sel) Colors.SELECTED else Colors.CARD, 12, if (sel) ui.fill else Colors.STROKE, if (sel) 2 else 1)
            }
            WidgetPrefs.BAR_MODES.forEach { (id, _) ->
                val v = ui.text("", 14.5f, Colors.TEXT, bold = true).apply {
                    setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                    setOnClickListener { WidgetPrefs.setBarMode(activity, id); paintModes(); ZahraWidget.updateAll(activity) }
                }
                modeViews += id to v
                col.addView(v, ui.match(0, 3, 0, 3))
            }
            paintModes()

            col.addView(ui.text("٢. ألوان خلفية الودجت:", 16f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val bgViews = ArrayList<Pair<String, android.widget.TextView>>()
            fun paintBg() = bgViews.forEach { (id, v) ->
                val c = WidgetPrefs.BACKGROUNDS.first { it.first == id }.third
                val solid = when (id) {
                    "glass" -> Color.parseColor("#C9C6C1")
                    "frost" -> Color.parseColor("#9AA7B4") // light frosted swatch
                    "none" -> Color.parseColor("#6B7280")
                    else -> c or (0xFF shl 24)
                }
                v.background = ui.rounded(solid, 12, if (WidgetPrefs.bg(activity) == id) ui.theme.accent else Color.TRANSPARENT, 3)
            }
            WidgetPrefs.BACKGROUNDS.chunked(3).forEach { chunk ->
                val r = ui.row()
                chunk.forEach { (id, name, _) ->
                    val v = ui.text(name, 14f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
                        setOnClickListener { WidgetPrefs.setBg(activity, id); paintBg(); ZahraWidget.updateAll(activity) }
                    }
                    bgViews += id to v
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                repeat(3 - chunk.size) { r.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
                col.addView(r)
            }
            paintBg()

            col.addView(ui.text("٣. لون الخط:", 16f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val fontViews = ArrayList<Pair<String, android.widget.TextView>>()
            fun paintFont() = fontViews.forEach { (id, v) ->
                val sel = WidgetPrefs.font(activity) == id
                val swatch = if (id == "black") Color.parseColor("#E0E0E0") else Color.parseColor("#3A3F45")
                v.background = ui.rounded(swatch, 12, if (sel) ui.theme.accent else Color.TRANSPARENT, 3)
            }
            WidgetPrefs.FONT_COLORS.chunked(3).forEach { chunk ->
                val r = ui.row()
                chunk.forEach { (id, name, c) ->
                    val v = ui.text("Aa  $name", 14f, c, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
                        setOnClickListener { WidgetPrefs.setFont(activity, id); paintFont(); ZahraWidget.updateAll(activity) }
                    }
                    fontViews += id to v
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                col.addView(r)
            }
            paintFont()

            col.addView(ui.text("٤. خيارات المحتوى (ماذا سوف يظهر داخل الودجت):", 16f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val opts = ui.card(Colors.SOFT)
            WidgetPrefs.OPTIONS.forEach { (key, label) ->
                opts.addView(ui.toggle(label, WidgetPrefs.on(activity, key)) { WidgetPrefs.set(activity, key, it) })
            }
            col.addView(opts)

            installBtn.setOnClickListener {
                ZahraWidget.updateAll(activity)
                val mgr = activity.getSystemService(AppWidgetManager::class.java)
                if (Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported) {
                    mgr.requestPinAppWidget(ComponentName(activity, size.provider), null, null)
                } else {
                    Toast.makeText(activity, "اضغط مطولاً على الشاشة الرئيسية ← الأدوات (Widgets) ← تقويم الزهراء", Toast.LENGTH_LONG).show()
                }
            }
            col.addView(installBtn)
            col.addView(ui.outlineButton("حفظ التغييرات وتحديث الودجات الحالية") {
                ZahraWidget.updateAll(activity)
                Toast.makeText(activity, "تم حفظ الإعدادات وتحديث الودجات", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            })
            paintSizes()
        }
    }

    // ---------- تفاصيل اليوم ----------
    /** The Zahra day number in a circle, coloured as in the calendar (black = sorrow, yellow = joy). */
    fun dayCircle(activity: Activity, d: Day, sizeDp: Int, onDark: Boolean = false): android.widget.TextView {
        val ui = Ui(activity)
        val (fill, fg) = when {
            d.isSorrow -> Color.parseColor("#111111") to Color.WHITE
            d.isJoy -> Color.parseColor("#FFD600") to Color.BLACK
            onDark -> Color.TRANSPARENT to Color.WHITE
            else -> Colors.SOFT to ui.ink
        }
        return ui.text(d.zahra.toString(), sizeDp * 0.42f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(fill)
                setStroke(ui.dp(2), ui.theme.accent)
            }
            layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        }
    }

    private fun shareText(d: Day): String = buildString {
        append("📅 ${CalendarData.weekday(d)} ${d.zahra} ${CalendarData.MONTHS[d.month - 1]} ${CalendarData.YEAR}هـ\n")
        append("${CalendarData.gregLabel(d.greg)}م\n")
        if (d.occasions.isNotEmpty()) { append("\n"); d.occasions.forEach { append("• ${it.text}\n") } }
        append("\n🕌 مواقيت الأحساء:\n")
        append("الإمساك ${d.times.imsak} | الصبح ${d.times.fajr} | الشروق ${d.times.sunrise}\n")
        append("الظهر ${d.times.dhuhr} | المغرب ${d.times.maghrib} | منتصف الليل ${d.times.midnight}\n")
        val extra = listOf("عقد النكاح" to d.mark, "الحجامة" to d.hijama).filter { it.second.isNotBlank() }
        if (extra.isNotEmpty()) append("\n" + extra.joinToString(" | ") { "${it.first}: ${it.second}" } + "\n")
        append("\n— تقويم الزهراء - الأحساء\n${Updater.APK_URL}")
    }

    fun day(activity: Activity, d: Day) {
        sheet(activity, "📅", "تفاصيل اليوم", null) { col, _ ->
            val ui = Ui(activity)
            val dark = d.isSorrow

            // Header card
            val head = ui.card(if (dark) Color.parseColor("#1E1E1E") else if (d.isJoy) Colors.JOY_SOFT else Colors.CARD, ui.theme.accent)
            val r = ui.row()
            r.addView(dayCircle(activity, d, 60, onDark = dark))
            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, 0, 0) }
            info.addView(ui.text("${CalendarData.weekday(d)} • ${CalendarData.gregLabel(d.greg)}م", 13.5f, if (dark) Color.parseColor("#BFBFBF") else Colors.MUTED))
            info.addView(ui.text("${CalendarData.ar(d.zahra)} ${CalendarData.MONTHS[d.month - 1]} ${CalendarData.ar(CalendarData.YEAR)}هـ", 20f, if (dark) Color.WHITE else ui.ink, bold = true))
            info.addView(ui.text("أم القرى: ${CalendarData.ar(d.ummAlQura)}", 13f, if (dark) Color.parseColor("#BFBFBF") else Colors.MUTED))
            r.addView(info, ui.weight())
            head.addView(r)
            val tags = buildList {
                if (d.isSorrow) add("🏴 شهادة / وفاة" to Color.parseColor("#444444"))
                if (d.isJoy) add("🌸 مولد / فرح" to Color.parseColor("#B8860B"))
                if (d.gregColor == "G") add("إجازة رسمية" to Color.parseColor("#558B2F"))
                if (d.gregColor == "O") add("أول يوم دوام" to Colors.FIRST_WORK)
                if (d.ummAlQuraColor == "D") add("أفضل أيام الصوم" to Colors.BEST_FAST)
            }
            if (tags.isNotEmpty()) {
                val tr = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
                tags.forEach { (l, c) -> tr.addView(ui.text(l, 12.5f, Color.WHITE, bold = true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); background = ui.rounded(c, 12) }, ui.wrap(0, 0, 6, 0)) }
                head.addView(tr)
            }
            if (d.occasions.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                d.occasions.forEachIndexed { i, o ->
                    if (i > 0) sb.append("\n")
                    val s = sb.length
                    sb.append(o.text)
                    sb.setSpan(ForegroundColorSpan(if (dark) Color.WHITE else Colors.occasion(o.color)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                head.addView(ui.text(sb, 16.5f, Colors.TEXT, bold = true).apply { setPadding(0, ui.dp(12), 0, 0); setLineSpacing(0f, 1.3f) })
            }
            col.addView(head)
            val pictured = mutableListOf<View>(head) // what goes into the shared picture

            // Times
            val t = d.times
            val items = mutableListOf("الإمساك" to t.imsak, "الصبح" to t.fajr, "الشروق" to t.sunrise, "الظهر" to t.dhuhr, "المغرب" to t.maghrib, "منتصف الليل" to t.midnight)
            if (t.sahur.isNotBlank()) items.add(0, "السحور" to t.sahur)
            if (t.qibla.isNotBlank()) items += "القبلة" to t.qibla
            val times = ui.card()
            items.chunked(3).forEach { chunk ->
                val row = ui.row()
                chunk.forEach { (l, v) ->
                    val c = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(6)) }
                    c.addView(ui.text(l, 12f, Colors.MUTED).apply { gravity = Gravity.CENTER })
                    c.addView(ui.text(v, 18f, ui.ink, bold = true).apply { gravity = Gravity.CENTER })
                    row.addView(c, ui.weight())
                }
                repeat(3 - chunk.size) { row.addView(View(activity), ui.weight()) }
                times.addView(row)
            }
            col.addView(times)
            pictured += times

            // Other columns as small pills
            val info2 = listOf("عقد النكاح" to d.mark, "السكن" to d.sakan, "الحجامة" to d.hijama, "الطالع" to d.tale).filter { it.second.isNotBlank() }
            info2.chunked(2).forEach { chunk ->
                val row = ui.row()
                chunk.forEach { (k, v) ->
                    val bad = v == "غير صالح" || v.startsWith("ردي") || (k == "عقد النكاح" && v != "جيد")
                    row.addView(ui.text("$k: $v", 14f, if (bad) Colors.BAD else Colors.GOOD, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
                        background = ui.rounded(Colors.CARD, 12, Colors.STROKE, 1)
                    }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
                }
                col.addView(row)
                pictured += row
            }

            // Share
            val text = shareText(d)
            col.addView(ui.primaryButton("🖼  مشاركة كصورة") {
                ShareImage.share(activity, pictured, Colors.BG,
                    "تقويم الزهراء - الأحساء ${CalendarData.ar(CalendarData.YEAR)}هـ", "مشاركة اليوم كصورة")
            }, ui.match(3, 10, 3, 0))
            val sr = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
            sr.addView(ui.outlineButton("مشاركة كنص") {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "مشاركة اليوم"))
            }, ui.weight().apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            sr.addView(ui.primaryButton("واتساب") {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); setPackage("com.whatsapp") }
                runCatching { activity.startActivity(i) }.onFailure {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text))))
                }
            }, ui.weight().apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            sr.addView(ui.outlineButton("نسخ") {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("taqweem", text))
                Toast.makeText(activity, "تم نسخ تفاصيل اليوم", Toast.LENGTH_SHORT).show()
            }, ui.weight().apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            col.addView(sr)
        }
    }

    // ---------- صفحة PDF مفردة ----------
    fun page(activity: Activity, index: Int) {
        val ui = Ui(activity)
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(activity)
        box.addView(PagesView.zoomableWeb(activity, index))
        box.addView(ui.text("✕", 26f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Color.parseColor("#99000000"), 24)
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(12), ui.dp(12), 0, 0) })
        dialog.setContentView(box)
        dialog.showImmersive()
    }

    // ---------- التواصل ----------
    const val CONTACT_NAME = "حسين اليوسف"
    const val CONTACT_PHONE = "0555328865"
    const val CONTACT_WHATSAPP = "966555328865"
    const val CONTACT_EMAIL = "alyoushw@gmail.com"

    fun contact(activity: Activity) {
        sheet(activity, "📞", "معلومات التواصل والدعم", null) { col, _ ->
            val ui = Ui(activity)
            fun copy(s: String) {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("taqweem", s))
                Toast.makeText(activity, "تم النسخ: $s", Toast.LENGTH_SHORT).show()
            }
            col.addView(ui.card().apply {
                addView(ui.text("المسؤول عن تقويم الزهراء", 13f, Colors.MUTED))
                addView(ui.text(CONTACT_NAME, 20f, ui.ink, bold = true))
            })
            col.addView(ui.card().apply {
                addView(ui.text("رقم الجوال", 13f, Colors.MUTED))
                addView(ui.text(CONTACT_PHONE, 18f, Colors.TEXT, bold = true))
                val r = ui.row()
                r.addView(ui.primaryButton("اتصال") { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$CONTACT_PHONE"))) }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(6), ui.dp(3), 0) })
                r.addView(ui.primaryButton("واتساب") { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$CONTACT_WHATSAPP"))) }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(6), ui.dp(3), 0) })
                r.addView(ui.outlineButton("نسخ") { copy(CONTACT_PHONE) }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(6), ui.dp(3), 0) })
                addView(r)
            })
            col.addView(ui.card().apply {
                addView(ui.text("البريد الإلكتروني", 13f, Colors.MUTED))
                addView(ui.text(CONTACT_EMAIL, 17f, Colors.TEXT, bold = true))
                val r = ui.row()
                r.addView(ui.primaryButton("إرسال") {
                    activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "استفسار بخصوص تقويم الزهراء - الأحساء"))
                }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(6), ui.dp(3), 0) })
                r.addView(ui.outlineButton("نسخ") { copy(CONTACT_EMAIL) }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(6), ui.dp(3), 0) })
                addView(r)
            })
            col.addView(ui.text("نسعد بتواصلكم وملاحظاتكم واقتراحاتكم لتطوير تقويم الزهراء المبارك.", 13f, Colors.MUTED).apply { gravity = Gravity.CENTER })
        }
    }
}
