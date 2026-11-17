package com.alzahra.taqweem

import android.content.Context
import org.json.JSONObject

/**
 * Reciter recordings for the duas and ziyarat (streamed from the audio site), each with
 * phrase timings so the reader can highlight the phrase being recited.
 * Data: assets/recitations.json  { prayerId: [ {reciter, name, url, sync:[[lineIndex, startSec, endSec], ...]} ] }
 */
object Recitations {
    data class Seg(val line: Int, val start: Double, val end: Double)
    data class Rec(val reciter: String, val name: String, val url: String, val sync: List<Seg>)

    @Volatile private var cache: Map<String, List<Rec>>? = null

    private fun all(ctx: Context): Map<String, List<Rec>> = cache ?: synchronized(this) {
        cache ?: run {
            val txt = runCatching { ctx.assets.open("recitations.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
            val out = HashMap<String, List<Rec>>()
            if (txt != null) {
                val o = JSONObject(txt)
                o.keys().forEach { pid ->
                    val arr = o.getJSONArray(pid)
                    out[pid] = (0 until arr.length()).map { k ->
                        val r = arr.getJSONObject(k)
                        val s = r.getJSONArray("sync")
                        Rec(r.getString("reciter"), r.getString("name"), r.getString("url"),
                            (0 until s.length()).map { j -> s.getJSONArray(j).let { Seg(it.getInt(0), it.getDouble(1), it.getDouble(2)) } })
                    }
                }
            }
            out.also { cache = it }
        }
    }

    fun forPrayer(ctx: Context, id: String): List<Rec> = all(ctx)[id].orEmpty()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    fun preferred(ctx: Context, id: String): Rec? {
        val list = forPrayer(ctx, id)
        val want = sp(ctx).getString("reciter_$id", null) ?: sp(ctx).getString("reciter_last", null)
        return list.firstOrNull { it.reciter == want } ?: list.firstOrNull()
    }
    fun remember(ctx: Context, id: String, reciter: String) =
        sp(ctx).edit().putString("reciter_$id", reciter).putString("reciter_last", reciter).apply()

    /** Index into sync of the phrase playing at [sec], or -1. */
    fun current(rec: Rec, sec: Double): Int {
        var lo = 0; var hi = rec.sync.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (rec.sync[mid].start <= sec + 0.05) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        // Keep the phrase highlighted through the short gap before the next one.
        return ans
    }
}
