package com.novaplay.player.subs

import android.content.Context
import android.net.Uri
import com.novaplay.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class OnlineSub(
    val fileId: Long,
    val name: String,
    val language: String,
    val downloads: Int,
    /** True when the subtitle was made for this exact file (hash match). */
    val exact: Boolean,
    /** Direct .gz link for keyless subtitles; null means "use the OpenSubtitles.com API". */
    val directLink: String? = null,
)

/** OpenSubtitles.com REST API: search by file hash + title, download as .srt. */
object OpenSubtitles {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    val enabled get() = BuildConfig.OPENSUBTITLES_KEY.isNotBlank()

    private fun conn(path: String, method: String = "GET") =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Api-Key", BuildConfig.OPENSUBTITLES_KEY)
            setRequestProperty("User-Agent", "NovaPlay v${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.body(): String {
        val ok = responseCode in 200..299
        val text = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            throw IllegalStateException(msg ?: "OpenSubtitles error $responseCode")
        }
        return text
    }

    /** Languages to search: phone language first, then English. */
    fun defaultLanguages(): String = listOf(Locale.getDefault().language, "en").distinct().joinToString(",")

    suspend fun search(query: String, hash: String?, languages: String = defaultLanguages()): List<OnlineSub> =
        withContext(Dispatchers.IO) {
            val params = buildList {
                if (query.isNotBlank()) add("query=" + URLEncoder.encode(query.lowercase(), "UTF-8"))
                if (hash != null) add("moviehash=$hash")
                add("languages=$languages")
            }.sorted().joinToString("&") // API asks for alphabetical params (better caching)
            val json = JSONObject(conn("/subtitles?$params").body())
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            List(data.length()) { i ->
                val a = data.getJSONObject(i).getJSONObject("attributes")
                val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@List null
                OnlineSub(
                    fileId = file.getLong("file_id"),
                    name = a.optString("release").ifBlank { file.optString("file_name", "Subtitle") },
                    language = a.optString("language"),
                    downloads = a.optInt("download_count"),
                    exact = a.optBoolean("moviehash_match"),
                )
            }.filterNotNull().sortedWith(compareByDescending<OnlineSub> { it.exact }.thenByDescending { it.downloads })
        }

    /** Downloads [sub] into [dir] and returns the file. */
    suspend fun download(sub: OnlineSub, dir: File): File = withContext(Dispatchers.IO) {
        if (sub.directLink != null) return@withContext FreeSubtitles.download(sub, dir)
        val c = conn("/download", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(JSONObject().put("file_id", sub.fileId).put("sub_format", "srt").toString().toByteArray()) }
        }
        val json = JSONObject(c.body())
        val link = json.getString("link")
        val name = json.optString("file_name").ifBlank { "${sub.fileId}.srt" }.replace(Regex("[^\\w.\\- ]"), "_")
        dir.mkdirs()
        val out = File(dir, "os_${sub.fileId}_$name")
        (URL(link).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000; readTimeout = 20_000
            inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out
    }

    /** OpenSubtitles hash: file size + sum of the first and last 64 KB as little-endian longs. */
    fun hash(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val chunk = 65536L
            if (size < chunk * 2) return null
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                fun sum(pos: Long): Long {
                    val buf = ByteBuffer.allocate(chunk.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    ch.position(pos)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    buf.flip()
                    var s = 0L
                    val lb = buf.asLongBuffer()
                    while (lb.hasRemaining()) s += lb.get()
                    return s
                }
                "%016x".format(size + sum(0) + sum(size - chunk))
            }
        }
    }.getOrNull()

    /** "Movie.Name.2019.1080p.x264-GRP.mkv" -> "Movie Name 2019". */
    fun cleanTitle(fileName: String): String {
        var t = fileName.substringBeforeLast('.')
        t = t.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("[._]+"), " ")
        val junk = Regex("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr|x264|x265|h264|h265|hevc|web[- ]?dl|webrip|bluray|brrip|dvdrip|hdtv|aac|ac3|dts|yify|rarbg)\\b.*$")
        t = t.replace(junk, "")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Keyless source: the classic rest.opensubtitles.org search (no account or API key).
 * Same subtitle library, served as gzip files.
 */
object FreeSubtitles {
    private const val BASE = "https://rest.opensubtitles.org/search"
    private const val UA = "TemporaryUserAgent"

    // OpenSubtitles uses ISO 639-2/B codes; Java gives 639-2/T for a few.
    private val bCodes = mapOf(
        "fra" to "fre", "deu" to "ger", "zho" to "chi", "ces" to "cze", "nld" to "dut", "ron" to "rum",
        "fas" to "per", "sqi" to "alb", "ell" to "gre", "hye" to "arm", "mya" to "bur", "kat" to "geo",
        "isl" to "ice", "mkd" to "mac", "msa" to "may", "slk" to "slo", "eus" to "baq", "cym" to "wel",
    )

    fun languages(): String = listOf(
        runCatching { Locale.getDefault().isO3Language }.getOrDefault("eng").let { bCodes[it] ?: it }, "eng",
    ).filter { it.length == 3 }.distinct().joinToString(",")

    private fun get(path: String): org.json.JSONArray {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("X-User-Agent", UA)
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return runCatching { org.json.JSONArray(text) }.getOrDefault(org.json.JSONArray())
    }

    suspend fun search(query: String, hash: String?, size: Long?): List<OnlineSub> = withContext(Dispatchers.IO) {
        val lang = "sublanguageid-" + languages()
        val results = mutableListOf<OnlineSub>()
        var lastError: Throwable? = null
        if (hash != null && size != null && size > 0) {
            runCatching { results += parse(get("/moviebytesize-$size/moviehash-$hash/$lang")) }.onFailure { lastError = it }
        }
        if (query.isNotBlank()) {
            val q = URLEncoder.encode(query.lowercase(), "UTF-8").replace("+", "%20")
            runCatching { results += parse(get("/query-$q/$lang")) }.onFailure { lastError = it }
        }
        if (results.isEmpty() && lastError != null) throw IllegalStateException("Subtitle search failed — check your connection")
        results.distinctBy { it.fileId }
            .sortedWith(compareByDescending<OnlineSub> { it.exact }.thenByDescending { it.downloads })
    }

    private fun parse(arr: org.json.JSONArray): List<OnlineSub> = List(arr.length()) { i ->
        val o = arr.optJSONObject(i) ?: return@List null
        val link = o.optString("SubDownloadLink").takeIf { it.startsWith("http") } ?: return@List null
        if (o.optString("SubFormat", "srt").lowercase() !in setOf("srt", "ass", "ssa", "vtt", "sub")) return@List null
        OnlineSub(
            fileId = o.optString("IDSubtitleFile").toLongOrNull() ?: return@List null,
            name = o.optString("MovieReleaseName").ifBlank { o.optString("SubFileName", "Subtitle") }.trim(),
            language = o.optString("ISO639").ifBlank { o.optString("SubLanguageID") },
            downloads = o.optString("SubDownloadsCnt").toIntOrNull() ?: 0,
            exact = o.optString("MatchedBy") == "moviehash",
            directLink = link,
        )
    }.filterNotNull()

    suspend fun download(sub: OnlineSub, dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val out = File(dir, "os_${sub.fileId}.srt")
        // The search API hands out plain http links, which Android blocks; the same host serves https.
        val link = sub.directLink.orEmpty().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        val c = (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
        }
        c.inputStream.use { raw ->
            java.io.BufferedInputStream(raw).use { input ->
                input.mark(2)
                val gz = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                val src = if (gz) java.util.zip.GZIPInputStream(input) else input
                out.outputStream().use { src.copyTo(it) }
            }
        }
        if (out.length() == 0L) { out.delete(); error("Empty subtitle file") }
        out
    }
}
