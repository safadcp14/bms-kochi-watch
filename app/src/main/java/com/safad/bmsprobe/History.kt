package com.safad.bmsprobe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val at: Long,
    val code: String,
    val status: String,
    val latencyMs: Long,
    val codes: List<String>,
    val hit: Boolean,
)

object History {

    private const val MAX = 400
    private val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    private fun file(c: Context) = File(c.filesDir, "history.jsonl")

    @Synchronized
    fun append(c: Context, e: HistoryEntry) {
        runCatching {
            val json = JSONObject().apply {
                put("at", e.at)
                put("code", e.code)
                put("status", e.status)
                put("latency", e.latencyMs)
                put("codes", e.codes.joinToString(","))
                put("hit", e.hit)
            }
            file(c).appendText(json.toString() + "\n")
            val f = file(c)
            if (f.length() > 200_000) {
                val keep = f.readLines().takeLast(MAX)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Synchronized
    fun recent(c: Context, n: Int = 40): List<HistoryEntry> = runCatching {
        file(c).readLines()
            .filter { it.isNotBlank() }
            .takeLast(n)
            .mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    HistoryEntry(
                        at = o.optLong("at"),
                        code = o.optString("code"),
                        status = o.optString("status"),
                        latencyMs = o.optLong("latency"),
                        codes = o.optString("codes").split(",").filter { it.isNotBlank() },
                        hit = o.optBoolean("hit"),
                    )
                }.getOrNull()
            }
            .reversed()
    }.getOrDefault(emptyList())

    fun label(at: Long): String = fmt.format(Date(at))
}
