package com.alzahra.taqweem

import android.graphics.Color

/** Colour meanings from the calendar's own key (instructions page, item 20). */
object Colors {
    val PRIMARY = Color.parseColor("#5C1D24")
    val PRIMARY_DARK = Color.parseColor("#3E1318")
    val GOLD = Color.parseColor("#D4AF37")
    val GOLD_LIGHT = Color.parseColor("#F3D77A")
    /** True while the dark theme is active; set by [Themes] when the app starts. */
    var dark = false

    // Surfaces and text: light palette / dark palette (dark is a calm slate, not pure black).
    val BG get() = if (dark) Color.parseColor("#10161C") else Color.parseColor("#FAF6EE")
    val CARD get() = if (dark) Color.parseColor("#19222B") else Color.WHITE
    val SOFT get() = if (dark) Color.parseColor("#222D37") else Color.parseColor("#F5EFE3")
    val STROKE get() = if (dark) Color.parseColor("#2D3A46") else Color.parseColor("#E6DCCB")
    val SELECTED get() = if (dark) Color.parseColor("#2A3440") else Color.parseColor("#FCE4EA")
    val JOY_SOFT get() = if (dark) Color.parseColor("#2F2A18") else Color.parseColor("#FFF8D6")
    val TODAY_SOFT get() = if (dark) Color.parseColor("#2A2716") else Color.parseColor("#FFF7E3")
    val TEXT get() = if (dark) Color.parseColor("#E9EDF1") else Color.parseColor("#1A1A1A")
    val MUTED get() = if (dark) Color.parseColor("#97A4B0") else Color.parseColor("#6B6B6B")

    val SORROW = Color.parseColor("#111111")      // أسود: يوم حزن
    val JOY = Color.parseColor("#FFD600")         // أصفر: يوم فرح
    val HOLIDAY = Color.parseColor("#8BC34A")     // أخضر فاتح: إجازة رسمية
    val FIRST_WORK = Color.parseColor("#F4511E")  // برتقالي: أول يوم في الدوام الرسمي
    val BEST_FAST = Color.parseColor("#1B5E20")   // أخضر غامق: أفضل أيام السنة للصوم
    val UQ_MARK = Color.parseColor("#8D5A1E")     // بني: كما هو مطبوع في عمود أم القرى

    /** Text colours used for occasions in the printed calendar. */
    fun occasion(c: Char) = when (c) {
        'r' -> if (dark) Color.parseColor("#FF7B72") else Color.parseColor("#C62828")
        'b' -> if (dark) Color.parseColor("#6CB6FF") else Color.parseColor("#1565C0")
        'g' -> if (dark) Color.parseColor("#7EE081") else Color.parseColor("#2E7D32")
        else -> TEXT
    }
    val GOOD get() = if (dark) Color.parseColor("#7EE081") else Color.parseColor("#2E7D32")
    val BAD get() = if (dark) Color.parseColor("#FF7B72") else Color.parseColor("#C62828")

    /** Background and text colour for the "الزهراء" day cell. */
    fun zahraCell(code: String): Pair<Int, Int> = when (code) {
        "K" -> SORROW to Color.WHITE
        "Y" -> JOY to Color.BLACK
        else -> Color.TRANSPARENT to TEXT
    }

    fun ummAlQuraCell(code: String): Pair<Int, Int> = when (code) {
        "D" -> BEST_FAST to Color.WHITE
        "N" -> UQ_MARK to Color.WHITE
        else -> Color.TRANSPARENT to MUTED
    }

    fun gregCell(code: String): Pair<Int, Int> = when (code) {
        "G" -> HOLIDAY to Color.BLACK
        "O" -> FIRST_WORK to Color.WHITE
        else -> Color.TRANSPARENT to TEXT
    }
}
