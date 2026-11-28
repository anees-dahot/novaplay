package com.novaplay.player.ui.player.engine

import android.view.SurfaceView
import android.widget.FrameLayout
import com.novaplay.player.ui.player.Chapter
import com.novaplay.player.ui.player.Track
import com.novaplay.player.ui.player.VideoScale
import java.io.File

/** Everything the engine reports back to the player screen. Always called on the main thread. */
interface EngineListener {
    fun onPlaying()
    fun onPaused()
    fun onEnded()
    /** [unsupported]: the engine can't open/decode this file at all (worth retrying with another engine). */
    fun onError(unsupported: Boolean, message: String?)
    fun onTime(ms: Long)
    fun onLength(ms: Long)
    fun onTracksChanged()
    fun onVideoSize()
}

/** Subtitle look, engine independent. */
data class SubStyle(
    val px: Int,
    /** Distance from the bottom as a fraction of the shorter screen side. */
    val pos: Float,
    /** Index into SUB_COLORS. */
    val color: Int,
    val background: Boolean,
    val outline: Int,
    val shadow: Boolean,
    val bold: Boolean,
    val font: Int,
    val override: Boolean,
)

data class VideoInfo(val width: Int, val height: Int, val fps: Float)

/**
 * A playback engine. ExoPlayer is the default (smooth seeking and speed changes); libVLC is the
 * fallback for files ExoPlayer can't open, and the only one that can cast.
 */
interface Engine {
    val isVlc: Boolean
    val isPlaying: Boolean
    val time: Long
    val length: Long
    var rate: Float

    /** Puts the video picture into [host] (called again after the activity gets a new view). */
    fun attach(host: FrameLayout)
    fun play()
    fun pause()
    fun seek(ms: Long, fast: Boolean)

    fun audioTracks(): List<Track>
    var audioTrack: Int
    fun spuTracks(): List<Track>
    var spuTrack: Int
    /** Adds a subtitle file and selects it. */
    fun addSubtitle(file: File)

    /** Positive = subtitles later. */
    var spuDelayMs: Long
    /** Positive = sound later. */
    var audioDelayMs: Long
    /** 0..200 percent. */
    var volume: Int
    fun setEqualizer(preset: Int, bassDb: Int)
    /** Applies subtitle look; returns false if the engine needs a restart to show it. */
    fun setSubStyle(style: SubStyle): Boolean

    fun chapters(): List<Chapter>
    fun videoInfo(): VideoInfo?
    fun setScale(scale: VideoScale)
    /** Exact one-frame step forward done by the engine itself; false = caller seeks instead. */
    fun nativeFrameStep(): Boolean
    fun setVideoEnabled(enabled: Boolean)
    /** The surface came back (e.g. returning from another app). */
    fun onSurfaceRestored()
    fun surfaceView(): SurfaceView?
    fun release()
}

/** libVLC's equalizer presets (same order as libVLC, so VLC can use its own by index). 10 bands. */
object EqPresets {
    val bandsHz = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    val presets: List<Pair<String, FloatArray>> = listOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f),
        "Club" to floatArrayOf(0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f),
        "Dance" to floatArrayOf(9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f),
        "Full bass" to floatArrayOf(-8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f),
        "Full bass and treble" to floatArrayOf(7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f),
        "Full treble" to floatArrayOf(-9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 16f, 16f, 16f, 16.8f),
        "Headphones" to floatArrayOf(4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12.8f, 14.4f),
        "Large Hall" to floatArrayOf(10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f),
        "Live" to floatArrayOf(-4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f),
        "Party" to floatArrayOf(7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f),
        "Pop" to floatArrayOf(-1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f),
        "Reggae" to floatArrayOf(0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f),
        "Ska" to floatArrayOf(-2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f),
        "Soft" to floatArrayOf(4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f),
        "Soft rock" to floatArrayOf(4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f),
        "Techno" to floatArrayOf(8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f),
    )
    val names get() = presets.map { it.first }

    /** Gains (dB) for [preset] (‑1 = none) with bass boost lifted onto the lowest bands. */
    fun gains(preset: Int, bassDb: Int): FloatArray {
        val g = presets.getOrNull(preset)?.second?.copyOf() ?: FloatArray(10)
        val b = bassDb.toFloat()
        listOf(0 to 1f, 1 to 0.75f, 2 to 0.35f).forEach { (band, k) -> g[band] = (g[band] + b * k).coerceIn(-20f, 20f) }
        return g
    }

    /** Gain at [hz], interpolated on a log-frequency scale. */
    fun gainAt(gains: FloatArray, hz: Float): Float {
        if (hz <= bandsHz.first()) return gains.first()
        if (hz >= bandsHz.last()) return gains.last()
        val i = bandsHz.indexOfFirst { it >= hz }
        val lo = kotlin.math.ln(bandsHz[i - 1]); val hi = kotlin.math.ln(bandsHz[i])
        val t = (kotlin.math.ln(hz) - lo) / (hi - lo)
        return gains[i - 1] + (gains[i] - gains[i - 1]) * t
    }
}
