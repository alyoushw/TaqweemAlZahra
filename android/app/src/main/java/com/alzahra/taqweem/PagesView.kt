package com.alzahra.taqweem

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient

/** The scanned calendar pages, shown in a WebView so pinch-zoom and lazy loading come for free. */
object PagesView {
    private val GREG_MONTHS = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private val MONTH_TITLES = listOf(
        "محرم الحرام", "صفر الخير", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب الأصب", "شعبان المعظم", "رمضان المبارك", "شوال المكرم", "ذو القعدة الحرام", "ذو الحجة الحرام",
    )

    fun pageTitle(ctx: Context, i: Int): String {
        if (i == 13) return "صفحة التنبيهات والإرشادات"
        val days = CalendarData.month(ctx, i)
        val a = days.first().greg
        val b = days.last().greg
        val greg = if (a.year == b.year) "${GREG_MONTHS[a.monthValue - 1]} / ${GREG_MONTHS[b.monthValue - 1]} ${CalendarData.ar(b.year)}"
        else "${GREG_MONTHS[a.monthValue - 1]} ${CalendarData.ar(a.year)} / ${GREG_MONTHS[b.monthValue - 1]} ${CalendarData.ar(b.year)}"
        return "شهر ${MONTH_TITLES[i - 1]} ${CalendarData.ar(CalendarData.YEAR)} هـ ($greg)"
    }

    private fun html(ctx: Context, pages: IntRange, primary: Int, accent: Int): String {
        val p = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and primary)
        val a = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and accent)
        val cards = pages.joinToString("") { i ->
            """<div class="card" id="p$i"><div class="head"><span class="t">${pageTitle(ctx, i)}</span><span class="n">صفحة $i من ١٣</span></div>
               <img src="pages/page_%02d.jpg" loading="lazy"/></div>""".format(java.util.Locale.US, i)
        }
        return """<!doctype html><html dir="rtl"><head><meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes">
<style>
body{margin:0;padding:8px;background:${String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and Colors.BG)};font-family:sans-serif}
.card{background:${String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and Colors.CARD)};border:2px solid $a;border-radius:16px;overflow:hidden;margin:0 0 14px}
.head{background:$p;color:#fff;display:flex;align-items:center;justify-content:space-between;padding:10px 12px;gap:8px}
.t{font-weight:bold;font-size:15px}
.n{background:$a;color:#3a2a00;border-radius:8px;padding:5px 9px;font-weight:bold;font-size:13px;white-space:nowrap}
img{display:block;width:100%;height:auto;min-height:120px;background:#eee}
</style></head><body>$cards
<script>
var last=0;
function visible(){var c=document.querySelectorAll('.card'),mid=window.innerHeight*0.35,best=1;
 for(var i=0;i<c.length;i++){if(c[i].getBoundingClientRect().top<=mid)best=parseInt(c[i].id.substring(1));}
 if(best!==last){last=best;if(window.Z)Z.page(best);}}
window.addEventListener('scroll',function(){window.requestAnimationFrame(visible);},{passive:true});
</script></body></html>"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun all(ctx: Context, startPage: Int, onScale: (Int) -> Unit, onPage: (Int) -> Unit): WebView =
        web(ctx, 1..13, onScale, startPage).apply {
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun page(p: Int) { post { onPage(p) } }
            }, "Z")
        }

    fun zoomableWeb(ctx: Context, index: Int): WebView = web(ctx, index..index, {})

    @SuppressLint("SetJavaScriptEnabled")
    private fun web(ctx: Context, pages: IntRange, onScale: (Int) -> Unit, startPage: Int = 1): WebView {
        val theme = Themes.current(ctx)
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(theme.pageBg)
            var base = 0f
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (startPage > 1) view.postDelayed({ scrollTo(view, startPage) }, 150)
                }
                override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                    if (base == 0f) base = oldScale
                    onScale(((newScale / base) * 100).toInt())
                }
            }
            loadDataWithBaseURL("file:///android_asset/", html(ctx, pages, theme.primary, theme.accent), "text/html", "utf-8", null)
        }
    }

    fun scrollTo(web: WebView, page: Int) {
        web.evaluateJavascript("last=$page;document.getElementById('p$page').scrollIntoView();", null)
    }
}
