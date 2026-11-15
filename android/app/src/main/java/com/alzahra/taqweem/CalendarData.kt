package com.alzahra.taqweem

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One coloured piece of occasion text. Colours follow the printed calendar: k=black, r=red, b=blue, g=green. */
data class Occasion(val text: String, val color: Char)

data class DayTimes(
    val imsak: String, val fajr: String, val sunrise: String, val dhuhr: String,
    val maghrib: String, val midnight: String, val qibla: String, val sahur: String,
)

data class Day(
    val month: Int,          // Hijri month 1..12 (Zahra calendar)
    val zahra: Int,          // day number in the "الزهراء" column
    val ummAlQura: Int,      // day number in the "أم القرى" column
    val ummAlQuraColor: String, // N = brown, D = dark green (best day to fast), "" = none
    val greg: LocalDate,
    val gregColor: String,   // G = light green (official holiday), O = orange (first working day)
    val zahraColor: String,  // K = black (sorrow), Y = yellow (joy)
    val mark: String,        // "عقد النكاح" column: جيد / ك / ع / ق / و / ن / مكروه نهاراً ...
    val sakan: String,       // "السكن": حسن / ردي
    val hijama: String,
    val tale: String,
    val times: DayTimes,
    val occasions: List<Occasion>,
) {
    val isSorrow get() = zahraColor == "K"
    val isJoy get() = zahraColor == "Y"
}

enum class Prayer(val key: String, val label: String) {
    IMSAK("imsak", "الإمساك"),
    FAJR("fajr", "الصبح"),
    SUNRISE("sunrise", "الشروق"),
    DHUHR("dhuhr", "الظهر"),
    MAGHRIB("maghrib", "المغرب"),
    MIDNIGHT("midnight", "منتصف الليل"),
}

object CalendarData {
    const val YEAR = 1448
    val MONTHS = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )
    val WEEKDAYS = mapOf(
        1 to "الإثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس",
        5 to "الجمعة", 6 to "السبت", 7 to "الأحد",
    )

    @Volatile private var cache: List<Day>? = null

    fun days(ctx: Context): List<Day> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val json = ctx.applicationContext.assets.open("calendar.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(json).getJSONArray("days")
            val list = ArrayList<Day>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getJSONObject("t")
                val ev = o.getJSONArray("ev")
                val occ = (0 until ev.length()).map { j ->
                    val e = ev.getJSONObject(j)
                    Occasion(e.getString("t"), e.getString("c").first())
                }
                list += Day(
                    month = o.getInt("m"), zahra = o.getInt("z"), ummAlQura = o.getInt("uq"),
                    ummAlQuraColor = o.getString("uqC"), greg = LocalDate.parse(o.getString("g")),
                    gregColor = o.getString("gC"), zahraColor = o.getString("zC"),
                    mark = o.getString("mark"), sakan = o.getString("sakan"), hijama = o.getString("hij"),
                    tale = o.getString("tale"),
                    times = DayTimes(
                        t.getString("imsak"), t.getString("fajr"), t.getString("sunrise"), t.getString("dhuhr"),
                        t.getString("maghrib"), t.getString("midnight"), t.getString("qibla"), t.getString("sahur"),
                    ),
                    occasions = occ,
                )
            }
            cache = list
            return list
        }
    }

    fun byDate(ctx: Context, date: LocalDate): Day? = days(ctx).firstOrNull { it.greg == date }
    fun month(ctx: Context, m: Int): List<Day> = days(ctx).filter { it.month == m }

    /** Month index (1..12) containing today, or the nearest month when today is outside 1448. */
    fun currentMonth(ctx: Context): Int {
        val all = days(ctx)
        val today = LocalDate.now()
        return when {
            today.isBefore(all.first().greg) -> 1
            today.isAfter(all.last().greg) -> 12
            else -> byDate(ctx, today)?.month ?: 1
        }
    }

    /**
     * The printed calendar writes every time on a 12-hour clock with no AM/PM.
     * Imsak, fajr and sunrise are morning; dhuhr is 11:xx or 12:xx; maghrib, midnight
     * (10:45–11:17 at night) and the qibla time are afternoon/evening.
     */
    fun timeOf(day: Day, p: Prayer): LocalTime? {
        val raw = when (p) {
            Prayer.IMSAK -> day.times.imsak
            Prayer.FAJR -> day.times.fajr
            Prayer.SUNRISE -> day.times.sunrise
            Prayer.DHUHR -> day.times.dhuhr
            Prayer.MAGHRIB -> day.times.maghrib
            Prayer.MIDNIGHT -> day.times.midnight
        }
        val (h, m) = parse(raw) ?: return null
        val hour = when (p) {
            Prayer.IMSAK, Prayer.FAJR, Prayer.SUNRISE -> h
            Prayer.DHUHR -> if (h < 10) h + 12 else h
            Prayer.MAGHRIB, Prayer.MIDNIGHT -> if (h < 12) h + 12 else h
        }
        return LocalTime.of(hour % 24, m)
    }

    private fun parse(s: String): Pair<Int, Int>? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h to m
    }

    data class NextPrayer(val prayer: Prayer, val at: LocalDateTime)

    /** The next fajr / sunrise / dhuhr / maghrib / midnight after [now], looking into tomorrow if needed. */
    fun nextPrayer(ctx: Context, now: LocalDateTime = LocalDateTime.now()): NextPrayer? {
        val order = listOf(Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.MAGHRIB, Prayer.MIDNIGHT)
        for (offset in 0L..1L) {
            val date = now.toLocalDate().plusDays(offset)
            val day = byDate(ctx, date) ?: continue
            for (p in order) {
                val t = timeOf(day, p) ?: continue
                val at = date.atTime(t)
                if (at.isAfter(now)) return NextPrayer(p, at)
            }
        }
        return null
    }

    fun hijriLabel(day: Day) = "${ar(day.zahra)} ${MONTHS[day.month - 1]} ${ar(YEAR)}هـ"
    fun weekday(day: Day) = WEEKDAYS[day.greg.dayOfWeek.value] ?: ""
    fun gregLabel(d: LocalDate) = "${d.dayOfMonth}/${d.monthValue}/${d.year}"

    private const val AR_DIGITS = "٠١٢٣٤٥٦٧٨٩"
    fun ar(n: Int): String = n.toString().map { if (it.isDigit()) AR_DIGITS[it - '0'] else it }.joinToString("")
}
