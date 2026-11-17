package com.alzahra.taqweem

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen reader for a dua or ziyarat, laid out like a prayer book: a light header with the title
 * in the middle and «عودة», then the text as justified flowing paragraphs. Size, colours and font are
 * chosen from the «Aa» button; the reading position is remembered.
 */
class ReaderActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var web: WebView
    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var backView: TextView
    private lateinit var aaView: TextView
    private var player: MediaPlayer? = null
    private lateinit var item: Prayers.Item

    private fun sp() = getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    private var size: Int
        get() = sp().getInt("reader_size2", 24)
        set(v) = sp().edit().putInt("reader_size2", v).apply()
    private var scheme: String
        get() = sp().getString("reader_scheme", if (Themes.current(this).dark) "night" else "paper") ?: "paper"
        set(v) = sp().edit().putString("reader_scheme", v).apply()
    private var font: String
        get() = sp().getString("reader_font", "sans") ?: "sans"
        set(v) = sp().edit().putString("reader_font", v).apply()

    /** background, text, notes, header background, header text */
    private data class Scheme(val name: String, val bg: String, val fg: String, val mu: String, val bar: String, val barFg: String)
    private val schemes = linkedMapOf(
        "paper" to Scheme("ورقي", "#F8F4EA", "#1B1B1B", "#8A6D3B", "#FAEBC8", "#2B2B2B"),
        "white" to Scheme("أبيض", "#FFFFFF", "#000000", "#7A7A7A", "#F1F1F1", "#222222"),
        "sepia" to Scheme("بني فاتح", "#F1E3C6", "#3B2A14", "#7A6447", "#E6D3AA", "#3B2A14"),
        "green" to Scheme("أخضر", "#EAF4EC", "#123B1E", "#4E7A58", "#D6EBDB", "#123B1E"),
        "night" to Scheme("ليلي", "#12181E", "#E9EDF1", "#B9A06A", "#1B242C", "#E9EDF1"),
        "black" to Scheme("أسود وذهبي", "#000000", "#F3D77A", "#B8A06A", "#121212", "#F3D77A"),
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        item = Prayers.find(this, intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Header: «Aa» (settings) on the right, title in the middle, «عودة» on the left.
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
        }
        aaView = ui.text("Aa", 18f, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { settings() }
        }
        titleView = ui.text(item.title, 19f, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        backView = ui.text("عودة", 18f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        header.addView(aaView, LinearLayout.LayoutParams(ui.dp(64), ui.dp(40)))
        header.addView(titleView, ui.weight())
        header.addView(backView, LinearLayout.LayoutParams(ui.dp(64), ui.dp(40)))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E8761E")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2)))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.builtInZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    apply()
                    val y = sp().getInt("reader_pos_${item.id}", 0)
                    if (y > 0) view.postDelayed({ view.evaluateJavascript("window.scrollTo(0,$y)", null) }, 150)
                }
            }
        }
        // Two-finger pinch changes the text size (the text re-wraps), not the page zoom.
        var pinchBase = 0f
        val pinch = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pinchBase = size.toFloat(); return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                pinchBase = (pinchBase * d.scaleFactor).coerceIn(16f, 56f)
                val n = pinchBase.toInt()
                if (n != size) { size = n; apply() }
                return true
            }
        })
        web.setOnTouchListener { _, ev ->
            pinch.onTouchEvent(ev)
            // While two fingers are down, keep the page still so only the text size changes.
            ev.pointerCount > 1 || pinch.isInProgress
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Immersive.apply(window)
        load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(window)
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("window.scrollY") { v -> v?.toDoubleOrNull()?.toInt()?.let { sp().edit().putInt("reader_pos_${item.id}", it).apply() } }
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Starts of a new thought in a dua (checked without tashkeel). */
    private val paragraphStarts = listOf("اللهم", "الهي", "إلهي", "يا رب", "السلام", "سبحانك", "يا من", "اسالك", "أسألك")
    private val harakat = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
    private fun startsThought(s: String): Boolean {
        val plain = harakat.replace(s.trim(), "").replace("ٱ", "ا").replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        return paragraphStarts.any { plain.startsWith(it.replace("أ", "ا").replace("إ", "ا")) }
    }

    /** Joins the short source phrases into book-style paragraphs: «phrase ، phrase ، … .» */
    private fun paragraphs(): String {
        val out = StringBuilder()
        val cur = ArrayList<String>()
        fun flush() {
            if (cur.isEmpty()) return
            val txt = cur.joinToString("\u00A0، ") { it.trim().trimEnd('،', ',', '.', '؛', ' ') } + "\u00A0."
            out.append("<p class='ar'>").append(esc(txt)).append("</p>")
            cur.clear()
        }
        for (l in item.lines) {
            when {
                l.isHeading -> { flush(); out.append(if (l.text == "❖") "<div class='sep'>❖</div>" else "<h3>${esc(l.text)}</h3>") }
                l.isNote -> { flush(); out.append("<p class='note'>${esc(l.text)}</p>") }
                else -> {
                    val len = cur.sumOf { it.length }
                    if (cur.isNotEmpty() && len > 220 && startsThought(l.text)) flush()
                    if (cur.isNotEmpty() && len > 700) flush()
                    cur += l.text
                }
            }
        }
        flush()
        return out.toString()
    }

    private fun load() {
        val html = """<!doctype html><html dir="rtl"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
@font-face{font-family:'ZSans';src:url('fonts/sans-400.woff2') format('woff2');font-weight:400}
@font-face{font-family:'ZSans';src:url('fonts/sans-700.woff2') format('woff2');font-weight:700}
@font-face{font-family:'ZNaskh';src:url('fonts/naskh-400.woff2') format('woff2');font-weight:400}
@font-face{font-family:'ZNaskh';src:url('fonts/naskh-700.woff2') format('woff2');font-weight:700}
:root{--bg:#F8F4EA;--fg:#1B1B1B;--mu:#8A6D3B;--sz:24px;--ff:'ZSans'}
html,body{background:var(--bg)}
body{margin:0;padding:22px 20px 90px;color:var(--fg);font-family:var(--ff),serif}
.ar{font-size:var(--sz);line-height:1.95;margin:0 0 1.6em;text-align:justify;text-align-last:right;word-spacing:.04em}
.note{font-size:calc(var(--sz)*.68);line-height:1.8;color:var(--mu);text-align:center;margin:.2em 0 1.2em;font-weight:700}
h3{font-size:calc(var(--sz)*.8);color:var(--mu);text-align:center;margin:1.4em 0 .8em}
.sep{text-align:center;color:var(--mu);margin:.4em 0 1.2em;font-size:calc(var(--sz)*.7)}
.src{font-size:13px;color:var(--mu);text-align:center;margin-top:2.4em;opacity:.8}
</style></head><body>${paragraphs()}<div class='src'>${esc(item.source)}</div>
<script>function setStyle(bg,fg,mu,sz,ff){var r=document.documentElement.style;r.setProperty('--bg',bg);r.setProperty('--fg',fg);r.setProperty('--mu',mu);r.setProperty('--sz',sz+'px');r.setProperty('--ff',ff);}</script>
</body></html>"""
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        apply()
    }

    private fun apply() {
        val c = schemes[scheme] ?: schemes.getValue("paper")
        web.setBackgroundColor(Color.parseColor(c.bg))
        header.setBackgroundColor(Color.parseColor(c.bar))
        listOf(titleView, aaView).forEach { it.setTextColor(Color.parseColor(c.barFg)) }
        backView.setTextColor(Color.parseColor(c.mu))
        window.decorView.setBackgroundColor(Color.parseColor(c.bg))
        val ff = if (font == "naskh") "'ZNaskh'" else "'ZSans'"
        web.evaluateJavascript("setStyle('${c.bg}','${c.fg}','${c.mu}',$size,\"$ff\")", null)
    }

    /** «Aa»: text size, font, colours, audio. */
    private fun settings() {
        Dialogs.sheet(this, "Aa", "إعدادات القراءة", null) { col, dialog ->
            col.addView(ui.text("حجم الخط", 15f, Colors.TEXT, bold = true), ui.match(0, 4, 0, 4))
            val sizeRow = ui.row()
            val sizeLabel = ui.text("$size", 18f, Colors.TEXT, bold = true).apply { gravity = Gravity.CENTER }
            fun sbtn(label: String, d: Int) = ui.text(label, 20f, ui.onFill, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.rounded(ui.fill, 14)
                setOnClickListener { size = (size + d).coerceIn(16, 56); sizeLabel.text = "$size"; apply() }
            }
            sizeRow.addView(sbtn("أ−", -2), LinearLayout.LayoutParams(0, ui.dp(50), 1f))
            sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
            sizeRow.addView(sbtn("أ+", 2), LinearLayout.LayoutParams(0, ui.dp(50), 1f))
            col.addView(sizeRow)

            col.addView(ui.text("نوع الخط", 15f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val fontRow = ui.row()
            val fontViews = ArrayList<Pair<String, TextView>>()
            fun paintFonts() = fontViews.forEach { (id, v) ->
                v.background = ui.rounded(Colors.CARD, 14, if (id == font) ui.theme.accent else Colors.STROKE, if (id == font) 3 else 1)
            }
            listOf("sans" to "عصري واضح", "naskh" to "نسخ تقليدي").forEach { (id, name) ->
                val v = ui.text("بِسْمِ اللَّهِ — $name", 15f, Colors.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(6), ui.dp(14), ui.dp(6), ui.dp(14))
                    setOnClickListener { font = id; paintFonts(); apply() }
                }
                fontViews += id to v
                fontRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
            }
            paintFonts()
            col.addView(fontRow)

            col.addView(ui.text("الألوان", 15f, Colors.TEXT, bold = true), ui.match(0, 14, 0, 4))
            val schemeViews = ArrayList<Pair<String, TextView>>()
            fun paintSchemes() = schemeViews.forEach { (id, v) ->
                val c = schemes.getValue(id)
                v.background = ui.rounded(Color.parseColor(c.bg), 14, if (id == scheme) ui.theme.accent else Color.parseColor("#C8C2B4"), if (id == scheme) 3 else 1)
            }
            schemes.entries.chunked(3).forEach { chunk ->
                val r = ui.row()
                chunk.forEach { (id, c) ->
                    val v = ui.text(c.name, 14f, Color.parseColor(c.fg), bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(14))
                        setOnClickListener { scheme = id; paintSchemes(); apply() }
                    }
                    schemeViews += id to v
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                col.addView(r)
            }
            paintSchemes()

            if (item.audio != null && hasAudio(item.audio!!)) {
                val play = ui.outlineButton(if (player?.isPlaying == true) "⏸ إيقاف الصوت" else "▶ استماع للدعاء") {}
                play.setOnClickListener { toggleAudio(play) }
                col.addView(play, ui.match(0, 14, 0, 0))
            }
            col.addView(ui.primaryButton("تم") { dialog.dismiss() }, ui.match(0, 10, 0, 0))
        }
    }

    private fun hasAudio(file: String) = runCatching { assets.list("audio")?.contains(file) == true }.getOrDefault(false)

    private fun toggleAudio(btn: TextView) {
        val p = player
        when {
            p != null && p.isPlaying -> { p.pause(); btn.text = "▶ استماع للدعاء" }
            p != null -> { p.start(); btn.text = "⏸ إيقاف الصوت" }
            else -> runCatching {
                val afd = assets.openFd("audio/${item.audio}")
                player = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                    setOnCompletionListener { btn.text = "▶ استماع للدعاء" }
                    prepare(); start()
                }
                btn.text = "⏸ إيقاف الصوت"
            }.onFailure { Toast.makeText(this, "تعذر تشغيل الصوت", Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_ID, id))
    }
}

/** Full-text duas and ziyarat, loaded from assets/prayers.json. */
object Prayers {
    data class Line(val text: String, val isHeading: Boolean = false, val isNote: Boolean = false)
    data class Item(val id: String, val kind: String, val title: String, val subtitle: String, val intro: String,
                    val lines: List<Line>, val source: String, val audio: String?)

    @Volatile private var cache: List<Item>? = null

    fun all(ctx: Context): List<Item> = cache ?: synchronized(this) {
        cache ?: run {
            val json = ctx.assets.open("prayers.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o: JSONObject = arr.getJSONObject(i)
                val ls = o.getJSONArray("lines")
                Item(
                    o.getString("id"), o.getString("kind"), o.getString("title"), o.optString("subtitle"), o.optString("intro"),
                    (0 until ls.length()).map { j ->
                        val l = ls.getJSONObject(j)
                        Line(l.getString("t"), l.optBoolean("h"), l.optBoolean("n"))
                    },
                    o.optString("source"), o.optString("audio").ifBlank { null },
                )
            }.also { cache = it }
        }
    }

    fun find(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }
    fun duas(ctx: Context) = all(ctx).filter { it.kind == "dua" }
    fun ziyarat(ctx: Context) = all(ctx).filter { it.kind == "ziyarat" }
}
