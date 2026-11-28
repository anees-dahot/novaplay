package com.novaplay.player.ui.player.engine

import java.io.File
import java.nio.charset.Charset

/** One subtitle line: shown from [start] to [end] (ms). */
data class SubCue(val start: Long, val end: Long, val text: String)

/** Reads and writes SRT / WebVTT / ASS-SSA files as plain cues. */
object SubFile {
    private val tags = Regex("\\{[^}]*\\}")
    private val html = Regex("</?(i|b|u|font)[^>]*>", RegexOption.IGNORE_CASE)
    private val arrow = Regex("""(\d+:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+:)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")

    fun read(file: File): List<SubCue> {
        val text = decode(file.readBytes())
        val ext = file.extension.lowercase()
        val cues = if (ext == "ass" || ext == "ssa" || text.contains("[Events]", ignoreCase = true)) parseAss(text) else parseSrt(text)
        return cues.filter { it.end > it.start && it.text.isNotBlank() }.sortedBy { it.start }
    }

    /** UTF-8 when it decodes cleanly, else the common Windows codepage (old .srt files). */
    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE).removePrefix("﻿")
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE).removePrefix("﻿")
        val dec = Charsets.UTF_8.newDecoder()
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }
            .getOrElse { String(b, Charset.forName("windows-1252")) }.removePrefix("﻿")
    }

    private fun ms(h: String?, m: String, s: String, f: String): Long =
        ((h?.trimEnd(':')?.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

    /** SRT and WebVTT share the "start --> end" line followed by text lines. */
    private fun parseSrt(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = arrow.find(lines[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = ms(g[1].ifEmpty { null }, g[2], g[3], g[4])
            val end = ms(g[5].ifEmpty { null }, g[6], g[7], g[8])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (arrow.containsMatchIn(lines[i])) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i].trim())
                i++
            }
            out += SubCue(start, end, sb.toString().replace(html, "").replace(tags, "").trim())
        }
        return out
    }

    private fun parseAss(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var inEvents = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        fun stamp(t: String): Long { // 0:01:02.50
            val parts = t.trim().split('.')
            val hms = parts[0].split(':').map { it.trim().toLong() }
            val frac = parts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
            return (hms[0] * 3600 + hms[1] * 60 + hms[2]) * 1000 + frac
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> inEvents = line.equals("[Events]", ignoreCase = true)
                inEvents && line.startsWith("Format:") -> fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                inEvents && line.startsWith("Dialogue:") -> runCatching {
                    val cols = line.substringAfter(':').split(",", limit = fields.size)
                    val body = cols.getOrNull(fields.indexOf("text")) ?: return@runCatching
                    if ("\\p1" in body) return@runCatching // vector drawing, not text
                    val t = body.replace(tags, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                    if (t.isNotEmpty()) out += SubCue(stamp(cols[fields.indexOf("start")]), stamp(cols[fields.indexOf("end")]), t)
                }
            }
        }
        return out
    }

    fun writeSrt(cues: List<SubCue>, out: File) {
        fun f(t: Long): String { val x = t.coerceAtLeast(0); return "%02d:%02d:%02d,%03d".format(x / 3600000, x / 60000 % 60, x / 1000 % 60, x % 1000) }
        out.writeText(buildString {
            cues.forEachIndexed { i, c -> append(i + 1).append('\n').append(f(c.start)).append(" --> ").append(f(c.end)).append('\n').append(c.text).append("\n\n") }
        })
    }

    /** Cues with time t moved to t * scale + offset. */
    fun retime(cues: List<SubCue>, offsetMs: Long, scale: Double): List<SubCue> =
        cues.map { SubCue((it.start * scale).toLong() + offsetMs, (it.end * scale).toLong() + offsetMs, it.text) }
            .filter { it.end > 0 }
}

/**
 * With "Override file style" on, a styled ASS/SSA file is rewritten as plain SRT (same timing and text),
 * because libVLC draws styled subtitles with the file's own font, size and colour.
 */
fun plainSubtitle(src: File, override: Boolean, cacheDir: File): File {
    if (!override || src.extension.lowercase() !in setOf("ass", "ssa")) return src
    val out = File(File(cacheDir, "plain").apply { mkdirs() }, "${src.nameWithoutExtension}.srt")
    if (out.exists() && out.lastModified() >= src.lastModified()) return out
    return runCatching {
        val cues = SubFile.read(src)
        if (cues.isEmpty()) src else out.also { SubFile.writeSrt(cues, it) }
    }.getOrDefault(src)
}
