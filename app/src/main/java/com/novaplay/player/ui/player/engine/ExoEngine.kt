package com.novaplay.player.ui.player.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.novaplay.player.ui.player.Chapter
import com.novaplay.player.ui.player.SUB_COLORS
import com.novaplay.player.ui.player.Track
import com.novaplay.player.ui.player.VideoScale
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * ExoPlayer engine (default). Seeks and speed changes don't interrupt the sound the way libVLC's do.
 *
 * Subtitle files the user adds are drawn by us from their parsed cues (so the subtitle delay works in
 * both directions); subtitles inside the video come from ExoPlayer and can only be delayed (later).
 */
@OptIn(UnstableApi::class)
class ExoEngine(
    private val context: Context,
    uri: Uri,
    startMs: Long,
    private var style: SubStyle,
    private val listener: EngineListener,
) : Engine {
    override val isVlc = false
    private val main = Handler(Looper.getMainLooper())
    private val delayProcessor = AudioDelay()
    private val sessionId = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    private val exo: ExoPlayer
    private var released = false

    private var host: FrameLayout? = null
    private val frame = AspectRatioFrameLayout(context)
    private val surface = SurfaceView(context)
    private val subView = SubtitleView(context)

    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var scale = VideoScale.FIT
    private var startedOnce = false
    private var lastLength = -1L

    /** External subtitle files: id (EXT_BASE + n) → name + cues. */
    private val external = ArrayList<Triple<Int, String, List<SubCue>>>()
    private var externalSelected = -1
    /** In-stream cues as they arrived (media time ms), replayed later for a positive subtitle delay. */
    private val history = ArrayDeque<Pair<Long, List<Cue>>>()
    private var lastShown: List<Cue>? = null

    init {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(Downmix(), delayProcessor))
                    .build()
        }.setEnableDecoderFallback(true)
            // FFmpeg audio (AC3, E-AC3, DTS, TrueHD…) when the phone has no decoder for it.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        exo = ExoPlayer.Builder(context, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exo.setAudioSessionId(sessionId)
        exo.setVideoSurfaceView(surface)
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!startedOnce) return
                if (playWhenReady) listener.onPlaying() else listener.onPaused()
                tick()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        reportLength()
                        if (!startedOnce) {
                            startedOnce = true
                            if (exo.playWhenReady) listener.onPlaying() else listener.onPaused()
                        }
                        tick()
                    }
                    Player.STATE_ENDED -> main.post { if (!released) listener.onEnded() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val unsupported = error.errorCode in 3000..4999 || error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED
                main.post { if (!released) listener.onError(unsupported, error.message) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // A picture or sound ExoPlayer can't decode at all: let the caller switch engines.
                val badVideo = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)
                val badAudio = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true)
                if (badVideo || badAudio) {
                    main.post { if (!released) listener.onError(true, if (badVideo) "video codec" else "audio codec") }
                    return
                }
                listener.onTracksChanged()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyScale()
                listener.onVideoSize()
            }

            override fun onCues(cueGroup: CueGroup) {
                history.addLast(cueGroup.presentationTimeUs / 1000 to cueGroup.cues)
                while (history.size > 400) history.removeFirst()
                if (externalSelected < 0 && spuDelayMs <= 0L) show(cueGroup.cues)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { history.clear(); show(emptyList()) }
                tick()
            }
        })

        frame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        subView.setApplyEmbeddedStyles(false)
        applySubStyle()

        exo.setMediaItem(MediaItem.fromUri(uri), startMs.coerceAtLeast(0))
        exo.playWhenReady = true
        exo.prepare()
        attachAudioEffects()
    }

    // ---------- time ----------

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (!released && exo.playWhenReady) main.postDelayed(this, 200)
        }
    }

    private fun tick() {
        if (released) return
        reportLength()
        listener.onTime(exo.currentPosition)
        renderSubs()
        main.removeCallbacks(ticker)
        if (exo.playWhenReady) main.postDelayed(ticker, 200)
    }

    private fun reportLength() {
        val d = exo.duration
        if (d != C.TIME_UNSET && d > 0 && d != lastLength) { lastLength = d; listener.onLength(d) }
    }

    // ---------- Engine ----------

    override val isPlaying get() = !released && exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_IDLE
    override val time get() = if (released) 0 else exo.currentPosition
    override val length get() = if (released) 0 else exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    override var rate: Float
        get() = exo.playbackParameters.speed
        set(v) { if (!released) exo.playbackParameters = PlaybackParameters(v) }

    override fun attach(host: FrameLayout) {
        if (released) return
        this.host = host
        (frame.parent as? ViewGroup)?.removeView(frame)
        (subView.parent as? ViewGroup)?.removeView(subView)
        host.addView(frame, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        host.addView(subView, 1, FrameLayout.LayoutParams(-1, -1))
        applyScale()
    }

    override fun play() {
        if (released) return
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
    }

    override fun pause() { if (!released) exo.pause() }

    override fun seek(ms: Long, fast: Boolean) {
        if (released) return
        exo.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        exo.seekTo(ms.coerceAtLeast(0))
    }

    private fun groups(type: Int) = exo.currentTracks.groups.filter { it.type == type }

    /** Tracks of [type], numbered in order: the n‑th track is id n. */
    private fun list(type: Int): List<Pair<Tracks.Group, Int>> =
        groups(type).flatMap { g -> (0 until g.length).map { g to it } }

    private fun label(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
        val parts = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, lang).distinct()
        return "Track ${n + 1}" + if (parts.isNotEmpty()) " - [${parts.joinToString(", ")}]" else ""
    }

    override fun audioTracks() = list(C.TRACK_TYPE_AUDIO).mapIndexed { i, (g, t) -> Track(i, label(g.getTrackFormat(t), i)) }

    override var audioTrack: Int
        get() = list(C.TRACK_TYPE_AUDIO).indexOfFirst { (g, t) -> g.isTrackSelected(t) }
        set(id) { select(C.TRACK_TYPE_AUDIO, id) }

    override fun spuTracks(): List<Track> {
        val inStream = list(C.TRACK_TYPE_TEXT).mapIndexed { i, (g, t) -> Track(i, label(g.getTrackFormat(t), i)) }
        return inStream + external.map { (id, name, _) -> Track(id, name) }
    }

    override var spuTrack: Int
        get() = when {
            externalSelected >= 0 -> externalSelected
            exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) -> -1
            else -> list(C.TRACK_TYPE_TEXT).indexOfFirst { (g, t) -> g.isTrackSelected(t) }
        }
        set(id) {
            if (id >= EXT_BASE && external.any { it.first == id }) {
                externalSelected = id
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                show(emptyList()); renderSubs()
            } else {
                externalSelected = -1
                show(emptyList())
                if (id < 0) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                else select(C.TRACK_TYPE_TEXT, id)
            }
            listener.onTracksChanged()
        }

    private fun select(type: Int, id: Int) {
        val (g, t) = list(type).getOrNull(id) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
            .build()
    }

    override fun addSubtitle(file: File) {
        val cues = runCatching { SubFile.read(file) }.getOrDefault(emptyList())
        if (cues.isEmpty()) error("No subtitles in file")
        val name = file.name.substringAfter('_').ifBlank { file.name }
        // Same file again (e.g. re-synced copy of it): replace instead of piling up.
        external.removeAll { it.second == name }
        val id = EXT_BASE + (external.maxOfOrNull { it.first - EXT_BASE + 1 } ?: 0)
        external += Triple(id, name, cues)
        spuTrack = id
    }

    override var spuDelayMs: Long = 0
        set(v) { field = v; renderSubs() }

    override var audioDelayMs: Long
        get() = delayProcessor.delayMs
        set(v) { delayProcessor.delayMs = v }

    override var volume: Int = 100
        set(v) {
            field = v.coerceIn(0, 200)
            if (released) return
            exo.volume = (field / 100f).coerceAtMost(1f)
            applyBoost()
        }

    private fun applyBoost() {
        val l = loudness ?: return
        runCatching {
            val gain = if (volume > 100) (2000 * log10(volume / 100.0)).roundToInt() else 0 // millibels
            l.setTargetGain(gain)
            l.enabled = gain > 0
        }
    }

    private var eqPreset = -1
    private var eqBass = 0

    override fun setEqualizer(preset: Int, bassDb: Int) {
        eqPreset = preset; eqBass = bassDb
        applyEq()
    }

    private fun applyEq() {
        val eq = equalizer ?: return
        runCatching {
            if (eqPreset < 0 && eqBass == 0) { eq.enabled = false; return }
            val gains = EqPresets.gains(eqPreset, eqBass)
            val range = eq.bandLevelRange // millibels
            // Leave headroom when boosting so loud passages don't clip.
            val headroom = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f) * 0.4f
            for (b in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(b.toShort()) / 1000f
                val db = EqPresets.gainAt(gains, hz) - headroom
                eq.setBandLevel(b.toShort(), (db * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = true
        }
    }

    private fun attachAudioEffects() {
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        applyEq()
        applyBoost()
    }

    // ---------- subtitles ----------

    override fun setSubStyle(style: SubStyle): Boolean {
        this.style = style
        applySubStyle()
        return true
    }

    private fun applySubStyle() {
        val s = style
        val rgb = SUB_COLORS.getOrNull(s.color)?.second ?: 0xFFFFFF
        val fg = Color.rgb(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
        val bg = if (s.background) Color.argb(170, 0, 0, 0) else Color.TRANSPARENT
        val edge = when {
            s.background -> CaptionStyleCompat.EDGE_TYPE_NONE
            s.outline > 0 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            s.shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val family = FONTS.getOrNull(s.font)
        val face = Typeface.create(family?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT, if (s.bold) Typeface.BOLD else Typeface.NORMAL)
        subView.setStyle(CaptionStyleCompat(fg, bg, Color.TRANSPARENT, edge, Color.BLACK, face))
        subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, s.px.toFloat())
        subView.setApplyEmbeddedFontSizes(false)
        subView.setApplyEmbeddedStyles(!s.override)
        val ref = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        val h = subView.height.takeIf { it > 0 } ?: ref
        subView.setBottomPaddingFraction((s.pos * ref / h).coerceIn(0.02f, 0.9f))
        if (subView.height == 0) subView.post { if (!released && subView.height > 0) subView.setBottomPaddingFraction((s.pos * ref / subView.height).coerceIn(0.02f, 0.9f)) }
    }

    private fun show(cues: List<Cue>) {
        if (cues === lastShown) return
        lastShown = cues
        subView.setCues(cues)
    }

    /** Draws the cues due now: from the chosen file, or the in-stream ones replayed with the delay. */
    private fun renderSubs() {
        if (released) return
        val now = exo.currentPosition - spuDelayMs
        if (externalSelected >= 0) {
            val cues = external.firstOrNull { it.first == externalSelected }?.third ?: return
            // Binary search for the last cue starting at or before now, then collect overlapping ones.
            var lo = 0; var hi = cues.size - 1; var idx = -1
            while (lo <= hi) { val mid = (lo + hi) / 2; if (cues[mid].start <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
            val active = ArrayList<SubCue>()
            var i = idx
            while (i >= 0 && i > idx - 8) { if (cues[i].end > now) active.add(0, cues[i]); i-- }
            val key = active.joinToString("\u0000") { it.text }
            if (key != shownKey) {
                shownKey = key
                show(active.map { Cue.Builder().setText(it.text).build() })
            }
            return
        }
        shownKey = null
        if (spuDelayMs > 0) {
            val due = history.lastOrNull { it.first <= now }?.second ?: emptyList()
            show(due)
        }
    }
    private var shownKey: String? = null

    // ---------- picture ----------

    override fun chapters(): List<Chapter> = emptyList()

    override fun videoInfo(): VideoInfo? {
        val f = exo.videoFormat ?: return null
        val vs = exo.videoSize
        if (vs.width <= 0 || vs.height <= 0) return null
        // Most decoders apply the rotation themselves (unappliedRotation 0); otherwise swap.
        val rot = vs.unappliedRotationDegrees % 180 != 0
        val w = (vs.width * vs.pixelWidthHeightRatio).roundToInt()
        return if (rot) VideoInfo(vs.height, w, f.frameRate) else VideoInfo(w, vs.height, f.frameRate)
    }

    override fun setScale(scale: VideoScale) { this.scale = scale; applyScale() }

    private fun applyScale() {
        val info = videoInfo()
        val aspect = when (scale) {
            VideoScale.R16_9 -> 16f / 9
            VideoScale.R4_3 -> 4f / 3
            VideoScale.R21_9 -> 21f / 9
            else -> info?.let { it.width.toFloat() / it.height } ?: 0f
        }
        frame.setAspectRatio(aspect)
        frame.resizeMode = when (scale) {
            VideoScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoScale.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        // 100%: the video's own pixel size, centred (shrunk to fit if it's bigger than the screen).
        val lp = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        if (scale == VideoScale.ORIGINAL && info != null) { lp.width = info.width; lp.height = info.height }
        else { lp.width = -1; lp.height = -1 }
        lp.gravity = Gravity.CENTER
        frame.layoutParams = lp
    }

    override fun nativeFrameStep() = false

    override fun setVideoEnabled(enabled: Boolean) {
        if (released) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }

    override fun onSurfaceRestored() {
        if (released) return
        if (exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) setVideoEnabled(true)
    }

    override fun surfaceView(): SurfaceView = surface

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        exo.volume = 0f
        exo.stop()
        exo.release()
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        (frame.parent as? ViewGroup)?.removeView(frame)
        (subView.parent as? ViewGroup)?.removeView(subView)
    }

    companion object {
        const val EXT_BASE = 1000
        /** Android font families matching SUB_FONTS (null = default). */
        private val FONTS = listOf(null, "serif", "monospace", "serif-monospace", "casual", "cursive")
    }
}
