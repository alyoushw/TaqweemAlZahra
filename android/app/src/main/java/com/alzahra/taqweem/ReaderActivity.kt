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
 * Full-screen reader for a dua or ziyarat. Opens as its own page (back button returns to the list),
 * keeps the screen on, and remembers the reader's text size, text colour and background.
 */
class ReaderActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var web: WebView
    private var player: MediaPlayer? = null
    private lateinit var item: Prayers.Item

    private fun sp() = getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    private var size: Int
        get() = sp().getInt("reader_size", 26)
        set(v) = sp().edit().putInt("reader_size", v).apply()
    private var scheme: String
        get() = sp().getString("reader_scheme", if (Themes.current(this).dark) "night" else "paper") ?: "paper"
        set(v) = sp().edit().putString("reader_scheme", v).apply()

    /** Background, Arabic text, translation/notes colour. */
    private val schemes = linkedMapOf(
        "paper" to Triple("#FBF6EA", "#1A1A1A", "#6B6B6B"),
        "white" to Triple("#FFFFFF", "#000000", "#666666"),
        "sepia" to Triple("#F1E3C6", "#3B2A14", "#7A6447"),
        "green" to Triple("#E8F3EA", "#123B1E", "#5D7A63"),
        "night" to Triple("#10161C", "#E9EDF1", "#97A4B0"),
        "black" to Triple("#000000", "#F3D77A", "#B8A06A"),
    )
    private val schemeNames = mapOf("paper" to "ورقي", "white" to "أبيض", "sepia" to "بني فاتح", "green" to "أخضر", "night" to "ليلي", "black" to "أسود وذهبي")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        item = Prayers.find(this, intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ui.theme.primary)
        }
        // Top bar: back, title
        val top = ui.row().apply { setPadding(ui.dp(8), ui.dp(10), ui.dp(12), ui.dp(10)) }
        top.addView(ui.text("→", 26f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        top.addView(ui.text(item.title, 19f, ui.theme.accent, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.weight())
        root.addView(top)

        // Toolbar: text size, colours, audio
        val tools = ui.row().apply {
            setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(10))
        }
        fun tool(label: String, onClick: () -> Unit) = ui.text(label, 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            background = ui.rounded(Color.parseColor("#33FFFFFF"), 12)
            setOnClickListener { onClick() }
        }
        tools.addView(tool("أ−") { size = (size - 2).coerceAtLeast(16); apply() }, ui.wrap(0, 0, 6, 0))
        tools.addView(tool("أ+") { size = (size + 2).coerceAtMost(48); apply() }, ui.wrap(0, 0, 6, 0))
        tools.addView(tool("🎨 الألوان") { pickScheme() }, ui.wrap(0, 0, 6, 0))
        if (item.audio != null && hasAudio(item.audio!!)) {
            val play = tool("▶ استماع") {}
            play.setOnClickListener { toggleAudio(play) }
            tools.addView(play, ui.wrap(0, 0, 6, 0))
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(tools) })

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val y = sp().getInt("reader_pos_${item.id}", 0)
                    if (y > 0) view.evaluateJavascript("window.scrollTo(0,$y)", null)
                }
            }
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
        // Remember where the reader stopped.
        web.evaluateJavascript("window.scrollY") { v -> v?.toDoubleOrNull()?.toInt()?.let { sp().edit().putInt("reader_pos_${item.id}", it).apply() } }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun load() {
        val body = StringBuilder()
        if (item.intro.isNotBlank()) body.append("<div class='intro'>${esc(item.intro).replace("\n", "<br>")}</div>")
        item.lines.forEach { l ->
            when {
                l.isHeading -> body.append("<h3>${esc(l.text)}</h3>")
                l.isNote -> body.append("<p class='note'>${esc(l.text)}</p>")
                else -> body.append("<p class='ar'>${esc(l.text)}</p>")
            }
        }
        if (item.source.isNotBlank()) body.append("<div class='src'>المصدر: ${esc(item.source)}</div>")
        val html = """<!doctype html><html dir="rtl"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--bg:#FBF6EA;--fg:#1A1A1A;--mu:#6B6B6B;--sz:26px}
body{margin:0;padding:18px 18px 80px;background:var(--bg);color:var(--fg);font-family:'Noto Naskh Arabic','Amiri',serif}
.ar{font-size:var(--sz);line-height:2.05;margin:0 0 .55em;text-align:justify}
h3{font-size:calc(var(--sz)*.8);text-align:center;margin:1.2em 0 .6em;color:var(--mu)}
.note,.intro{font-size:calc(var(--sz)*.62);line-height:1.8;color:var(--mu);margin:.4em 0 .9em}
.intro{border-bottom:1px solid var(--mu);padding-bottom:.8em;margin-bottom:1.2em}
.src{font-size:13px;color:var(--mu);text-align:center;margin-top:2em;opacity:.8}
</style></head><body>$body
<script>function setStyle(bg,fg,mu,sz){var r=document.documentElement.style;r.setProperty('--bg',bg);r.setProperty('--fg',fg);r.setProperty('--mu',mu);r.setProperty('--sz',sz+'px');}</script>
</body></html>"""
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        web.postDelayed({ apply() }, 50)
        apply()
    }

    private fun apply() {
        val (bg, fg, mu) = schemes[scheme] ?: schemes.getValue("paper")
        web.setBackgroundColor(Color.parseColor(bg))
        web.evaluateJavascript("setStyle('$bg','$fg','$mu',$size)", null)
    }

    private fun pickScheme() {
        Dialogs.sheet(this, "🎨", "ألوان القراءة", "لون الخلفية ولون الخط") { col, dialog ->
            schemes.entries.chunked(2).forEach { pair ->
                val r = ui.row()
                pair.forEach { (id, c) ->
                    val v = ui.text("بِسْمِ اللَّهِ — ${schemeNames[id]}", 16f, Color.parseColor(c.second), bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(16))
                        background = ui.rounded(Color.parseColor(c.first), 14, if (id == scheme) ui.theme.accent else Colors.STROKE, if (id == scheme) 3 else 1)
                        setOnClickListener { scheme = id; apply(); dialog.dismiss() }
                    }
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                col.addView(r)
            }
        }
    }

    private fun hasAudio(file: String) = runCatching { assets.list("audio")?.contains(file) == true }.getOrDefault(false)

    private fun toggleAudio(btn: TextView) {
        val p = player
        when {
            p != null && p.isPlaying -> { p.pause(); btn.text = "▶ استماع" }
            p != null -> { p.start(); btn.text = "⏸ إيقاف" }
            else -> runCatching {
                val afd = assets.openFd("audio/${item.audio}")
                player = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                    setOnCompletionListener { btn.text = "▶ استماع" }
                    prepare(); start()
                }
                btn.text = "⏸ إيقاف"
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
