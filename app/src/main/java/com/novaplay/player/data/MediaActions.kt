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
