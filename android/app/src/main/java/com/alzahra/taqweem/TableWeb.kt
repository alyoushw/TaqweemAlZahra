package com.alzahra.taqweem

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.time.LocalDate

/**
 * The month table, drawn as HTML inside a WebView so it pinch-zooms and pans smoothly in both
 * directions, like the printed page. Column order and colours follow the printed calendar.
 */
object TableWeb {

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun ar(s: String) = s.map { if (it.isDigit()) "٠١٢٣٤٥٦٧٨٩"[it - '0'] else it }.joinToString("")

    fun html(activity: Activity, month: Int): String {
        val t = Themes.current(activity)
        val days = CalendarData.month(activity, month)
        val today = LocalDate.now()
        val hasSakan = days.any { it.sakan.isNotBlank() }
        val hasSahur = days.any { it.times.sahur.isNotBlank() }
        val years = days.map { it.greg.year }.distinct().joinToString("<br>")

        val head = buildString {
            append("<th>اليوم</th><th>عقد<br>النكاح</th><th>الزهراء</th><th>أم<br>القرى</th><th>$years</th><th>الحجامة</th>")
            if (hasSakan) append("<th>السكن</th>")
            append("<th>الطالع</th><th class='ev'>المناسبات / ثواب زيارة الحسين عليه السلام</th>")
            if (hasSahur) append("<th>السحور</th>")
            append("<th>الإمساك</th><th>الصبح</th><th>الشروق</th><th>الظهر</th><th>المغرب</th><th>منتصف<br>الليل</th><th>القبلة</th>")
        }

        val rows = buildString {
            days.forEachIndexed { i, d ->
                val cls = if (d.greg == today) "today" else if (i % 2 == 1) "alt" else ""
                append("<tr class='$cls' id='r$i' onclick='Z.day($i)'>")
                val fri = d.greg.dayOfWeek.value == 5
                append("<td class='wd${if (fri) " fri" else ""}'>${CalendarData.weekday(d)}</td>")
                val mark = when {
                    d.mark == "جيد" -> "<span class='good'>جيد</span>"
                    d.mark.startsWith("مكروه") -> "<span class='small'>مكروه<br>نهاراً</span>"
                    else -> esc(d.mark)
                }
                append("<td>$mark</td>")
                val z = when (d.zahraColor) { "K" -> "box k"; "Y" -> "box y"; else -> "" }
                append("<td><span class='$z num'>${CalendarData.ar(d.zahra)}</span></td>")
                val u = when (d.ummAlQuraColor) { "D" -> "box d"; "N" -> "box n"; else -> "uq" }
                append("<td><span class='$u num'>${CalendarData.ar(d.ummAlQura)}</span></td>")
                val g = when (d.gregColor) { "G" -> "box g"; "O" -> "box o"; else -> "" }
                val gl = if (d.zahra == 1 || d.greg.dayOfMonth == 1) "${d.greg.dayOfMonth}/${d.greg.monthValue}" else "${d.greg.dayOfMonth}"
                append("<td><span class='$g num'>$gl</span></td>")
                append(if (d.hijama == "غير صالح") "<td><span class='box bad small'>غير صالح</span></td>" else "<td class='small'>${esc(d.hijama)}</td>")
                if (hasSakan) append("<td class='${if (d.sakan.startsWith("ردي")) "red" else "grn"}'>${esc(d.sakan)}</td>")
                append("<td class='tale${if (d.tale.length > 3) " small" else ""}'>${esc(d.tale)}</td>")
                val ev = d.occasions.joinToString(" / ") { o -> "<span class='c${o.color}'>${esc(o.text)}</span>" }
                append("<td class='ev'>$ev</td>")
                if (hasSahur) append("<td>${ar(d.times.sahur)}</td>")
                append("<td class='pink'>${ar(d.times.imsak)}</td><td>${ar(d.times.fajr)}</td><td class='blue'>${ar(d.times.sunrise)}</td>")
                append("<td>${ar(d.times.dhuhr)}</td><td class='pink'>${ar(d.times.maghrib)}</td><td>${ar(d.times.midnight)}</td>")
                append("<td class='blue'>${if (d.times.qibla.isBlank()) "-" else ar(d.times.qibla)}</td>")
                append("</tr>")
            }
        }

        val note = "نظراً لاختلاف الفلكيين في دخول الفجر الصادق فمقتضى الاحتياط الإمساك للصيام قبل أذان الصبح بـ (٨) دقائق وتأخير أداء صلاة الصبح عن الأذان بـ (١٣) دقيقة (تقويم مكتب السيد السيستاني)"
        val colCount = 16 + (if (hasSakan) 1 else 0) + (if (hasSahur) 1 else 0)

        return """<!doctype html><html dir="ltr"><head>
<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=5, user-scalable=yes">
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;padding:4px 6px 24px;background:${hex(t.pageBg)};font-family:sans-serif;color:#1a1a1a}
table{border-collapse:separate;border-spacing:0;background:#fff;border:2px solid ${hex(t.primary)};border-radius:10px;overflow:hidden;white-space:nowrap}
th{position:sticky;top:0;z-index:2;background:${hex(t.primary)};color:#fff;font-size:15px;padding:10px 8px;border-left:1px solid ${hex(t.accent)};border-bottom:2px solid ${hex(t.accent)};line-height:1.25}
td{font-size:16px;font-weight:bold;text-align:center;padding:0 6px;height:52px;border-left:1px solid #e3d8c6;border-bottom:1px solid #e3d8c6}
tr.alt td{background:#FBF7F0}
tr.today td{background:${hex(t.todayRow)};border-top:2px solid ${hex(t.primary)};border-bottom:2px solid ${hex(t.primary)}}
td.ev{text-align:right;min-width:430px;white-space:normal;line-height:1.35;font-size:15.5px;padding:4px 10px}
th.ev{min-width:430px}
.wd{min-width:74px}.fri{color:#C62828}
.num{display:inline-block;min-width:46px;padding:9px 4px;border-radius:6px}
.box{color:#fff}.k{background:#111}.y{background:#FFD600;color:#000}.d{background:#1B5E20}.n{background:#8D5A1E}
.g{background:#8BC34A;color:#000}.o{background:#F4511E}.uq{color:#8a8a8a}
.bad{background:#E0482A;display:inline-block;padding:9px 8px;border-radius:6px}
.small{font-size:13px;line-height:1.2}.good{color:#1565C0}
.red{color:#C62828}.grn{color:#2E7D32}.pink{color:#C2185B}.blue{color:#1565C0}
.tale{background:#F6E7A6 !important;min-width:60px}
.ck{color:#1a1a1a}.cr{color:#C62828}.cb{color:#1565C0}.cg{color:#2E7D32}
.note td{background:#D84315 !important;color:#fff;font-size:13px;height:auto;padding:8px;white-space:normal;text-align:right}
</style></head><body>
<table dir="rtl"><thead><tr>$head</tr></thead><tbody>$rows
<tr class="note"><td colspan="$colCount">$note</td></tr></tbody></table>
<script>
function goToday(){var e=document.querySelector('tr.today');var y=0;if(e){y=Math.max(0,e.getBoundingClientRect().top+window.scrollY-120);}window.scrollTo(document.documentElement.scrollWidth,y);}
</script>
</body></html>"""
    }

    /** Show the weekday side (the table's right edge) first. */
    fun toRightEdge(view: WebView) {
        view.post { view.scrollTo(1_000_000, view.scrollY) }
    }

    class Bridge(private val activity: Activity, private val month: Int) {
        @JavascriptInterface
        fun day(i: Int) {
            activity.runOnUiThread {
                CalendarData.month(activity, month).getOrNull(i)?.let { Dialogs.day(activity, it) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun create(activity: Activity, month: Int, onScale: (Int) -> Unit): WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        setBackgroundColor(Themes.current(activity).pageBg)
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(activity, month), "Z")
        var base = 0f
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.postDelayed({ view.evaluateJavascript("goToday()") { toRightEdge(view) } }, 120)
            }
            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                if (base == 0f) base = oldScale
                onScale(((newScale / base) * 100).toInt())
            }
        }
        loadDataWithBaseURL("file:///android_asset/", html(activity, month), "text/html", "utf-8", null)
    }
}
