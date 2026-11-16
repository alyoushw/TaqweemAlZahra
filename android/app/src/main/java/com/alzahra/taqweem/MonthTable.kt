package com.alzahra.taqweem

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/**
 * Builds one month as a table laid out like the printed calendar page:
 * اليوم | عقد النكاح | الزهراء | أم القرى | الميلادي | الحجامة | السكن | الطالع | المناسبات | (السحور) | الإمساك | الصبح | الشروق | الظهر | المغرب | منتصف الليل | القبلة
 */
class MonthTable(private val ui: Ui, private val onDayClick: (Day) -> Unit) {

    private class Col(val title: String, val width: Int, val cell: (Day) -> Cell)
    private data class Cell(
        val text: CharSequence,
        val fg: Int = Colors.TEXT,
        val bg: Int = Color.TRANSPARENT,
        val bold: Boolean = false,
        val small: Boolean = false,
        val start: Boolean = false,
        val keepTodayBg: Boolean = true,
    )

    private val red = Color.parseColor("#C62828")
    private val blue = Color.parseColor("#1565C0")
    private val pink = Color.parseColor("#C2185B")
    private val green = Color.parseColor("#2E7D32")
    private val grid = Color.parseColor("#D9CBB3")
    private val muted = Color.parseColor("#8A8A8A")

    private fun columns(days: List<Day>): List<Col> {
        val years = days.map { it.greg.year }.distinct()
        val gregTitle = years.joinToString("\n")
        val hasSakan = days.any { it.sakan.isNotBlank() }
        val hasSahur = days.any { it.times.sahur.isNotBlank() }
        val cols = mutableListOf(
            Col("اليوم", 76) { d ->
                Cell(CalendarData.weekday(d), if (d.greg.dayOfWeek.value == 5) red else Colors.TEXT, bold = true)
            },
            Col("عقد\nالنكاح", 62) { d ->
                when {
                    d.mark == "جيد" -> Cell("جيد", blue, bold = true)
                    d.mark.startsWith("مكروه") -> Cell("مكروه\nنهاراً", Colors.TEXT, small = true)
                    else -> Cell(d.mark, Colors.TEXT, bold = true)
                }
            },
            Col("الزهراء", 54) { d ->
                val (bg, fg) = Colors.zahraCell(d.zahraColor)
                Cell(CalendarData.ar(d.zahra), fg, bg, bold = true, keepTodayBg = bg == Color.TRANSPARENT)
            },
            Col("أم\nالقرى", 54) { d ->
                val (bg, fg) = Colors.ummAlQuraCell(d.ummAlQuraColor)
                Cell(CalendarData.ar(d.ummAlQura), if (bg == Color.TRANSPARENT) muted else fg, bg, bold = true, keepTodayBg = bg == Color.TRANSPARENT)
            },
            Col(gregTitle, 62) { d ->
                val (bg, fg) = Colors.gregCell(d.gregColor)
                val label = if (d.zahra == 1 || d.greg.dayOfMonth == 1) "${d.greg.dayOfMonth}/${d.greg.monthValue}" else "${d.greg.dayOfMonth}"
                Cell(label, fg, bg, bold = true, keepTodayBg = bg == Color.TRANSPARENT)
            },
            Col("الحجامة", 80) { d ->
                if (d.hijama == "غير صالح") Cell("غير صالح", Color.WHITE, Color.parseColor("#E0482A"), bold = true, small = true, keepTodayBg = false)
                else Cell(d.hijama, Colors.TEXT, small = true)
            },
        )
        if (hasSakan) cols += Col("السكن", 56) { d ->
            Cell(d.sakan, if (d.sakan.startsWith("ردي")) red else green, bold = true)
        }
        cols += Col("الطالع", 66) { d -> Cell(d.tale, Colors.TEXT, Color.parseColor("#F6E7A6"), bold = true, small = d.tale.length > 3, keepTodayBg = false) }
        cols += Col("المناسبات / ثواب زيارة الحسين عليه السلام", 470) { d -> Cell(occasions(d), Colors.TEXT, start = true, bold = true) }
        if (hasSahur) cols += Col("السحور", 58) { d -> Cell(arTime(d.times.sahur), Colors.TEXT, bold = true) }
        cols += listOf(
            Col("الإمساك", 58) { d -> Cell(arTime(d.times.imsak), pink, bold = true) },
            Col("الصبح", 58) { d -> Cell(arTime(d.times.fajr), Colors.TEXT, bold = true) },
            Col("الشروق", 58) { d -> Cell(arTime(d.times.sunrise), blue, bold = true) },
            Col("الظهر", 58) { d -> Cell(arTime(d.times.dhuhr), Colors.TEXT, bold = true) },
            Col("المغرب", 58) { d -> Cell(arTime(d.times.maghrib), pink, bold = true) },
            Col("منتصف\nالليل", 62) { d -> Cell(arTime(d.times.midnight), Colors.TEXT, bold = true) },
            Col("القبلة", 58) { d -> Cell(if (d.times.qibla.isBlank()) "-" else arTime(d.times.qibla), blue, bold = true) },
        )
        return cols
    }

    private fun occasions(d: Day): CharSequence {
        val sb = SpannableStringBuilder()
        d.occasions.forEachIndexed { i, o ->
            if (i > 0) sb.append(" / ")
            val s = sb.length
            sb.append(o.text)
            sb.setSpan(ForegroundColorSpan(Colors.occasion(o.color)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** The printed calendar uses Arabic-Indic digits for times. */
    private fun arTime(s: String): String =
        s.map { if (it.isDigit()) "٠١٢٣٤٥٦٧٨٩"[it - '0'] else it }.joinToString("")

    class Built(val header: View, val body: View, val todayOffset: Int)

    /** @param zoom 0.6..1.8 */
    fun build(month: Int, zoom: Float): Built {
        val days = CalendarData.month(ui.ctx, month)
        val cols = columns(days)
        val theme = ui.theme
        val rowH = ui.dpf(48 * zoom)
        val today = LocalDate.now()

        val table = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(grid)
            setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }

        // Header row
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isBaselineAligned = false
            setBackgroundColor(theme.accent)
            setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }
        cols.forEach { c ->
            header.addView(cellView(c.title, Color.WHITE, theme.primary, true, false, false, zoom, c.width, ui.dpf(62 * zoom)).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * zoom)
            }, LinearLayout.LayoutParams(ui.dpf(c.width * zoom), ui.dpf(62 * zoom)).apply { setMargins(0, 0, ui.dp(1), 0) })
        }

        var todayIndex = -1
        days.forEachIndexed { i, d ->
            val isToday = d.greg == today
            if (isToday) todayIndex = i
            val baseBg = when {
                isToday -> theme.todayRow
                i % 2 == 0 -> Color.WHITE
                else -> Color.parseColor("#FBF7F0")
            }
            val row = LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setBackgroundColor(if (isToday) theme.primary else grid)
                setPadding(0, if (isToday) ui.dp(2) else ui.dp(1), 0, if (isToday) ui.dp(2) else 0)
                isBaselineAligned = false
                setOnClickListener { onDayClick(d) }
            }
            cols.forEach { c ->
                val cell = c.cell(d)
                val bg = if (cell.bg != Color.TRANSPARENT) cell.bg else baseBg
                val v = cellView(cell.text, cell.fg, bg, cell.bold || isToday, cell.small, cell.start, zoom, c.width, rowH)
                if (cell.bg != Color.TRANSPARENT && !cell.keepTodayBg) {
                    // Coloured cells keep a thin inner frame like the printed page.
                    v.background = ui.rounded(cell.bg, 4, Color.WHITE, 1)
                    val holder = LinearLayout(ui.ctx).apply { setBackgroundColor(baseBg); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)); addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
                    row.addView(holder, LinearLayout.LayoutParams(ui.dpf(c.width * zoom), rowH).apply { setMargins(0, 0, ui.dp(1), 0) })
                } else {
                    row.addView(v, LinearLayout.LayoutParams(ui.dpf(c.width * zoom), rowH).apply { setMargins(0, 0, ui.dp(1), 0) })
                }
            }
            table.addView(row)
        }

        // Footer note, as printed at the bottom of every page.
        val total = cols.sumOf { ui.dpf(it.width * zoom) + ui.dp(1) }
        table.addView(ui.text(
            "نظراً لاختلاف الفلكيين في دخول الفجر الصادق فمقتضى الاحتياط الإمساك للصيام قبل أذان الصبح بـ (٨) دقائق وتأخير أداء صلاة الصبح عن الأذان بـ (١٣) دقيقة (تقويم مكتب السيد السيستاني)",
            12f * zoom, Color.WHITE, bold = true,
        ).apply {
            setBackgroundColor(Color.parseColor("#D84315"))
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(total, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Approximate offset of today's row from the top of the body.
        val todayOffset = if (todayIndex >= 0) todayIndex * (rowH + ui.dp(1)) else -1
        return Built(header, table, todayOffset)
    }

    private fun cellView(
        s: CharSequence, fg: Int, bg: Int, bold: Boolean, small: Boolean, start: Boolean,
        zoom: Float, widthDp: Int, heightPx: Int,
    ) = TextView(ui.ctx).apply {
        text = s
        setTextColor(fg)
        setBackgroundColor(bg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (small) 12.5f else 15f) * zoom)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = if (start) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_RTL
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(ui.dp(4), 0, ui.dp(4), 0)
        setLineSpacing(0f, 0.95f)
        includeFontPadding = false
        minHeight = heightPx
    }
}
