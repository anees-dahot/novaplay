package com.novaplay.player.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a file operation: done, or Android needs the user to confirm first. */
sealed interface ActionResult {
    data object Done : ActionResult
    data class NeedsConfirm(val sender: IntentSender) : ActionResult
    data class Failed(val message: String) : ActionResult
}

data class VideoInfo(val rows: List<Pair<String, String>>)

object MediaActions {

    /** Android 11+ shows its own "Allow NovaPlay to delete?" dialog; run [delete] again is not needed after OK. */
    fun delete(context: Context, uris: List<Uri>): ActionResult = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            ActionResult.NeedsConfirm(MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender)
        } else {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            ActionResult.Done
        }
    }.getOrElse { e ->
        if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
            ActionResult.NeedsConfirm(e.userAction.actionIntent.intentSender)
        } else ActionResult.Failed("Couldn't delete: ${e.message}")
    }

    /** Asks write access (Android 11+). After the user allows, call [applyRename]. */
    fun requestRename(context: Context, uri: Uri): ActionResult = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            ActionResult.NeedsConfirm(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender)
        } else ActionResult.Done
    }.getOrElse { ActionResult.Failed(it.message ?: "Rename failed") }

    fun applyRename(context: Context, uri: Uri, newName: String): ActionResult = runCatching {
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, newName) }
        val n = context.contentResolver.update(uri, values, null, null)
        if (n > 0) ActionResult.Done else ActionResult.Failed("Couldn't rename this file")
    }.getOrElse { e ->
        if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
            ActionResult.NeedsConfirm(e.userAction.actionIntent.intentSender)
        } else ActionResult.Failed("Couldn't rename: ${e.message}")
    }

    /** Android 11+: one "Allow NovaPlay to modify?" dialog covering every file to move. */
    fun requestWrite(context: Context, uris: List<Uri>): ActionResult = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            ActionResult.NeedsConfirm(MediaStore.createWriteRequest(context.contentResolver, uris).intentSender)
        } else ActionResult.Done
    }.getOrElse { ActionResult.Failed(it.message ?: "Not allowed") }

    /** Normalise "Movies/Trips" → "Movies/Trips/". */
    fun cleanPath(path: String) = path.trim().trim('/').replace(Regex("/+"), "/").let { if (it.isEmpty()) it else "$it/" }

    private fun mime(name: String) = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "video/*"

    /** MediaStore only accepts videos under these roots; anything else goes to Downloads. */
    private fun collectionFor(path: String): Uri = when {
        Build.VERSION.SDK_INT >= 29 && path.startsWith("Download", ignoreCase = true) ->
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Build.VERSION.SDK_INT >= 29 -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    /** Copies each video into [dest] (relative path). [onProgress] gets 0..1 over all bytes. */
    suspend fun copy(
        context: Context, videos: List<Video>, dest: String, onProgress: (Float) -> Unit = {},
    ): ActionResult = withContext(Dispatchers.IO) {
        val path = cleanPath(dest)
        val total = videos.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        var failed = 0
        for (v in videos) {
            val ok = runCatching { copyOne(context, v, path) { n -> done += n; onProgress(done.toFloat() / total) } }.isSuccess
            if (!ok) failed++
        }
        when (failed) {
            0 -> ActionResult.Done
            videos.size -> ActionResult.Failed("Couldn't copy to $path")
            else -> ActionResult.Failed("$failed of ${videos.size} couldn't be copied")
        }
    }

    private fun copyOne(context: Context, v: Video, path: String, onBytes: (Long) -> Unit) {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, v.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(v.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val out = cr.insert(collectionFor(path), values) ?: error("insert failed")
            try {
                cr.openInputStream(v.uri)!!.use { i -> cr.openOutputStream(out)!!.use { o -> pump(i, o, onBytes) } }
                cr.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { cr.delete(out, null, null) }
                throw e
            }
        } else {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), path).apply { mkdirs() }
            var target = java.io.File(dir, v.name)
            var n = 1
            while (target.exists()) target = java.io.File(dir, "${v.name.substringBeforeLast('.')} (${n++}).${v.name.substringAfterLast('.')}")
            try {
                cr.openInputStream(v.uri)!!.use { i -> target.outputStream().use { o -> pump(i, o, onBytes) } }
            } catch (e: Throwable) { target.delete(); throw e }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        }
    }

    private fun pump(i: java.io.InputStream, o: java.io.OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val r = i.read(buf)
            if (r < 0) break
            o.write(buf, 0, r)
            onBytes(r.toLong())
        }
    }

    /**
     * Moves videos into [dest]. Same-volume moves are instant (MediaStore just updates the path);
     * if that is refused, falls back to copy + delete. Call after [requestWrite] was allowed.
     * Returns the uris that changed identity (copied then deleted) so callers can forget them.
     */
    suspend fun move(
        context: Context, videos: List<Video>, dest: String, onProgress: (Float) -> Unit = {},
    ): Pair<ActionResult, List<Uri>> = withContext(Dispatchers.IO) {
        val path = cleanPath(dest)
        val cr = context.contentResolver
        val replaced = mutableListOf<Uri>()
        var failed = 0
        val total = videos.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        for (v in videos) {
            if (v.path.equals(path, ignoreCase = true)) { done += v.size; continue }
            val fast = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    cr.update(v.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }, null, null) > 0
                } else legacyMove(context, v, path)
            }
            if (fast.exceptionOrNull() is RecoverableSecurityException && Build.VERSION.SDK_INT == 29) {
                return@withContext ActionResult.NeedsConfirm(
                    (fast.exceptionOrNull() as RecoverableSecurityException).userAction.actionIntent.intentSender,
                ) to replaced
            }
            if (fast.getOrDefault(false)) {
                done += v.size; onProgress(done.toFloat() / total); continue
            }
            val ok = runCatching {
                copyOne(context, v, path) { n -> done += n; onProgress(done.toFloat() / total) }
                cr.delete(v.uri, null, null)
            }.isSuccess
            if (ok) replaced += v.uri else failed++
        }
        val result = when (failed) {
            0 -> ActionResult.Done
            videos.size -> ActionResult.Failed("Couldn't move to $path")
            else -> ActionResult.Failed("$failed of ${videos.size} couldn't be moved")
        }
        result to replaced
    }

    @Suppress("DEPRECATION")
    private fun legacyMove(context: Context, v: Video, path: String): Boolean {
        val cr = context.contentResolver
        val old = cr.query(v.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), path).apply { mkdirs() }
        val target = java.io.File(dir, v.name)
        if (target.exists() || !java.io.File(old).renameTo(target)) return false
        cr.update(v.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATA, target.path) }, null, null)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(old, target.path), null, null)
        return true
    }

    fun share(context: Context, uris: List<Uri>) {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "video/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun info(context: Context, video: Video): VideoInfo = withContext(Dispatchers.IO) {
        val rows = mutableListOf(
            "Name" to video.name,
            "Folder" to video.folder,
            "Location" to (video.path.ifEmpty { "—" }),
            "Size" to formatSize(video.size),
            "Duration" to formatTime(video.durationMs),
        )
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, video.uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) rows += "Resolution" to "$w × $h"
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                rows += "Bitrate" to "%.1f Mbps".format(it / 1_000_000.0)
            }
            r.release()
        }
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(context, video.uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        rows += "Video codec" to codecName(mime)
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            rows += "Frame rate" to "${runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }} fps"
                        }
                    }
                    mime.startsWith("audio/") -> {
                        val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) " · ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch" else ""
                        val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) " · ${f.getString(MediaFormat.KEY_LANGUAGE)}" else ""
                        rows += "Audio" to codecName(mime) + ch + lang
                    }
                    mime.contains("text") || mime.contains("subrip") -> rows += "Subtitles" to codecName(mime)
                }
            }
            ex.release()
        }
        VideoInfo(rows)
    }

    private fun codecName(mime: String) = when (mime.substringAfter('/').lowercase()) {
        "avc" -> "H.264 (AVC)"
        "hevc" -> "H.265 (HEVC)"
        "x-vnd.on2.vp9" -> "VP9"
        "av01" -> "AV1"
        "mp4a-latm" -> "AAC"
        "ac3" -> "Dolby AC-3"
        "eac3" -> "Dolby E-AC-3"
        "vnd.dts" -> "DTS"
        "opus" -> "Opus"
        "mpeg" -> "MP3"
        "x-subrip" -> "SRT"
        else -> mime.substringAfter('/').uppercase()
    }
}
