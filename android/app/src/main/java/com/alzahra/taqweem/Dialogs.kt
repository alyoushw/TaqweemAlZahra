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
        col.background = ui.rounded(Color.parseColor("#FAF6EE"), 24)
        val head = ui.row()
        head.addView(ui.text(icon, 26f, ui.theme.primary), ui.wrap())
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, 0, 0) }
        ht.addView(ui.text(title, 19f, ui.theme.primary, bold = true))
        if (subtitle != null) ht.addView(ui.text(subtitle, 13f, Colors.MUTED))
        head.addView(ht, ui.weight())
        head.addView(ui.text("✕", 24f, Colors.TEXT).apply { setPadding(ui.dp(10), 0, ui.dp(6), 0); setOnClickListener { dialog.dismiss() } })
        col.addView(head, ui.match(0, 0, 0, 10))
        build(col, dialog)
        dialog.setContentView(ui.scroll(col))
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                r.background = ui.rounded(if (sel) Color.parseColor("#FCE4EA") else Color.WHITE, 18, if (sel) t.primary else Color.parseColor("#E6DCCB"), if (sel) 2 else 1)
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

    // ---------- الإشعارات ----------
    fun notifications(activity: Activity) {
        sheet(activity, "🔔", "إشعارات مواقيت الصلاة", "تنبيهات تظهر على شاشة القفل وستارة الإشعارات في جوالك") { col, _ ->
            notificationSettings(activity, col)
        }
    }

    fun notificationSettings(activity: Activity, col: LinearLayout) {
        val ui = Ui(activity)
        val today = CalendarData.byDate(activity, java.time.LocalDate.now())
        col.addView(ui.primaryButton("🔔 تجربة وفحص الإشعار الخارجي الآن") {
            Notifier.showPrayer(activity, Prayer.FAJR, "تجربة")
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
        col.addView(ui.toggle("إشعار مناسبة اليوم (٧ صباحاً)", Prefs.occasionOn(activity)) { on -> Prefs.setOccasionOn(activity, on); AlarmScheduler.scheduleNext(activity) })
        col.addView(ui.text("صوت التنبيه حالياً نغمة المنبه في جوالك. أصوات الأذان الشيعي ستُضاف في التحديث القادم.", 12f, Colors.MUTED).apply { setPadding(0, ui.dp(6), 0, 0) })
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
                    v.setTextColor(if (sel) Color.WHITE else Colors.TEXT)
                    v.background = if (sel) ui.rounded(ui.theme.primary, 14) else ui.rounded(Color.WHITE, 14, Color.parseColor("#E3D6C2"), 1)
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

            col.addView(ui.text("٢. ألوان خلفية الودجت:", 16f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val bgViews = ArrayList<Pair<String, android.widget.TextView>>()
            fun paintBg() = bgViews.forEach { (id, v) ->
                val c = WidgetPrefs.BACKGROUNDS.first { it.first == id }.third
                val solid = if (id == "glass") Color.parseColor("#C9C6C1") else c or (0xFF shl 24)
                v.background = ui.rounded(solid, 12, if (WidgetPrefs.bg(activity) == id) ui.theme.accent else Color.TRANSPARENT, 3)
            }
            WidgetPrefs.BACKGROUNDS.chunked(3).forEach { chunk ->
                val r = ui.row()
                chunk.forEach { (id, name, _) ->
                    val v = ui.text(name, 14f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
                        setOnClickListener { WidgetPrefs.setBg(activity, id); paintBg() }
                    }
                    bgViews += id to v
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                repeat(3 - chunk.size) { r.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
                col.addView(r)
            }
            paintBg()

            col.addView(ui.text("٣. خيارات المحتوى (ماذا سوف يظهر داخل الودجت):", 16f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val opts = ui.card(Color.parseColor("#F3ECE0"))
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
            else -> Color.parseColor("#FFF8EC") to ui.theme.primary
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
            val head = ui.card(if (dark) Color.parseColor("#1E1E1E") else if (d.isJoy) Color.parseColor("#FFF8D6") else Color.WHITE, ui.theme.accent)
            val r = ui.row()
            r.addView(dayCircle(activity, d, 60, onDark = dark))
            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, 0, 0) }
            info.addView(ui.text("${CalendarData.weekday(d)} • ${CalendarData.gregLabel(d.greg)}م", 13.5f, if (dark) Color.parseColor("#BFBFBF") else Colors.MUTED))
            info.addView(ui.text("${CalendarData.ar(d.zahra)} ${CalendarData.MONTHS[d.month - 1]} ${CalendarData.ar(CalendarData.YEAR)}هـ", 20f, if (dark) Color.WHITE else ui.theme.primary, bold = true))
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
                    c.addView(ui.text(v, 18f, ui.theme.primary, bold = true).apply { gravity = Gravity.CENTER })
                    row.addView(c, ui.weight())
                }
                repeat(3 - chunk.size) { row.addView(View(activity), ui.weight()) }
                times.addView(row)
            }
            col.addView(times)

            // Other columns as small pills
            val info2 = listOf("عقد النكاح" to d.mark, "السكن" to d.sakan, "الحجامة" to d.hijama, "الطالع" to d.tale).filter { it.second.isNotBlank() }
            info2.chunked(2).forEach { chunk ->
                val row = ui.row()
                chunk.forEach { (k, v) ->
                    val bad = v == "غير صالح" || v.startsWith("ردي") || (k == "عقد النكاح" && v != "جيد")
                    row.addView(ui.text("$k: $v", 14f, if (bad) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"), bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
                        background = ui.rounded(Color.WHITE, 12, Color.parseColor("#E8DFD0"), 1)
                    }, ui.weight().apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
                }
                col.addView(row)
            }

            // Share
            val text = shareText(d)
            val sr = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            sr.addView(ui.primaryButton("مشاركة") {
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
                addView(ui.text(CONTACT_NAME, 20f, ui.theme.primary, bold = true))
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
