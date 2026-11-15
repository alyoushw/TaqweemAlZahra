package com.alzahra.taqweem

import android.graphics.Color

/** Colour meanings from the calendar's own key (instructions page, item 20). */
object Colors {
    val PRIMARY = Color.parseColor("#5C1D24")
    val PRIMARY_DARK = Color.parseColor("#3E1318")
    val GOLD = Color.parseColor("#D4AF37")
    val GOLD_LIGHT = Color.parseColor("#F3D77A")
    val BG = Color.parseColor("#FAF6EE")
    val CARD = Color.WHITE
    val TEXT = Color.parseColor("#1A1A1A")
    val MUTED = Color.parseColor("#6B6B6B")

    val SORROW = Color.parseColor("#111111")      // أسود: يوم حزن
    val JOY = Color.parseColor("#FFD600")         // أصفر: يوم فرح
    val HOLIDAY = Color.parseColor("#8BC34A")     // أخضر فاتح: إجازة رسمية
    val FIRST_WORK = Color.parseColor("#F4511E")  // برتقالي: أول يوم في الدوام الرسمي
    val BEST_FAST = Color.parseColor("#1B5E20")   // أخضر غامق: أفضل أيام السنة للصوم
    val UQ_MARK = Color.parseColor("#8D5A1E")     // بني: كما هو مطبوع في عمود أم القرى

    /** Text colours used for occasions in the printed calendar. */
    fun occasion(c: Char) = when (c) {
        'r' -> Color.parseColor("#C62828")
        'b' -> Color.parseColor("#1565C0")
        'g' -> Color.parseColor("#2E7D32")
        else -> TEXT
    }

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
