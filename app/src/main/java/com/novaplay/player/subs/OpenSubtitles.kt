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
