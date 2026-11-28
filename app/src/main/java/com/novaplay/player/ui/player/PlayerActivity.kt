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
import org.videolan.libvlc.RendererDiscoverer
import android.widget.FrameLayout
import com.novaplay.player.subs.SubSync
import com.novaplay.player.ui.player.engine.Engine
import com.novaplay.player.ui.player.engine.EngineListener
import com.novaplay.player.ui.player.engine.EqPresets
import com.novaplay.player.ui.player.engine.ExoEngine
import com.novaplay.player.ui.player.engine.SubFile
import com.novaplay.player.ui.player.engine.SubStyle
import com.novaplay.player.ui.player.engine.VlcEngine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.novaplay.player.subs.OnlineSub
import com.novaplay.player.subs.OpenSubtitles
import com.novaplay.player.subs.FreeSubtitles
import org.videolan.libvlc.RendererItem
import android.net.wifi.WifiManager
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
    private lateinit var player: Engine
    private var videoHost: FrameLayout? = null
    /** Only for casting: discovers renderers and plays to them. */
    private var castVlc: LibVLC? = null
    /** Video that ExoPlayer couldn't play: stays on libVLC. */
    private var vlcOnlyFor: Uri? = null
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
    private var openFailures = 0
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
    private var castItem: RendererItem? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var retriever: MediaMetadataRetriever? = null
    @Volatile private var retrieverFor: Uri? = null
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
        state.hwDecoder = settings.hwDecoder
        state.fastSeek = settings.fastSeek
        state.backgroundPlay = settings.backgroundPlay
        state.autoNext = settings.autoNext
        state.nightMode = settings.nightMode
        state.loopMode = LoopMode.entries[settings.loopMode.coerceIn(0, 2)]
        state.shuffle = settings.shuffle
        state.rate = settings.speed / 100f
        state.subColor = settings.subColor.coerceIn(0, SUB_COLORS.lastIndex)
        state.subBackground = settings.subBackground
        state.subBold = settings.subBold
        state.subFont = settings.subFont.coerceIn(0, SUB_FONTS.lastIndex)
        state.subOutline = settings.subOutline.coerceIn(0, 3)
        state.subShadow = settings.subShadow
        state.subOverride = settings.subOverride
        state.subPos = settings.subPos / 1000f
        // Older versions stored a percentage: turn it into the same size in pixels once.
        state.subPx = settings.subPx.takeIf { it > 0 } ?: (subBasePx() * settings.subtitleScale / 100f).roundToInt()
        state.eqPreset = settings.eqPreset
        state.bassDb = settings.bassDb
        state.holdSpeed = settings.holdSpeed / 100f
        state.seekStep = settings.seekStep
        state.scale = VideoScale.entries.getOrElse(settings.scale) { VideoScale.FIT }
        state.volumeBoost = settings.volumeBoost
        // Brightness picked in the player last time (‑1 = follow the system).
        if (settings.brightness >= 0) {
            brightness = settings.brightness / 1000f
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Like MX Player: the system bars come and go with the player controls, so the back/home
        // buttons are one tap away instead of needing an edge swipe that also drags the video.
        lifecycleScope.launch {
            snapshotFlow { state.controlsVisible && !state.locked && !state.inPip }
                .collect { if (it) showSystemBars() else hideSystemBars() }
        }
        hideSystemBars()
        ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PlaybackService.player = this
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED
        )

        createPlayer(startMs = resume.get(videoUri.toString()))
        state.eqPresets = EqPresets.names

        setContent {
            AppTheme { PlayerScreen(state, this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Save the old video's position before the playlist changes: afterwards videoUri is the new one.
        saveResume()
        if (readPlaylist(intent)) { setIntent(intent); switchTo(index, saveOld = false) }
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
            LibraryStore.playSource = null
            uris = listOf(uri)
            titles = listOf(i.getStringExtra(EXTRA_TITLE) ?: displayName(uri) ?: uri.lastPathSegment ?: "Video")
            index = 0
        }
        return true
    }

    // ---------- engine setup ----------

    private fun subStyle() = SubStyle(
        px = state.subPx, pos = state.subPos, color = state.subColor, background = state.subBackground,
        outline = state.subOutline, shadow = state.subShadow, bold = state.subBold, font = state.subFont,
        override = state.subOverride,
    )

    private fun createPlayer(startMs: Long, renderer: RendererItem? = castItem) {
        released = false
        // Fresh player (new video, loop, or restart): remembered tracks must be applied again.
        trackRestoreFor = null
        spuRestoreFor = null
        LibraryStore.markPlayed(videoUri.toString())
        LibraryStore.rememberSource(videoUri.toString())
        state.hasPrev = index > 0
        state.hasNext = index < uris.lastIndex || (state.shuffle && uris.size > 1)
        state.title = videoTitle
        state.nextTitle = if (index < uris.lastIndex && !state.shuffle) titles.getOrNull(index + 1) else null
        state.bookmarks = loadBookmarks()
        state.queue = titles
        state.playlistIndex = index
        state.playlistSize = uris.size

        val start = if (startMs > RESUME_MIN_MS) startMs else 0L
        player = try {
            // ExoPlayer seeks and changes speed without stalling the sound; libVLC plays the files it
            // can't, and is the only one that can cast.
            if (renderer != null || vlcOnlyFor == videoUri) {
                VlcEngine(this, videoUri, start, state.hwDecoder, subStyle(), engineListener, castVlc.takeIf { renderer != null }, renderer)
            } else ExoEngine(this, videoUri, start, subStyle(), engineListener)
        } catch (e: Exception) {
            released = true
            Toast.makeText(this, "Can't open video: ${e.message}", Toast.LENGTH_LONG).show()
            // Skip to the next one, but give up once every video failed (shuffle would loop forever).
            if (state.hasNext && ++openFailures < uris.size) main.post { playNext() } else finish()
            return
        }
        openFailures = 0
        state.resumedFrom = if (startMs > RESUME_MIN_MS) startMs else -1L
        player.rate = state.rate
        player.volume = state.volumeBoost
        player.audioDelayMs = state.audioDelayMs
        player.setEqualizer(state.eqPreset, state.bassDb)
        // Re-attach a subtitle file the user picked for this video before.
        settings.subtitleFor(videoUri.toString())?.let { path ->
            if (File(path).exists()) runCatching { player.addSubtitle(File(path)) }
        }

        // On first launch the view doesn't exist yet; createVideoView attaches.
        videoHost?.let { player.attach(it); player.setScale(state.scale) }
    }

    override fun createVideoView(context: Context): View =
        FrameLayout(context).also {
            it.setBackgroundColor(android.graphics.Color.BLACK)
            videoHost = it
            if (!released) { player.attach(it); player.setScale(state.scale) }
        }

    private val engineListener = object : EngineListener {
        override fun onPlaying() {
            if (released) return
            state.playing = true
            requestAudioFocus()
            if (player.rate != state.rate && !state.holdingFast) player.rate = state.rate
            onFirstPlaying?.let { onFirstPlaying = null; it() }
            refreshTracks()
            loadChapters()
            onPlayStateChanged()
        }

        override fun onPaused() {
            if (released) return
            state.playing = false
            onPlayStateChanged()
        }

        override fun onEnded() { this@PlayerActivity.onEnded() }

        override fun onError(unsupported: Boolean, message: String?) {
            if (released) return
            if (!player.isVlc && state.castingTo == null) {
                // ExoPlayer can't play this one: libVLC plays nearly everything.
                vlcOnlyFor = videoUri
                restartSameVideo(afterError = true)
                return
            }
            Toast.makeText(this@PlayerActivity, "Playback error", Toast.LENGTH_LONG).show()
            // Software decoding often fixes black screen / codec issues. Only for this session:
            // one bad file shouldn't switch the user's saved preference.
            if (state.hwDecoder) { state.hwDecoder = false; state.flash("Switched to software decoder"); restartSameVideo(afterError = true) }
            else finish()
        }

        override fun onTime(ms: Long) {
            if (released) return
            // After a seek libVLC still reports a few old times; ignore them so the bar doesn't jump back.
            val settled = SystemClock.uptimeMillis() > seekGuardUntil || abs(ms - seekTarget) < 1_500
            if (!state.seeking && settled) state.time = ms
            if (ms > 0) state.started = true
            // A-B repeat: jump back to A once we pass B.
            if (state.abStart >= 0 && state.abEnd > state.abStart && ms >= state.abEnd) seekTo(state.abStart)
        }

        override fun onLength(ms: Long) {
            if (released) return
            state.length = ms
            loadChapters()
        }

        override fun onTracksChanged() { if (!released) refreshTracks() }
        override fun onVideoSize() { if (!released) autoOrient() }
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

    // Remembered track picks, applied once per video as soon as the tracks show up.
    private var trackRestoreFor: Uri? = null
    private var spuRestoreFor: Uri? = null
    private var orientationFor: Uri? = null
    private var userRotated = false

    private fun restoreTracks() {
        val key = videoUri.toString()
        if (trackRestoreFor != videoUri) {
            val a = player.audioTracks()
            if (a.isNotEmpty()) {
                trackRestoreFor = videoUri
                settings.trackFor(key, "a")?.let { id -> if (a.any { it.id == id }) player.audioTrack = id }
            }
        }
        if (spuRestoreFor != videoUri) {
            val id = settings.trackFor(key, "s")
            when {
                id == null -> spuRestoreFor = videoUri
                id == -1 -> { player.spuTrack = -1; spuRestoreFor = videoUri }
                player.spuTracks().any { it.id == id } -> { player.spuTrack = id; spuRestoreFor = videoUri }
            }
        }
    }

    /** Portrait videos open in portrait, wide ones in landscape — unless the user rotated by hand. */
    private fun autoOrient() {
        if (orientationFor == videoUri || userRotated) return
        val t = player.videoInfo() ?: return
        orientationFor = videoUri
        val w = t.width; val h = t.height
        requestedOrientation = if (h > w) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun refreshTracks() {
        restoreTracks()
        autoOrient()
        state.audioTracks = player.audioTracks()
        state.spuTracks = player.spuTracks()
        state.audioTrack = player.audioTrack
        state.spuTrack = player.spuTrack
    }

    private fun loadChapters() {
        state.chapters = player.chapters()
    }

    /** Switches to another video in the playlist. */
    private fun switchTo(newIndex: Int, fromStart: Boolean = false, saveOld: Boolean = true) {
        if (newIndex !in uris.indices) return
        if (saveOld) saveResume()
        releasePlayer()
        index = newIndex
        state.resetForNewVideo()
        closeRetriever()
        createPlayer(if (fromStart) 0 else resume.get(videoUri.toString()))
        PlaybackService.update(this)
    }

    /** Runs once when the next player starts playing. */
    private var onFirstPlaying: (() -> Unit)? = null

    /** Restarts the same video at the same spot (engine switch, decoder change, libVLC start-up options). */
    private fun restartSameVideo(afterError: Boolean = false) {
        if (released) return
        // After a playback error libVLC reports no time and "not playing": use what the UI last showed and keep playing.
        val pos = player.time.takeIf { it > 0 } ?: state.time
        val spu = state.spuTrack
        val audioId = state.audioTrack
        val wasPaused = !afterError && !player.isPlaying
        releasePlayer()
        createPlayer(startMs = pos)
        if (released) return
        state.resumedFrom = -1L // not a resume: no "Start over" pill
        // Short positions skip the start-time option (resume threshold), so seek once playing.
        if (pos in 1..RESUME_MIN_MS) player.seek(pos, false)
        onFirstPlaying = {
            if (wasPaused) player.pause()
            if (audioId >= 0) player.audioTrack = audioId
            player.spuTrack = spu
            player.spuDelayMs = state.spuDelayMs
        }
    }

    // ---------- PlayerController ----------

    override fun togglePlay() {
        if (released) return
        lastStepTarget = -1
        if (player.isPlaying) player.pause() else player.play()
        state.bump()
    }

    override fun seekTo(ms: Long) = seekTo(ms, fast = false)

    /** [fast] jumps to the keyframe before the target: no decode-forward wait, so sound comes back at once. */
    private fun seekTo(ms: Long, fast: Boolean) {
        if (released) return
        val target = if (state.length > 0) ms.coerceIn(0, state.length) else ms.coerceAtLeast(0)
        player.seek(target, fast)
        state.time = target
        seekTarget = target
        seekGuardUntil = SystemClock.uptimeMillis() + 800
    }

    override fun seekBy(deltaMs: Long) {
        if (released) return
        // Quick repeated taps: the engine may still report the old time, so add on top of the last target instead.
        val base = if (SystemClock.uptimeMillis() < seekGuardUntil) seekTarget else player.time
        seekTo(base + deltaMs, fast = state.fastSeek)
    }

    override fun setRate(rate: Float) {
        state.rate = rate
        settings.speed = (rate * 100).toInt()
        changeRate(rate)
    }

    override fun startHoldFast() {
        if (state.holdingFast || !player.isPlaying) return
        rateBeforeHold = state.rate
        state.holdingFast = true
        changeRate(state.holdSpeed)
    }

    override fun stopHoldFast() {
        if (!state.holdingFast) return
        state.holdingFast = false
        changeRate(rateBeforeHold)
    }

    private fun changeRate(rate: Float) {
        if (released || player.rate == rate) return
        player.rate = rate
    }

    override fun setScale(scale: VideoScale) {
        state.scale = scale
        settings.scale = scale.ordinal
        applyScale(scale)
        state.flash(scale.label)
    }

    private fun applyScale(scale: VideoScale) {
        if (!released) player.setScale(scale)
    }

    override fun selectAudio(id: Int) {
        player.audioTrack = id
        state.audioTrack = id
        settings.setTrackFor(videoUri.toString(), "a", id)
    }

    override fun selectSpu(id: Int) {
        player.spuTrack = id
        state.spuTrack = id
        settings.setTrackFor(videoUri.toString(), "s", id)
    }

    override fun pickSubtitleFile() = subtitlePicker.launch(arrayOf("*/*"))

    private fun loadExternalSubtitle(uri: Uri) {
        // Subtitle files are read by path, so copy the picked file into app storage first.
        val name = displayName(uri) ?: "subtitle.srt"
        val dir = File(filesDir, "subs").apply { mkdirs() } // kept so the subtitle reloads next time
        val file = File(dir, "${videoUri.toString().hashCode()}_$name")
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            player.addSubtitle(file)
            settings.setSubtitleFor(videoUri.toString(), file.absolutePath)
        }.onSuccess {
            state.flash("Subtitle loaded")
            autoSyncLoaded(file)
        }.onFailure {
            Toast.makeText(this, "Couldn't load subtitle", Toast.LENGTH_SHORT).show()
        }
    }

    /** Checks a just-loaded subtitle against the audio and swaps in a re-timed copy if it's clearly off. */
    private fun autoSyncLoaded(file: File) {
        val key = videoUri.toString()
        lifecycleScope.launch {
            val speech = runCatching { speechForSync() }.getOrNull()
            speech ?: return@launch
            val cues = withContext(Dispatchers.IO) { runCatching { SubFile.read(file) }.getOrDefault(emptyList()) }
            val fit = SubSync.fit(speech, cues)
            fit ?: return@launch
            if (released || key != videoUri.toString() || !fit.confident) return@launch
            if (kotlin.math.abs(fit.offsetMs) < 150 && fit.scale == 1.0) return@launch
            loadOnlineFile(file, fit, null)
        }
    }

    override fun shiftSpuDelay(deltaMs: Long) {
        state.spuDelayMs += deltaMs
        if (!released) player.spuDelayMs = state.spuDelayMs
    }

    /** libVLC's default subtitle size: 6.25% of the picture height. */
    private fun subBasePx() = 0.0625f * subRefHeight()
    private fun subRefHeight() = resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }

    private val subRestart = Runnable { restartSameVideo() }

    override fun applySubStyle() {
        settings.subColor = state.subColor; settings.subBackground = state.subBackground; settings.subBold = state.subBold
        settings.subFont = state.subFont; settings.subPx = state.subPx; settings.subOutline = state.subOutline
        settings.subShadow = state.subShadow; settings.subOverride = state.subOverride
        if (released || player.setSubStyle(subStyle())) return
        // libVLC reads subtitle options only at start; wait for the taps to stop before reloading.
        main.removeCallbacks(subRestart)
        main.postDelayed(subRestart, 450)
    }

    override fun setSubPosition(fraction: Float) {
        state.subPos = fraction.coerceIn(0f, 0.85f)
        settings.subPos = (state.subPos * 1000).roundToInt()
        if (released || player.setSubStyle(subStyle())) return
        main.removeCallbacks(subRestart)
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
                seekTo(state.abStart)
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

    override fun setFastSeek(enabled: Boolean) {
        state.fastSeek = enabled
        settings.fastSeek = enabled
        state.flash(if (enabled) "Fast seek on" else "Exact seek")
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

    override fun setHoldSpeed(speed: Float) {
        state.holdSpeed = speed
        settings.holdSpeed = (speed * 100).toInt()
    }

    override fun setSeekStep(seconds: Int) {
        state.seekStep = seconds
        settings.seekStep = seconds
    }

    // Audio focus: pause for calls / other apps, resume after a short interruption.
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var pausedByFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            if (released) return@post
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; if (player.isPlaying) player.pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (player.isPlaying) { pausedByFocus = true; player.pause() }
                AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; player.play() }
            }
        }
    }

    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build()
                )
                .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
    }

    // Headphones unplugged / Bluetooth dropped → pause instead of blasting the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!released && player.isPlaying) { player.pause(); state.flash("Paused — headphones disconnected") }
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
        val surface = if (released) return else player.surfaceView() ?: return
        if (surface.width == 0 || surface.height == 0) return
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bmp, { result ->
            if (result != PixelCopy.SUCCESS) {
                Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
                return@request
            }
            // PNG encoding of a full frame takes a while: keep it off the UI thread.
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { saveScreenshot(bmp).also { bmp.recycle() } }
                if (saved) state.flash(if (Build.VERSION.SDK_INT >= 29) "Screenshot saved to Pictures/NovaPlay" else "Screenshot saved")
                else Toast.makeText(this@PlayerActivity, "Screenshot failed", Toast.LENGTH_SHORT).show()
            }
        }, main)
    }

    private fun saveScreenshot(bmp: Bitmap): Boolean {
        val name = "NovaPlay_${System.currentTimeMillis()}.png"
        return runCatching {
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
        }.isSuccess
    }

    override suspend fun previewFrame(ms: Long): Bitmap? = withContext(Dispatchers.IO) {
        retrieverLock.withLock {
            runCatching {
                val uri = videoUri
                // A preview still running while the video switched can leave the old file's retriever behind.
                if (retrieverFor != uri) { retriever?.let { old -> runCatching { old.release() } }; retriever = null }
                val r = retriever ?: MediaMetadataRetriever().also {
                    // File descriptor works on more phones than a content Uri.
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> it.setDataSource(fd.fileDescriptor) }
                    }.getOrElse { _ -> it.setDataSource(this@PlayerActivity, uri) }
                    retriever = it
                    retrieverFor = uri
                }
                val us = ms * 1000
                if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
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
        // Like MX Player: past the phone's max, keep swiping up to boost the sound to 200%.
        if (volumeAcc < 0) {
            val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            volumeAcc = if (sys >= max && state.volumeBoost > 100) max * state.volumeBoost / 100f else sys
        }
        volumeAcc = (volumeAcc + delta * max * 1.5f).coerceIn(0f, max * 2f)
        // Some phones refuse volume changes in Do Not Disturb: ignore instead of crashing.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeAcc.coerceAtMost(max.toFloat()).roundToInt(), 0) }
        val boost = (volumeAcc / max * 100).roundToInt().coerceIn(100, 200)
        if (boost != state.volumeBoost) {
            state.volumeBoost = boost
            settings.volumeBoost = boost
            if (!released) player.volume = boost
        }
        return (volumeAcc / max * 100).roundToInt()
    }

    override fun endGesture() {
        volumeAcc = -1f
        if (brightness >= 0) settings.brightness = (brightness * 1000).roundToInt()
    }

    override fun toggleRotation() {
        userRotated = true
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
        // Forward on libVLC: its real next-frame is exact. Seeking is the fallback (and the only way back).
        if (forward && player.nativeFrameStep()) { lastStepTarget = -1; state.bump(); return }
        val fps = player.videoInfo()?.fps?.takeIf { it > 1f } ?: 30f
        val frameMs = 1000.0 / fps
        // The reported time can lag while paused, so keep stepping from our own last target.
        val now = SystemClock.uptimeMillis()
        val base = if (now - lastStepAt < 5_000 && lastStepTarget >= 0 && abs(player.time - lastStepTarget) < 1_000) lastStepTarget else player.time
        val frame = Math.floor(base / frameMs).toLong() + if (forward) 1 else -1
        // Aim at the middle of the frame so rounding never lands on its neighbour.
        val target = ((frame.coerceAtLeast(0) + 0.5) * frameMs).toLong()
        lastStepAt = now
        lastStepTarget = target
        player.seek(target, false) // precise (non-fast) seek so we land on that exact frame
        state.time = target
        seekTarget = target
        seekGuardUntil = SystemClock.uptimeMillis() + 800
        state.bump()
    }

    // ---------- Audio ----------

    private fun applyAudio() {
        if (released) return
        player.volume = state.volumeBoost
        player.audioDelayMs = state.audioDelayMs
        player.setEqualizer(state.eqPreset, state.bassDb)
    }

    override fun shiftAudioDelay(deltaMs: Long) {
        state.audioDelayMs += deltaMs
        if (!released) player.audioDelayMs = state.audioDelayMs
    }

    override fun setVolumeBoost(percent: Int) {
        state.volumeBoost = percent.coerceIn(100, 200)
        settings.volumeBoost = state.volumeBoost
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
        state.onlineStatus = "Downloading…"
        val key = videoUri.toString()
        lifecycleScope.launch {
            runCatching {
                val file = OpenSubtitles.download(sub, File(filesDir, "subs"))
                state.onlineStatus = "Matching to the audio…"
                val speech = speechForSync()
                val fit = speech?.let { sp -> withContext(Dispatchers.IO) { SubFile.read(file) }.let { SubSync.fit(sp, it) } }
                file to fit
            }.onSuccess { (file, fit) ->
                if (!released && key == videoUri.toString()) loadOnlineFile(file, fit, null)
            }.onFailure { state.onlineError = it.message ?: "Download failed" }
            state.onlineStatus = null
            state.onlineBusy = false
        }
    }

    /**
     * Downloads the top few results, checks how well each one's timing follows the speech in this
     * video, and loads the best one (shifted so it lines up).
     */
    override fun pickBestOnlineSub() {
        if (state.onlineBusy || state.onlineSubs.isEmpty()) return
        state.onlineBusy = true
        state.onlineError = null
        val key = videoUri.toString()
        // Same language as the top result (what the search ranked first), best-known first.
        val lang = state.onlineSubs.first().language
        val pool = state.onlineSubs.filter { it.language == lang }.take(8)
        lifecycleScope.launch {
            runCatching {
                state.onlineStatus = "Listening to the audio…"
                val speech = speechForSync() ?: error("Couldn't read this video's audio")
                var best: Triple<OnlineSub, File, SubSync.Fit>? = null
                pool.forEachIndexed { i, sub ->
                    state.onlineStatus = "Comparing ${i + 1} of ${pool.size}…"
                    val file = runCatching { OpenSubtitles.download(sub, File(filesDir, "subs")) }.getOrNull() ?: return@forEachIndexed
                    val cues = withContext(Dispatchers.IO) { runCatching { SubFile.read(file) }.getOrDefault(emptyList()) }
                    val fit = SubSync.fit(speech, cues) ?: return@forEachIndexed
                    // Exact file-hash matches get a small edge: made for this very release.
                    val b = best
                    val bonus = if (sub.exact) 0.02 else 0.0
                    if (b == null || fit.score + bonus > b.third.score + (if (b.first.exact) 0.02 else 0.0)) best = Triple(sub, file, fit)
                }
                best ?: error("Couldn't download any of them")
            }.onSuccess { (sub, file, fit) ->
                if (!released && key == videoUri.toString()) loadOnlineFile(file, fit, sub)
            }.onFailure { state.onlineError = it.message ?: "Couldn't pick a subtitle" }
            state.onlineStatus = null
            state.onlineBusy = false
        }
    }

    private suspend fun speechForSync(): SubSync.Speech? {
        val uri = videoUri
        if (uri.scheme != "content" && uri.scheme != "file") return null
        return SubSync.speech(this, uri, state.length)
    }

    /** Loads a downloaded subtitle, re-timed to the audio when the match is clear. */
    private suspend fun loadOnlineFile(file: File, fit: SubSync.Fit?, picked: OnlineSub?) {
        val key = videoUri.toString()
        val shift = fit?.takeIf { it.confident && (kotlin.math.abs(it.offsetMs) >= 150 || it.scale != 1.0) }
        val use = if (shift == null) file else withContext(Dispatchers.IO) {
            val out = File(file.parentFile, "synced_${file.nameWithoutExtension}.srt")
            SubFile.writeSrt(SubFile.retime(SubFile.read(file), shift.offsetMs, shift.scale), out)
            out
        }
        runCatching { player.addSubtitle(use) }.onFailure { state.onlineError = "Couldn't load subtitle"; return }
        settings.setSubtitleFor(key, use.absolutePath)
        // The file is now timed to the audio: any manual delay from before would push it off again.
        if (shift != null) { state.spuDelayMs = 0; player.spuDelayMs = 0 }
        val match = fit?.let { " · ${(it.score.coerceIn(0.0, 1.0) * 100).roundToInt()}% match" }.orEmpty()
        val synced = shift?.let { " · synced ${if (it.offsetMs >= 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(it.offsetMs) / 1000.0)}s" }.orEmpty()
        state.flash((if (picked != null) "Best match loaded" else "Subtitle loaded") + synced + match)
        state.onlineLoaded++
    }

    // ---------- Casting (Chromecast etc. via libVLC renderers) ----------

    override fun startCastScan() {
        if (released || discoverers.isNotEmpty()) return
        multicastLock = runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("novaplay-cast").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val vlc = castVlc ?: runCatching { LibVLC(this, ArrayList(VlcEngine.options(subStyle()))) }.getOrNull()?.also { castVlc = it } ?: return
        discoverers = RendererDiscoverer.list(vlc).mapNotNull { d ->
            runCatching {
                RendererDiscoverer(vlc, d.name).also { rd ->
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
        val pos = player.time.takeIf { it > 0 } ?: state.time
        val wasPaused = !player.isPlaying
        // Casting runs on libVLC (with the same instance that found the device); a renderer only takes
        // effect when the media starts, so reopen it at the same spot.
        castItem = item
        state.castingTo = name
        val keep = rendererItems.toMap()
        player.release()
        released = true
        createPlayer(startMs = pos, renderer = item)
        if (released) return
        rendererItems.putAll(keep)
        state.resumedFrom = -1L
        if (pos in 1..RESUME_MIN_MS) player.seek(pos, false)
        if (wasPaused) onFirstPlaying = { player.pause() }
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
        val track = runCatching { if (released) null else player.videoInfo() }.getOrNull()
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
        if (hasFocus && !(state.controlsVisible && !state.locked)) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the player: onStop/onDestroy only come once the library has settled (seconds later),
        // so silence it now instead of letting the sound run on behind the library.
        if (isFinishing && !released && ::player.isInitialized) {
            saveResume()
            player.volume = 0
            player.pause()
            releasePlayer()
            return
        }
        // Start the notification service while we're still allowed to (app still visible).
        if (!released && state.backgroundPlay && player.isPlaying && !isFinishing) PlaybackService.start(this)
    }

    override fun onStop() {
        super.onStop()
        if (released) return
        saveResume()
        if (state.backgroundPlay && player.isPlaying && !isFinishing) {
            player.setVideoEnabled(false) // audio only while hidden
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
        // Leaving the app destroys the surface (libVLC then drops its views for good) and background
        // play turned the picture off: bring both back.
        videoHost?.let { player.attach(it); applyScale(state.scale) }
        player.onSurfaceRestored()
        if (wasPlaying && videoHost != null && !player.isPlaying) player.play()
        wasPlaying = true
    }

    override fun onDestroy() {
        main.removeCallbacks(sleepRunnable)
        main.removeCallbacks(subRestart)
        if (!isChangingConfigurations) saveResume()
        releasePlayer()
        castVlc?.let { runCatching { it.release() } }
        castVlc = null
        closeRetriever()
        runCatching { unregisterReceiver(pipReceiver) }
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
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
        onFirstPlaying = null
        player.release()
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
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
