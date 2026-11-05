package com.adfree.player

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Build
import com.adfree.player.data.VideoRepository
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) VideoRepository.trimThumbs(level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        VideoRepository.trimThumbs(true)
    }
}

/** Writes the last crash to a file so the next launch can offer to share it. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(
                    "NovaPlay $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}

/**
 * libVLC builds its plugin + font caches the first time it runs, which made the very first
 * video take ~20s to start. Do that once in the background, right after install, by
 * "playing" a library video silently into a dummy output.
 */
object VlcWarmup {
    @Volatile private var running = false

    fun runOnce(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val version = runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
        if (running || prefs.getLong("warmed_for", -1) == version) return
        running = true
        val app = context.applicationContext
        Thread {
            runCatching {
                val vlc = LibVLC(app, arrayListOf("--vout=dummy", "--aout=dummy", "--no-audio"))
                val player = MediaPlayer(vlc)
                app.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    val media = Media(vlc, fd.fileDescriptor)
                    player.media = media
                    media.release()
                    player.play()
                    Thread.sleep(6_000)
                    player.stop()
                }
                player.release()
                vlc.release()
                prefs.edit().putLong("warmed_for", version).apply()
            }
            running = false
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
