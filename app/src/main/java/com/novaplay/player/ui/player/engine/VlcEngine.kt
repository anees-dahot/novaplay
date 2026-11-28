package com.novaplay.player.ui.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.novaplay.player.ui.player.Chapter
import com.novaplay.player.ui.player.FrameStepper
import com.novaplay.player.ui.player.SUB_COLORS
import com.novaplay.player.ui.player.SUB_FONTS
import com.novaplay.player.ui.player.Track
import com.novaplay.player.ui.player.VideoScale
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.roundToInt

/**
 * libVLC engine: plays almost anything and can cast, but stalls the sound on seeks and speed
 * changes, so it's only the fallback. [sharedVlc] (used for casting) is not released by us.
 */
class VlcEngine(
    private val context: Context,
    private val uri: Uri,
    startMs: Long,
    hwDecoder: Boolean,
    private var style: SubStyle,
    private val listener: EngineListener,
    sharedVlc: LibVLC? = null,
    renderer: RendererItem? = null,
) : Engine {
    override val isVlc = true
    private val ownsVlc = sharedVlc == null
    val libVLC: LibVLC = sharedVlc ?: LibVLC(context, ArrayList(options(style)))
    private val player = MediaPlayer(libVLC)
    private var pfd: ParcelFileDescriptor? = null
    private var layout: VLCVideoLayout? = null
    private var released = false
    private val main = Handler(Looper.getMainLooper())
    private var pendingSeek: Long? = null
    private var audioApplied = false
    private var appliedEq: Pair<Int, Int>? = null
    private var wantEq: Pair<Int, Int> = -1 to 0
    private var wantVolume = 100
    private var wantAudioDelay = 0L

    init {
        player.setEventListener(::onEvent)
        if (renderer != null) player.setRenderer(renderer)
        val media = if (uri.scheme == "content") {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("no file descriptor")
            pfd = fd
            Media(libVLC, fd.fileDescriptor)
        } else Media(libVLC, uri)
        media.setHWDecoderEnabled(hwDecoder, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        player.media = media
        media.release()
    }

    private fun onEvent(e: MediaPlayer.Event) {
        if (released) return
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                restoreVideo()
                // Only once per player: re-setting the equalizer restarts libVLC's audio chain (a gap).
                if (!audioApplied) { audioApplied = true; applyAudio() }
                pendingSeek?.let { player.time = it; pendingSeek = null }
                listener.onPlaying()
                listener.onTracksChanged()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> listener.onPaused()
            MediaPlayer.Event.Vout -> listener.onVideoSize()
            MediaPlayer.Event.LengthChanged -> listener.onLength(e.lengthChanged)
            MediaPlayer.Event.TimeChanged -> listener.onTime(e.timeChanged)
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> listener.onTracksChanged()
            // Don't tear the player down inside its own callback; post it.
            MediaPlayer.Event.EndReached -> main.post { if (!released) listener.onEnded() }
            MediaPlayer.Event.EncounteredError -> main.post { if (!released) listener.onError(false, null) }
        }
    }

    override val isPlaying get() = !released && player.isPlaying
    override val time get() = player.time
    override val length get() = player.length

    override var rate: Float
        get() = player.rate
        /**
         * libVLC 3 re-bases its clock on a speed change and then finds ~1 s of already-queued audio "too
         * early/late". Seeking to where we are right away throws that stale audio out.
         */
        set(value) {
            if (released || player.rate == value) return
            player.rate = value
            if (player.isPlaying && player.length > 0) player.setTime(player.time, false)
        }

    override fun attach(host: FrameLayout) {
        if (released) return
        val l = layout ?: VLCVideoLayout(context).also { layout = it }
        if (l.parent !== host) {
            (l.parent as? ViewGroup)?.removeView(l)
            host.addView(l, 0, FrameLayout.LayoutParams(-1, -1))
            l.findSurface()?.holder?.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { main.post(::onSurfaceRestored) }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
        if (!player.vlcVout.areViewsAttached()) player.attachViews(l, null, true, false)
        if (!player.isPlaying) player.play()
    }

    override fun play() { if (!released) player.play() }
    override fun pause() { if (!released) player.pause() }
    override fun seek(ms: Long, fast: Boolean) {
        if (released) return
        if (!player.isPlaying && player.length <= 0) { pendingSeek = ms; return }
        player.setTime(ms, fast)
    }

    override fun audioTracks() = player.audioTracks?.map { Track(it.id, it.name) } ?: emptyList()
    override var audioTrack: Int
        get() = player.audioTrack
        set(v) { player.audioTrack = v }
    override fun spuTracks() = player.spuTracks?.map { Track(it.id, it.name) } ?: emptyList()
    override var spuTrack: Int
        get() = player.spuTrack
        set(v) { player.spuTrack = v }

    override fun addSubtitle(file: File) {
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(plainSubtitle(file, style.override, context.cacheDir)), true)
    }

    override var spuDelayMs: Long = 0
        set(v) { field = v; player.spuDelay = v * 1000 } // libVLC wants microseconds
    override var audioDelayMs: Long
        get() = wantAudioDelay
        set(v) { wantAudioDelay = v; if (!released) player.audioDelay = v * 1000 }
    override var volume: Int
        get() = wantVolume
        set(v) { wantVolume = v; if (!released) player.volume = v }

    override fun setEqualizer(preset: Int, bassDb: Int) {
        wantEq = preset to bassDb
        if (audioApplied) applyAudio()
    }

    private fun applyAudio() {
        if (released) return
        player.volume = wantVolume
        player.audioDelay = wantAudioDelay * 1000
        if (wantEq == appliedEq) return
        val hadEq = appliedEq != null && appliedEq != (-1 to 0)
        appliedEq = wantEq
        val (preset, bass) = wantEq
        if (preset < 0 && bass == 0) { if (hadEq) player.setEqualizer(null); return }
        val eq = if (preset >= 0) MediaPlayer.Equalizer.createFromPreset(preset) else MediaPlayer.Equalizer.create()
        val b = bass.toFloat()
        listOf(0 to 1f, 1 to 0.75f, 2 to 0.35f).forEach { (band, k) ->
            eq.setAmp(band, (eq.getAmp(band) + b * k).coerceIn(-20f, 20f))
        }
        if (b > 0) eq.preAmp = (eq.preAmp - b * 0.4f).coerceIn(-20f, 20f) // headroom so boosted bass doesn't clip
        player.setEqualizer(eq)
    }

    /** libVLC reads subtitle styling only when it starts: always needs a restart. */
    override fun setSubStyle(style: SubStyle): Boolean { val same = style == this.style; this.style = style; return same }

    override fun chapters(): List<Chapter> =
        player.getChapters(-1)
            ?.filter { it.timeOffset >= 0 }
            ?.mapIndexed { i, c -> Chapter(c.timeOffset, c.name?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}") }
            ?: emptyList()

    override fun videoInfo(): VideoInfo? {
        val t = runCatching { player.currentVideoTrack }.getOrNull() ?: return null
        if (t.width <= 0 || t.height <= 0) return null
        val rotated = t.orientation == IMedia.VideoTrack.Orientation.RightTop || t.orientation == IMedia.VideoTrack.Orientation.LeftBottom
        val fps = if (t.frameRateDen > 0 && t.frameRateNum > 0) t.frameRateNum.toFloat() / t.frameRateDen else 0f
        return if (rotated) VideoInfo(t.height, t.width, fps) else VideoInfo(t.width, t.height, fps)
    }

    override fun setScale(scale: VideoScale) {
        player.aspectRatio = scale.ratio
        player.videoScale = scale.vlc
    }

    override fun nativeFrameStep() = FrameStepper.next(player)

    override fun setVideoEnabled(enabled: Boolean) { player.setVideoTrackEnabled(enabled) }

    override fun onSurfaceRestored() {
        if (released) return
        val l = layout
        if (l != null && !player.vlcVout.areViewsAttached()) player.attachViews(l, null, true, false)
        restoreVideo()
    }

    /** libVLC only turns the picture back on by itself when playing; a paused video stayed black. */
    private fun restoreVideo() {
        if (released) return
        if (player.videoTrack == -1 && player.videoTracksCount > 0) {
            player.setVideoTrackEnabled(true)
            // Paused: nothing is decoded, so the new surface stays empty. A seek to "here" draws the frame.
            if (!player.isPlaying && player.time > 0) main.postDelayed({
                if (!released && !player.isPlaying) player.time = player.time
            }, 200)
        }
    }

    override fun surfaceView(): SurfaceView? = layout?.findSurface()

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        player.volume = 0
        player.stop()
        player.detachViews()
        player.release()
        if (ownsVlc) libVLC.release()
        layout?.let { (it.parent as? ViewGroup)?.removeView(it) }
        layout = null
        pfd?.close()
        pfd = null
    }

    companion object {
        fun options(s: SubStyle): List<String> {
            val ref = android.content.res.Resources.getSystem().displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
            val base = 0.0625f * ref // libVLC's default subtitle size: 6.25% of the picture height
            return listOf(
                "--audio-time-stretch",
                "--rtsp-tcp",
                "--sub-text-scale=${(s.px * 100f / base).roundToInt().coerceIn(10, 500)}",
                "--sub-margin=${(s.pos * ref).roundToInt()}",
                "--freetype-color=${SUB_COLORS.getOrNull(s.color)?.second ?: 0xFFFFFF}",
                "--freetype-background-opacity=${if (s.background) 170 else 0}",
                "--freetype-background-color=0",
                "--freetype-outline-thickness=${if (s.background) 0 else s.outline * 2}",
                "--freetype-shadow-opacity=${if (s.shadow) 128 else 0}",
            ) + (if (s.bold) listOf("--freetype-bold") else emptyList()) +
                (SUB_FONTS.getOrNull(s.font)?.second?.takeIf { it.isNotEmpty() }?.let { listOf("--freetype-font=$it") } ?: emptyList())
        }

        private fun View.findSurface(): SurfaceView? {
            if (this is SurfaceView) return this
            if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findSurface()?.let { return it }
            return null
        }
    }
}
