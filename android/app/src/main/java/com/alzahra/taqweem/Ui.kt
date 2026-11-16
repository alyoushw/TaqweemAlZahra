package com.alzahra.taqweem

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** App colour themes (same five choices as the original app). */
data class AppTheme(
    val id: String,
    val title: String,
    val desc: String,
    val primary: Int,
    val primaryDark: Int,
    val accent: Int,
    val todayRow: Int,
    val pageBg: Int,
    val dark: Boolean = false,
)

object Themes {
    val ALL = listOf(
        AppTheme("dark", "الوضع الداكن الفاخر", "مظهر ليلي مريح للعين مع إبراز ذهبي خاشع لليوم الحالي",
            Color.parseColor("#1A2530"), Color.parseColor("#121A22"), Color.parseColor("#E0B84A"), Color.parseColor("#3A3420"), Color.parseColor("#10161C"), dark = true),
        AppTheme("maroon", "عنابي تقويم الزهراء الأصيل", "اللون العنابي التراثي المستوحى من تقويم الزهراء الورقي",
            Color.parseColor("#6B1723"), Color.parseColor("#4A0F18"), Color.parseColor("#D4A92F"), Colors.SELECTED, Colors.SOFT),
        AppTheme("green", "الأخضر الزمردي الإسلامي", "أخضر رحماني بهيج يعكس روح الهداية والسكينة",
            Color.parseColor("#1B6B2A"), Color.parseColor("#11471B"), Color.parseColor("#E0C35A"), Color.parseColor("#E3F4E5"), Color.parseColor("#F1F5EE")),
        AppTheme("gold", "الذهبي الملكي المشرف", "ذهبي المشاهد المقدسة والعتبات المنورة",
            Color.parseColor("#9C7413"), Color.parseColor("#6E510B"), Color.parseColor("#FFE08A"), Color.parseColor("#FFF4D2"), Color.parseColor("#FAF5E8")),
        AppTheme("blue", "الأزرق الياقوتي الملكي", "أزرق سماوي عميق يعكس روحانية السماء والهدوء",
            Color.parseColor("#0D47A1"), Color.parseColor("#082F6B"), Color.parseColor("#FFD54F"), Color.parseColor("#E3EEFF"), Color.parseColor("#EEF3FA")),
    )

    fun current(ctx: Context): AppTheme {
        val id = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE).getString("theme", "maroon")
        val t = ALL.firstOrNull { it.id == id } ?: ALL[1]
        Colors.dark = t.dark
        return t
    }

    fun set(ctx: Context, id: String) =
        ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE).edit().putString("theme", id).apply()
}

/** Small view-building helpers shared by the screens. */
class Ui(val ctx: Context) {
    val theme get() = Themes.current(ctx)
    /** Colour for headings/accent text: brand colour on light, gold on dark. */
    val ink get() = if (theme.dark) theme.accent else theme.primary
    /** Fill of selected chips and main buttons, and the text on top of it. */
    val fill get() = if (theme.dark) theme.accent else theme.primary
    val onFill get() = if (theme.dark) Color.parseColor("#14181C") else Color.WHITE
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Float) = (v * density).toInt()

    fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun rounded(color: Int, radiusDp: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    fun column(pad: Int = 12) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun scroll(v: View) = ScrollView(ctx).apply { isFillViewport = true; addView(v) }

    fun wrap(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun match(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    /** Filled theme button. */
    fun primaryButton(label: String, onClick: () -> Unit) = text(label, 15f, onFill, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(fill, 14)
        layoutParams = match(0, 6, 0, 6)
        setOnClickListener { onClick() }
    }

    /** Outlined button. */
    fun outlineButton(label: String, onClick: () -> Unit) = text(label, 15f, ink, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rounded(Colors.CARD, 14, Colors.STROKE, 1)
        layoutParams = match(0, 6, 0, 6)
        setOnClickListener { onClick() }
    }

    fun card(bg: Int = Colors.CARD, stroke: Int = Colors.STROKE) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = rounded(bg, 18, stroke, 1)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = match(0, 6, 0, 6)
    }

    fun title(s: String, size: Float = 17f) = text(s, size, ink, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) }

    fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        text = label
        textSize = 15f
        setTextColor(Colors.TEXT)
        isChecked = initial
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** Rounded "chip" used for the month bar and tabs. */
    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 15f, if (selected) onFill else Colors.TEXT, bold = selected).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = if (selected) rounded(fill, 14) else rounded(Colors.CARD, 14, Colors.STROKE, 1)
        layoutParams = wrap(4, 0, 4, 0)
        setOnClickListener { onClick() }
    }
}
