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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val folder: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** "4K", "1080p", "720p" or null for SD/unknown. */
    val quality: String?
        get() {
            val short = minOf(width, height).takeIf { it > 0 } ?: return null
            return when {
                short >= 2000 -> "4K"
                short >= 1400 -> "1440p"
                short >= 1000 -> "1080p"
                short >= 700 -> "720p"
                else -> null
            }
        }
}

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
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val result = mutableListOf<Video>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
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
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                )
            }
        }
        result
    }

    private val thumbCache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    /** Videos we already failed on this session: don't hammer the decoder again. */
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    /** Hardware decoders are few; too many parallel frame grabs make them all fail. */
    private val gate = kotlinx.coroutines.sync.Semaphore(3)

    fun cachedThumb(id: Long): Bitmap? = thumbCache.get(id)

    /** Called on memory pressure: halve the in-memory thumbs, or drop them all. */
    fun trimThumbs(all: Boolean) {
        if (all) thumbCache.evictAll() else thumbCache.trimToSize(thumbCache.size() / 2)
    }

    private fun diskFile(context: Context, video: Video) =
        java.io.File(context.cacheDir, "thumbs/${video.id}_${video.size}.jpg")

    suspend fun thumbnail(context: Context, video: Video): Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(video.id)?.let { return@withContext it }
        val file = diskFile(context, video)
        if (file.exists()) {
            android.graphics.BitmapFactory.decodeFile(file.path)?.let { thumbCache.put(video.id, it); return@withContext it }
        }
        if (video.id in failed) return@withContext null
        val bmp = gate.withPermit {
            thumbCache.get(video.id) ?: (systemThumb(context, video) ?: frameGrab(context, video))
        }
        if (bmp == null) { failed += video.id; return@withContext null }
        thumbCache.put(video.id, bmp)
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        bmp
    }

    private fun systemThumb(context: Context, video: Video): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(video.uri, Size(384, 216), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
            )
        }
    }.getOrNull()?.takeUnless { isBlank(it) }

    /** Some phones can't thumbnail MKV/HEVC via MediaStore; grab a frame directly instead. */
    private fun frameGrab(context: Context, video: Video): Bitmap? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, video.uri)
            val dur = video.durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Try a few points: early frames are often black intros.
            val points = listOf(dur / 10, dur / 3, 1000L, 0L).map { it.coerceAtLeast(0) * 1000 }.distinct()
            var frame: Bitmap? = null
            for (us in points) {
                val f = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
                } else r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null && !isBlank(f)) { frame = f; break }
                if (frame == null) frame = f
            }
            frame?.let {
                if (it.width <= 384) it
                else Bitmap.createScaledBitmap(it, 384, (384f * it.height / it.width).toInt().coerceAtLeast(1), true)
            }
        } finally { r.release() }
    }.getOrNull()

    /** True for an (almost) all-black frame. Samples a 6x6 grid. */
    private fun isBlank(b: Bitmap): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) return@runCatching false
        var lit = 0
        for (x in 1..6) for (y in 1..6) {
            val c = b.getPixel((b.width * x / 7).coerceIn(0, b.width - 1), (b.height * y / 7).coerceIn(0, b.height - 1))
            if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 60) lit++
        }
        lit < 3
    }.getOrDefault(false)
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
