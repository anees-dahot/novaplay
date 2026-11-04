package com.adfree.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val folder: String,
)

object VideoRepository {

    suspend fun load(context: Context): List<Video> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val result = mutableListOf<Video>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += Video(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(nameCol) ?: "Video $id",
                    durationMs = c.getLong(durCol),
                    size = c.getLong(sizeCol),
                    dateAdded = c.getLong(dateCol),
                    folder = c.getString(folderCol) ?: "Internal storage",
                )
            }
        }
        result
    }

    private val thumbCache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun cachedThumb(id: Long): Bitmap? = thumbCache.get(id)

    suspend fun thumbnail(context: Context, video: Video): Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(video.id)?.let { return@withContext it }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, video.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        }.getOrNull() ?: frameFallback(context, video)
        bmp?.also { thumbCache.put(video.id, it) }
    }

    /** Some phones can't thumbnail MKV/HEVC; grab a frame directly instead. */
    private fun frameFallback(context: Context, video: Video): Bitmap? = runCatching {
        context.contentResolver.openFileDescriptor(video.uri, "r")!!.use { pfd ->
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(pfd.fileDescriptor)
                val at = (video.durationMs / 10).coerceAtLeast(1000) * 1000
                val f = r.getFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@use null
                Bitmap.createScaledBitmap(f, 320, (320f * f.height / f.width).toInt().coerceAtLeast(1), true)
            } finally { r.release() }
        }
    }.getOrNull()
}

/** Remembers where you stopped watching each video. */
class ResumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(key: String): Long = prefs.getLong(key, 0L)
    fun put(key: String, ms: Long) { prefs.edit().putLong(key, ms).commit() }
    fun clear(key: String) = prefs.edit().remove(key).apply()
}

/** Small user settings, remembered between videos. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = object {
        operator fun getValue(t: Any?, p: Any?) = prefs.getBoolean(key, def)
        operator fun setValue(t: Any?, p: Any?, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    }

    var subtitleScale: Int
        get() = prefs.getInt("sub_scale", 100)
        set(v) = prefs.edit().putInt("sub_scale", v).apply()

    var loopMode: Int
        get() = prefs.getInt("loop_mode", 0)
        set(v) = prefs.edit().putInt("loop_mode", v).apply()

    var hwDecoder by bool("hw_decoder", true)
    var backgroundPlay by bool("background_play", false)
    var autoNext by bool("auto_next", true)
    var nightMode by bool("night_mode", false)
}

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
