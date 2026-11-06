package com.novaplay.player.ui.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import android.os.SystemClock
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.novaplay.player.AppTheme
import com.novaplay.player.data.LibraryStore
import com.novaplay.player.data.ResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererDiscoverer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.novaplay.player.subs.OnlineSub
import com.novaplay.player.subs.OpenSubtitles
import com.novaplay.player.subs.FreeSubtitles
import org.videolan.libvlc.RendererItem
import android.net.wifi.WifiManager
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.roundToInt
import com.novaplay.player.data.Settings as AppSettings

class PlayerActivity : ComponentActivity(), PlayerController, BackgroundPlayer {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        private const val RESUME_MIN_MS = 5_000L
        private const val ACTION_PIP_TOGGLE = "com.novaplay.PIP_TOGGLE"
    }

    private val state = PlayerState()
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private var videoLayout: VLCVideoLayout? = null
    private var pfd: ParcelFileDescriptor? = null
    private lateinit var resume: ResumeStore
    private lateinit var settings: AppSettings
    private val main = Handler(Looper.getMainLooper())
    private var wasPlaying = true
    private var released = true

    // Playlist (videos of the folder the user opened this from).
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private val videoUri get() = uris[index]
    override val videoTitle get() = titles.getOrElse(index) { "Video" }

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var brightness = -1f
    private var volumeAcc = -1f
    private var rateBeforeHold = 1f
    private var seekTarget = 0L
    private var seekGuardUntil = 0L

    private val shuffleSeen = mutableSetOf<Int>()
    private var pendingSeek: Long? = null
    private var lastStepAt = 0L
    private var lastStepTarget = -1L
    private val sleepRunnable = Runnable {
        if (!released && player.isPlaying) player.pause()
        state.sleepAt = -1
        state.flash("Sleep timer — paused")
        PlaybackService.update(this)
    }
    private var discoverers: List<RendererDiscoverer> = emptyList()
    private val rendererItems = linkedMapOf<String, RendererItem>()
    private var multicastLock: WifiManager.MulticastLock? = null

    private var retriever: MediaMetadataRetriever? = null
    private val retrieverLock = Mutex()

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadExternalSubtitle(uri)
        }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = togglePlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!readPlaylist(intent)) { finish(); return }
        resume = ResumeStore(this)
        LibraryStore.init(this)
        settings = AppSettings(this)
        state.subScale = settings.subtitleScale
        state.hwDecoder = settings.hwDecoder
        state.backgroundPlay = settings.backgroundPlay
        state.autoNext = settings.autoNext
        state.nightMode = settings.nightMode
        state.loopMode = LoopMode.entries[settings.loopMode.coerceIn(0, 2)]
        state.shuffle = settings.shuffle
        state.rate = settings.speed / 100f
        state.subColor = settings.subColor.coerceIn(0, SUB_COLORS.lastIndex)
        state.subBackground = settings.subBackground
        state.subBold = settings.subBold
        state.eqPreset = settings.eqPreset
        state.bassDb = settings.bassDb

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PlaybackService.player = this

        createPlayer(startMs = resume.get(videoUri.toString()))
        // Equalizer presets are native calls: only valid once LibVLC has loaded its library.
        state.eqPresets = runCatching {
            List(MediaPlayer.Equalizer.getPresetCount()) { MediaPlayer.Equalizer.getPresetName(it) }
        }.getOrDefault(emptyList())

        setContent {
            AppTheme { PlayerScreen(state, this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveResume()
        if (readPlaylist(intent)) switchTo(index)
    }

    /** Reads either a folder playlist (from our library) or a single video (from other apps). */
    private fun readPlaylist(i: Intent): Boolean {
        val list = IntentCompat.getParcelableArrayListExtra(i, EXTRA_URIS, Uri::class.java)
        if (!list.isNullOrEmpty()) {
            uris = list
            titles = i.getStringArrayListExtra(EXTRA_TITLES) ?: list.map { it.lastPathSegment ?: "Video" }
            index = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.lastIndex)
        } else {
            val uri = i.data ?: return false
            uris = listOf(uri)
            titles = listOf(i.getStringExtra(EXTRA_TITLE) ?: displayName(uri) ?: uri.lastPathSegment ?: "Video")
            index = 0
        }
        return true
    }

    // ---------- libVLC setup ----------

    private fun createPlayer(startMs: Long) {
        released = false
        LibraryStore.markPlayed(videoUri.toString())
        state.hasPrev = index > 0
        state.hasNext = index < uris.lastIndex || (state.shuffle && uris.size > 1)
        state.title = videoTitle
        state.nextTitle = if (index < uris.lastIndex && !state.shuffle) titles.getOrNull(index + 1) else null
        state.bookmarks = loadBookmarks()
        state.queue = titles
        state.playlistIndex = index
        state.playlistSize = uris.size
        libVLC = LibVLC(
            this,
            ArrayList(listOf(
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--rtsp-tcp",
                "--sub-text-scale=${state.subScale}",
                "--freetype-color=${SUB_COLORS[state.subColor].second}",
                "--freetype-background-opacity=${if (state.subBackground) 170 else 0}",
                "--freetype-background-color=0",
                "--freetype-outline-thickness=${if (state.subBackground) 0 else 4}",
            ) + (if (state.subBold) listOf("--freetype-bold") else emptyList())),
        )
        player = MediaPlayer(libVLC)
        player.setEventListener(::onPlayerEvent)

        val media = try {
            openMedia()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open video: ${e.message}", Toast.LENGTH_LONG).show()
            if (state.hasNext) main.post { playNext() } else finish()
            return
        }
        media.setHWDecoderEnabled(state.hwDecoder, false)
        if (startMs > RESUME_MIN_MS) media.addOption(":start-time=${startMs / 1000.0}")
        state.resumedFrom = if (startMs > RESUME_MIN_MS) startMs else -1L
        player.media = media
        media.release()
        // Re-attach a subtitle file the user picked for this video before.
        settings.subtitleFor(videoUri.toString())?.let { path ->
            if (File(path).exists()) player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(path)), true)
        }

        // On first launch the view doesn't exist yet; createVideoView attaches + plays.
        videoLayout?.let { attachAndPlay(it) }
    }

    private fun openMedia(): Media =
        if (videoUri.scheme == "content") {
            pfd?.close()
            val fd = contentResolver.openFileDescriptor(videoUri, "r") ?: error("no file descriptor")
            pfd = fd
            Media(libVLC, fd.fileDescriptor)
        } else {
            Media(libVLC, videoUri)
        }

    private fun attachAndPlay(layout: VLCVideoLayout) {
        player.attachViews(layout, null, true, false)
        applyScale(state.scale)
        player.play()
    }

    override fun createVideoView(context: Context): View =
        VLCVideoLayout(context).also {
            videoLayout = it
            if (!released) attachAndPlay(it)
        }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                state.playing = true
                if (player.rate != state.rate && !state.holdingFast) player.rate = state.rate
                applyAudio()
                pendingSeek?.let { player.time = it; pendingSeek = null }
                refreshTracks()
                loadChapters()
                onPlayStateChanged()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                state.playing = false
                onPlayStateChanged()
            }
            MediaPlayer.Event.LengthChanged -> {
                state.length = event.lengthChanged
                loadChapters()
            }
            MediaPlayer.Event.TimeChanged -> {
                val t = event.timeChanged
                // After a seek libVLC still reports a few old times; ignore them so the bar doesn't jump back.
                val settled = SystemClock.uptimeMillis() > seekGuardUntil || abs(t - seekTarget) < 1_500
                if (!state.seeking && settled) state.time = t
                if (t > 0) state.started = true
                // A-B repeat: jump back to A once we pass B.
                if (state.abStart >= 0 && state.abEnd > state.abStart && t >= state.abEnd) {
                    player.time = state.abStart
                }
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            // Don't tear the player down inside its own callback; post it.
            MediaPlayer.Event.EndReached -> main.post { onEnded() }
            MediaPlayer.Event.EncounteredError -> main.post {
                Toast.makeText(this, "Playback error", Toast.LENGTH_LONG).show()
                if (state.hwDecoder) setHwDecoder(false) // often fixes black screen / codec issues
                else finish()
            }
        }
    }

    private val bookmarkPrefs by lazy { getSharedPreferences("bookmarks", MODE_PRIVATE) }

    private fun loadBookmarks(): List<Long> =
        bookmarkPrefs.getString(videoUri.toString(), null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.sorted()

    private fun saveBookmarks(list: List<Long>) {
        state.bookmarks = list.sorted()
        bookmarkPrefs.edit().apply {
            if (list.isEmpty()) remove(videoUri.toString()) else putString(videoUri.toString(), state.bookmarks.joinToString(","))
        }.apply()
    }

    override fun addBookmark() {
        val t = state.time
        // Ignore a second tap within 2s of an existing bookmark.
        if (state.bookmarks.none { kotlin.math.abs(it - t) < 2000 }) saveBookmarks(state.bookmarks + t)
        state.flash("Bookmarked ${com.novaplay.player.data.formatTime(t)}")
    }

    override fun playAt(index: Int) {
        if (index in uris.indices && index != this.index) switchTo(index)
    }

    override fun removeBookmark(ms: Long) = saveBookmarks(state.bookmarks - ms)

    private fun onEnded() {
        if (released) return
        resume.clear(videoUri.toString())
        LibraryStore.markWatched(videoUri.toString())
        if (state.sleepAtEnd) { setSleep(0); finish(); return }
        when {
            state.loopMode == LoopMode.ONE -> switchTo(index, fromStart = true)
            state.shuffle && uris.size > 1 && (state.autoNext || state.loopMode == LoopMode.ALL) -> switchTo(randomIndex())
            state.hasNext && state.autoNext && !state.upNextCancelled -> switchTo(index + 1)
            state.loopMode == LoopMode.ALL -> switchTo(if (state.hasNext) index + 1 else 0, fromStart = true)
            else -> finish()
        }
    }

    /** Random other video, preferring ones not played yet this session. */
    private fun randomIndex(): Int {
        shuffleSeen += index
        val fresh = uris.indices.filter { it != index && it !in shuffleSeen }
        if (fresh.isEmpty()) shuffleSeen.clear()
        return (fresh.ifEmpty { uris.indices.filter { it != index } }).random()
    }

    private fun onPlayStateChanged() {
        setPipParams()
        PlaybackService.update(this)
    }

    private fun refreshTracks() {
        state.audioTracks = player.audioTracks?.map { Track(it.id, it.name) } ?: emptyList()
        state.spuTracks = player.spuTracks?.map { Track(it.id, it.name) } ?: emptyList()
        state.audioTrack = player.audioTrack
        state.spuTrack = player.spuTrack
    }

    private fun loadChapters() {
        state.chapters = player.getChapters(-1)
            ?.filter { it.timeOffset >= 0 }
            ?.mapIndexed { i, c -> Chapter(c.timeOffset, c.name?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}") }
            ?: emptyList()
    }

    /** Switches to another video in the playlist. */
    private fun switchTo(newIndex: Int, fromStart: Boolean = false) {
        if (newIndex !in uris.indices) return
        saveResume()
        releasePlayer()
        index = newIndex
        state.resetForNewVideo()
        closeRetriever()
        createPlayer(if (fromStart) 0 else resume.get(videoUri.toString()))
        PlaybackService.update(this)
    }

    /** Restarts the same video at the same spot (needed for libVLC start-up options). */
    private fun restartSameVideo() {
        val pos = player.time
        val spu = state.spuTrack
        val audioId = state.audioTrack
        val wasPaused = !player.isPlaying
        releasePlayer()
        createPlayer(startMs = pos)
        if (released) return
        // Short positions skip the start-time option (resume threshold), so seek once playing.
        if (pos in 1..RESUME_MIN_MS) pendingSeek = pos
        player.setEventListener { e ->
            onPlayerEvent(e)
            if (e.type == MediaPlayer.Event.Playing) {
                if (wasPaused) player.pause()
                if (audioId >= 0) player.audioTrack = audioId
                player.spuTrack = spu
                player.spuDelay = state.spuDelayMs * 1000
                player.setEventListener(::onPlayerEvent)
            }
        }
    }

    // ---------- PlayerController ----------

    override fun togglePlay() {
        if (released) return
        lastStepTarget = -1
        if (player.isPlaying) player.pause() else player.play()
        state.bump()
    }

    override fun seekTo(ms: Long) {
        if (released) return
        val target = if (state.length > 0) ms.coerceIn(0, state.length) else ms.coerceAtLeast(0)
        player.time = target
        state.time = target
        seekTarget = target
        seekGuardUntil = SystemClock.uptimeMillis() + 800
    }

    override fun seekBy(deltaMs: Long) = seekTo(player.time + deltaMs)

    override fun setRate(rate: Float) {
        state.rate = rate
        settings.speed = (rate * 100).toInt()
        player.rate = rate
    }

    override fun startHoldFast() {
        if (state.holdingFast || !player.isPlaying) return
        rateBeforeHold = state.rate
        state.holdingFast = true
        player.rate = 2f
    }

    override fun stopHoldFast() {
        if (!state.holdingFast) return
        state.holdingFast = false
        player.rate = rateBeforeHold
    }

    override fun setScale(scale: VideoScale) {
        state.scale = scale
        applyScale(scale)
        state.flash(scale.label)
    }

    private fun applyScale(scale: VideoScale) {
        player.aspectRatio = scale.ratio
        player.videoScale = scale.vlc
    }

    override fun selectAudio(id: Int) {
        player.audioTrack = id
        state.audioTrack = id
    }

    override fun selectSpu(id: Int) {
        player.spuTrack = id
        state.spuTrack = id
    }

    override fun pickSubtitleFile() = subtitlePicker.launch(arrayOf("*/*"))

    private fun loadExternalSubtitle(uri: Uri) {
        // libVLC reads subtitle files by path, so copy the picked file into the app cache first.
        val name = displayName(uri) ?: "subtitle.srt"
        val dir = File(filesDir, "subs").apply { mkdirs() } // kept so the subtitle reloads next time
        val file = File(dir, "${videoUri.toString().hashCode()}_$name")
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
            settings.setSubtitleFor(videoUri.toString(), file.absolutePath)
        }.onSuccess {
            state.flash("Subtitle loaded")
        }.onFailure {
            Toast.makeText(this, "Couldn't load subtitle", Toast.LENGTH_SHORT).show()
        }
    }

    override fun shiftSpuDelay(deltaMs: Long) {
        state.spuDelayMs += deltaMs
        player.spuDelay = state.spuDelayMs * 1000 // libVLC wants microseconds
    }

    override fun setSubScale(percent: Int) {
        if (percent == state.subScale) return
        state.subScale = percent
        settings.subtitleScale = percent
        restartSameVideo()
    }

    override fun playPrev() {
        // Like most players: first press restarts the video if we're past 5 seconds.
        if (state.time > 5_000 || index == 0) seekTo(0) else switchTo(index - 1)
    }

    override fun playNext() {
        if (state.shuffle && uris.size > 1) switchTo(randomIndex())
        else if (index < uris.lastIndex) switchTo(index + 1)
        else if (state.loopMode == LoopMode.ALL) switchTo(0)
    }

    override fun cycleAbRepeat() {
        when {
            state.abStart < 0 -> { state.abStart = player.time; state.flash("A set — tap again for B") }
            state.abEnd < 0 -> {
                if (player.time <= state.abStart + 500) { state.flash("B must be after A"); return }
                state.abEnd = player.time
                player.time = state.abStart
                state.flash("Repeating A–B")
            }
            else -> { state.abStart = -1; state.abEnd = -1; state.flash("A–B repeat off") }
        }
    }

    override fun setLoopMode(mode: LoopMode) {
        state.loopMode = mode
        settings.loopMode = mode.ordinal
        state.flash(mode.label)
    }

    override fun setHwDecoder(enabled: Boolean) {
        state.hwDecoder = enabled
        settings.hwDecoder = enabled
        state.flash(if (enabled) "Hardware decoder" else "Software decoder")
        restartSameVideo()
    }

    override fun setBackgroundPlay(enabled: Boolean) {
        state.backgroundPlay = enabled
        settings.backgroundPlay = enabled
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun setAutoNext(enabled: Boolean) {
        state.autoNext = enabled
        settings.autoNext = enabled
    }

    override fun setNightMode(enabled: Boolean) {
        state.nightMode = enabled
        settings.nightMode = enabled
    }

    override fun takeScreenshot() {
        val surface = videoLayout?.findSurfaceView() ?: return
        if (surface.width == 0 || surface.height == 0) return
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveScreenshot(bmp)
            else Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
        }, main)
    }

    private fun View.findSurfaceView(): SurfaceView? {
        if (this is SurfaceView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findSurfaceView()?.let { return it }
        return null
    }

    private fun saveScreenshot(bmp: Bitmap) {
        val name = "NovaPlay_${System.currentTimeMillis()}.png"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NovaPlay")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onSuccess { state.flash("Screenshot saved to Pictures/NovaPlay") }
            .onFailure { Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show() }
    }

    override suspend fun previewFrame(ms: Long): Bitmap? = withContext(Dispatchers.IO) {
        retrieverLock.withLock {
            runCatching {
                val r = retriever ?: MediaMetadataRetriever().also {
                    // File descriptor works on more phones than a content Uri.
                    runCatching {
                        contentResolver.openFileDescriptor(videoUri, "r")!!.use { fd -> it.setDataSource(fd.fileDescriptor) }
                    }.getOrElse { _ -> it.setDataSource(this@PlayerActivity, videoUri) }
                    retriever = it
                }
                val us = ms * 1000
                if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }

    private fun closeRetriever() {
        val r = retriever ?: return
        retriever = null
        Thread { runCatching { r.release() } }.start()
    }

    override fun adjustBrightness(delta: Float): Int {
        if (brightness < 0) {
            val current = window.attributes.screenBrightness
            brightness = if (current >= 0) current
            else Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }
        brightness = (brightness + delta).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        return (brightness * 100).roundToInt()
    }

    override fun adjustVolume(delta: Float): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (volumeAcc < 0) volumeAcc = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        volumeAcc = (volumeAcc + delta * max * 1.5f).coerceIn(0f, max.toFloat())
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeAcc.roundToInt(), 0)
        return (volumeAcc / max * 100).roundToInt()
    }

    override fun endGesture() {
        volumeAcc = -1f
    }

    override fun toggleRotation() {
        requestedOrientation =
            if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
    }

    override fun back() = finish()

    override fun setShuffle(enabled: Boolean) {
        state.shuffle = enabled
        settings.shuffle = enabled
        state.nextTitle = if (index < uris.lastIndex && !enabled) titles.getOrNull(index + 1) else null
        shuffleSeen.clear()
        state.hasNext = index < uris.lastIndex || (enabled && uris.size > 1)
        state.flash(if (enabled) "Shuffle on" else "Shuffle off")
    }

    override fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        state.sleepAtEnd = minutes == -1
        state.sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else -1
        if (minutes > 0) main.postDelayed(sleepRunnable, minutes * 60_000L)
        state.flash(
            when {
                minutes > 0 -> "Sleep in $minutes min"
                minutes == -1 -> "Stop after this video"
                else -> "Sleep timer off"
            }
        )
    }

    override fun frameStep(forward: Boolean) {
        if (released) return
        if (player.isPlaying) player.pause()
        // Forward: libVLC's real next-frame is exact. Seeking is the fallback (and the only way back).
        if (forward && FrameStepper.next(player)) { lastStepTarget = -1; state.bump(); return }
        val track = runCatching { player.currentVideoTrack }.getOrNull()
        val fps = if (track != null && track.frameRateDen > 0 && track.frameRateNum > 0)
            track.frameRateNum.toFloat() / track.frameRateDen else 30f
        val frameMs = 1000.0 / fps
        // libVLC's reported time lags while paused, so keep stepping from our own last target.
        val now = SystemClock.uptimeMillis()
        val base = if (now - lastStepAt < 5_000 && lastStepTarget >= 0 && abs(player.time - lastStepTarget) < 1_000) lastStepTarget else player.time
        val frame = Math.floor(base / frameMs).toLong() + if (forward) 1 else -1
        // Aim at the middle of the frame so rounding never lands on its neighbour.
        val target = ((frame.coerceAtLeast(0) + 0.5) * frameMs).toLong()
        lastStepAt = now
        lastStepTarget = target
        player.setTime(target, false) // precise (non-fast) seek so we land on that exact frame
        state.time = target
        seekTarget = target
        seekGuardUntil = SystemClock.uptimeMillis() + 800
        state.bump()
    }

    // ---------- Audio ----------

    private fun applyAudio() {
        if (released) return
        player.volume = state.volumeBoost
        player.audioDelay = state.audioDelayMs * 1000
        if (state.eqPreset < 0 && state.bassDb == 0) {
            player.setEqualizer(null)
            return
        }
        val eq = if (state.eqPreset >= 0) MediaPlayer.Equalizer.createFromPreset(state.eqPreset)
        else MediaPlayer.Equalizer.create()
        // Bass boost lifts the lowest bands on top of the chosen preset.
        val b = state.bassDb.toFloat()
        listOf(0 to 1f, 1 to 0.75f, 2 to 0.35f).forEach { (band, k) ->
            eq.setAmp(band, (eq.getAmp(band) + b * k).coerceIn(-20f, 20f))
        }
        if (b > 0) eq.preAmp = (eq.preAmp - b * 0.4f).coerceIn(-20f, 20f) // headroom so boosted bass doesn't clip
        player.setEqualizer(eq)
    }

    override fun shiftAudioDelay(deltaMs: Long) {
        state.audioDelayMs += deltaMs
        if (!released) player.audioDelay = state.audioDelayMs * 1000
    }

    override fun setVolumeBoost(percent: Int) {
        state.volumeBoost = percent.coerceIn(100, 200)
        if (!released) player.volume = state.volumeBoost
        state.flash("Volume ${state.volumeBoost}%")
    }

    override fun setEqPreset(index: Int) {
        state.eqPreset = index
        settings.eqPreset = index
        applyAudio()
    }

    override fun setBass(db: Int) {
        state.bassDb = db
        settings.bassDb = db
        applyAudio()
    }

    override fun setSubStyle(color: Int, background: Boolean, bold: Boolean) {
        if (color == state.subColor && background == state.subBackground && bold == state.subBold) return
        state.subColor = color; state.subBackground = background; state.subBold = bold
        settings.subColor = color; settings.subBackground = background; settings.subBold = bold
        restartSameVideo() // freetype options only apply to a new libVLC instance
    }

    // ---------- Online subtitles ----------

    override fun subtitleQuery() = OpenSubtitles.cleanTitle(videoTitle)

    override fun searchOnlineSubs(query: String) {
        if (state.onlineBusy) return
        state.onlineBusy = true
        state.onlineError = null
        val uri = videoUri
        lifecycleScope.launch {
            runCatching {
                // Exact matches come from the file hash; the title search catches the rest.
                val hash = if (uri.scheme == "content" || uri.scheme == "file")
                    withContext(Dispatchers.IO) { OpenSubtitles.hash(this@PlayerActivity, uri) } else null
                val size = if (hash != null) withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                } else null
                // Keyless search always; the official API joins in when a key is configured.
                val free = runCatching { FreeSubtitles.search(query, hash, size) }
                val official = if (OpenSubtitles.enabled) runCatching { OpenSubtitles.search(query, hash) } else null
                if (free.isFailure && (official == null || official.isFailure)) throw (free.exceptionOrNull()!!)
                (official?.getOrNull().orEmpty() + free.getOrNull().orEmpty())
                    .distinctBy { it.name.lowercase() + it.language }
                    .sortedWith(compareByDescending<OnlineSub> { it.exact }.thenByDescending { it.downloads })
            }.onSuccess {
                state.onlineSubs = it
            }.onFailure {
                state.onlineSubs = emptyList()
                state.onlineError = it.message ?: "Search failed — check your connection"
            }
            state.onlineSearched = true
            state.onlineBusy = false
        }
    }

    override fun downloadOnlineSub(sub: OnlineSub) {
        if (state.onlineBusy) return
        state.onlineBusy = true
        state.onlineError = null
        val key = videoUri.toString()
        lifecycleScope.launch {
            runCatching { OpenSubtitles.download(sub, File(filesDir, "subs")) }
                .onSuccess { file ->
                    if (!released) {
                        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
                        settings.setSubtitleFor(key, file.absolutePath)
                        state.flash("Subtitle loaded")
                        state.onlineLoaded++
                    }
                }
                .onFailure { state.onlineError = it.message ?: "Download failed" }
            state.onlineBusy = false
        }
    }

    // ---------- Casting (Chromecast etc. via libVLC renderers) ----------

    override fun startCastScan() {
        if (released || discoverers.isNotEmpty()) return
        multicastLock = runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("novaplay-cast").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        discoverers = RendererDiscoverer.list(libVLC).mapNotNull { d ->
            runCatching {
                RendererDiscoverer(libVLC, d.name).also { rd ->
                    rd.setEventListener { e ->
                        when (e.type) {
                            RendererDiscoverer.Event.ItemAdded -> e.item?.let { rendererItems[it.displayName] = it }
                            RendererDiscoverer.Event.ItemDeleted -> e.item?.let { rendererItems.remove(it.displayName) }
                        }
                        state.renderers = rendererItems.keys.toList()
                    }
                    rd.start()
                }
            }.getOrNull()
        }
    }

    override fun stopCastScan() {
        discoverers.forEach { runCatching { it.stop(); it.release() } }
        discoverers = emptyList()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        if (state.castingTo == null) { rendererItems.clear(); state.renderers = emptyList() }
    }

    override fun castTo(name: String?) {
        if (released) return
        val item = name?.let { rendererItems[it] }
        if (name != null && item == null) return
        val pos = player.time
        player.setRenderer(item)
        state.castingTo = name
        // A renderer only takes effect when the media (re)starts, so reopen it at the same spot.
        val media = runCatching { openMedia() }.getOrNull() ?: return
        media.setHWDecoderEnabled(state.hwDecoder, false)
        player.media = media
        media.release()
        pendingSeek = pos
        player.play()
        state.flash(if (name != null) "Casting to $name" else "Playing on this phone")
    }

    // ---------- BackgroundPlayer (notification / lock screen) ----------

    override val isPlaying get() = !released && player.isPlaying
    override val positionMs get() = state.time
    override val lengthMs get() = state.length

    // ---------- Picture-in-picture ----------

    private fun pipParams(): PictureInPictureParams {
        val icon = Icon.createWithResource(
            this,
            if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val action = RemoteAction(
            icon, "Play/Pause", "Play/Pause",
            PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(videoAspect())
            .setActions(listOf(action))
            .build()
    }

    /** PiP window matches the video's shape (Android allows 1:2.39 … 2.39:1). */
    private fun videoAspect(): Rational {
        val track = runCatching { if (released) null else player.currentVideoTrack }.getOrNull()
        val w = track?.width ?: 0
        val h = track?.height ?: 0
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val r = (w.toFloat() / h).coerceIn(1f / 2.39f + 0.01f, 2.39f - 0.01f)
        return Rational((r * 1000).roundToInt(), 1000)
    }

    private fun setPipParams() {
        if (state.inPip) setPictureInPictureParams(pipParams())
    }

    override fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "Picture-in-picture not supported", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Pressing Home while a video plays shrinks it into a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!released && player.isPlaying && !state.backgroundPlay) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        state.inPip = isInPip
        if (isInPip) state.controlsVisible = false
    }

    // ---------- lifecycle ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        // Start the notification service while we're still allowed to (app still visible).
        if (!released && state.backgroundPlay && player.isPlaying && !isFinishing) PlaybackService.start(this)
    }

    override fun onStop() {
        super.onStop()
        if (released) return
        saveResume()
        if (state.backgroundPlay && player.isPlaying && !isFinishing) {
            player.setVideoTrackEnabled(false) // audio only while hidden
            return
        }
        wasPlaying = player.isPlaying && !state.inPip
        player.pause()
        PlaybackService.stop(this)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.stop(this)
        if (released) return
        player.setVideoTrackEnabled(true)
        if (wasPlaying && videoLayout != null && !player.isPlaying) player.play()
        wasPlaying = true
    }

    override fun onDestroy() {
        main.removeCallbacks(sleepRunnable)
        if (!isChangingConfigurations) saveResume()
        releasePlayer()
        closeRetriever()
        runCatching { unregisterReceiver(pipReceiver) }
        if (PlaybackService.player === this) {
            PlaybackService.player = null
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }

    private fun saveResume() {
        if (released || !::player.isInitialized) return
        val key = videoUri.toString()
        val t = player.time
        val len = player.length
        if (len > 0 && t > RESUME_MIN_MS && t < len - RESUME_MIN_MS) resume.put(key, t)
        else if (len > 0 && t >= len - RESUME_MIN_MS) { resume.clear(key); LibraryStore.markWatched(key) }
    }

    private fun releasePlayer() {
        if (released || !::player.isInitialized) return
        released = true
        stopCastScan()
        rendererItems.clear()
        state.renderers = emptyList()
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVLC.release()
        pfd?.close()
        pfd = null
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun displayName(uri: Uri): String? =
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        } else uri.lastPathSegment
}
