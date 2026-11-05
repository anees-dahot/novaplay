package com.novaplay.player.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.videolan.libvlc.MediaPlayer

data class Track(val id: Int, val name: String)
data class Chapter(val timeMs: Long, val name: String)

/** Level HUD shown while swiping brightness/volume. */
data class Hud(val volume: Boolean, val level: Float)

enum class VideoScale(val label: String, val vlc: MediaPlayer.ScaleType, val ratio: String? = null) {
    FIT("Fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT),
    CROP("Crop", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN),
    STRETCH("Stretch", MediaPlayer.ScaleType.SURFACE_FILL),
    R16_9("16:9", MediaPlayer.ScaleType.SURFACE_BEST_FIT, "16:9"),
    R4_3("4:3", MediaPlayer.ScaleType.SURFACE_BEST_FIT, "4:3"),
    R21_9("21:9", MediaPlayer.ScaleType.SURFACE_BEST_FIT, "21:9"),
    ORIGINAL("100%", MediaPlayer.ScaleType.SURFACE_ORIGINAL),
}

/** Subtitle colours offered in the style picker (RGB). */
val SUB_COLORS = listOf("White" to 0xFFFFFF, "Yellow" to 0xFFE14D, "Cyan" to 0x5AD8FF, "Green" to 0x7CFC8A)

enum class LoopMode(val label: String) { OFF("Loop off"), ONE("Loop this video"), ALL("Loop all") }

/** Everything the player UI shows. Updated by PlayerActivity from libVLC events. */
class PlayerState {
    var playing by mutableStateOf(false)
    /** False until the first frame time arrives; shows a loading spinner. */
    var started by mutableStateOf(false)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    /** True while the user drags the seek bar or swipes to seek; blocks time updates from libVLC. */
    var seeking by mutableStateOf(false)

    var controlsVisible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var rate by mutableFloatStateOf(1f)
    var holdingFast by mutableStateOf(false)
    var scale by mutableStateOf(VideoScale.FIT)

    var audioTracks by mutableStateOf<List<Track>>(emptyList())
    var spuTracks by mutableStateOf<List<Track>>(emptyList())
    var audioTrack by mutableIntStateOf(-1)
    var spuTrack by mutableIntStateOf(-1)
    var spuDelayMs by mutableLongStateOf(0L)
    var subScale by mutableIntStateOf(100)

    var chapters by mutableStateOf<List<Chapter>>(emptyList())
    var abStart by mutableLongStateOf(-1L)
    var abEnd by mutableLongStateOf(-1L)
    var loopMode by mutableStateOf(LoopMode.OFF)
    var hwDecoder by mutableStateOf(true)
    var backgroundPlay by mutableStateOf(false)
    var autoNext by mutableStateOf(true)
    var nightMode by mutableStateOf(false)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)

    /** Current video title + position in playlist; state-backed so next/prev updates the UI. */
    var title by mutableStateOf("")
    var playlistIndex by mutableIntStateOf(0)
    var playlistSize by mutableIntStateOf(1)

    /** Free pinch zoom (1 = off) and pan in px, applied to the video view. */
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    var shuffle by mutableStateOf(false)
    /** Sleep timer: wall-clock end time, or -1 off. [sleepAtEnd] = stop when this video ends. */
    var sleepAt by mutableLongStateOf(-1L)
    var sleepAtEnd by mutableStateOf(false)

    // Audio
    var audioDelayMs by mutableLongStateOf(0L)
    var volumeBoost by mutableIntStateOf(100)
    var eqPreset by mutableIntStateOf(-1)
    var bassDb by mutableIntStateOf(0)
    var eqPresets by mutableStateOf<List<String>>(emptyList())

    // Subtitle look
    var subColor by mutableIntStateOf(0)
    var subBackground by mutableStateOf(false)
    var subBold by mutableStateOf(false)

    // Online subtitles (OpenSubtitles)
    var onlineSubs by mutableStateOf<List<com.novaplay.player.subs.OnlineSub>>(emptyList())
    var onlineBusy by mutableStateOf(false)
    var onlineError by mutableStateOf<String?>(null)
    var onlineSearched by mutableStateOf(false)
    /** Bumped when a downloaded subtitle is loaded, so the sheet can close. */
    var onlineLoaded by mutableIntStateOf(0)

    // Casting
    var renderers by mutableStateOf<List<String>>(emptyList())
    var castingTo by mutableStateOf<String?>(null)

    var gestureText by mutableStateOf<String?>(null)
    /** Where the feedback bubble sits: -1 left half, 0 center, 1 right half (double-tap seeks). */
    var flashSide by mutableIntStateOf(0)
    var hud by mutableStateOf<Hud?>(null)
    var gestureTick by mutableIntStateOf(0)
    /** Bumped on any user interaction so the auto-hide timer restarts. */
    var interaction by mutableIntStateOf(0)

    fun flash(text: String, side: Int = 0) {
        gestureText = text
        flashSide = side
        gestureTick++
    }

    fun bump() {
        interaction++
    }

    /** Clears per-video state when switching to another video. */
    fun resetForNewVideo() {
        time = 0; length = 0; playing = false; started = false
        audioTracks = emptyList(); spuTracks = emptyList()
        audioTrack = -1; spuTrack = -1; spuDelayMs = 0
        chapters = emptyList(); abStart = -1; abEnd = -1
        zoom = 1f; panX = 0f; panY = 0f
        castingTo = null
        onlineSubs = emptyList(); onlineError = null; onlineSearched = false
    }
}

interface PlayerController {
    val videoTitle: String
    fun createVideoView(context: Context): View
    fun togglePlay()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun setRate(rate: Float)
    fun startHoldFast()
    fun stopHoldFast()
    fun setScale(scale: VideoScale)
    fun selectAudio(id: Int)
    fun selectSpu(id: Int)
    fun pickSubtitleFile()
    fun shiftSpuDelay(deltaMs: Long)
    fun setSubScale(percent: Int)
    fun playPrev()
    fun playNext()
    fun enterPip()
    fun cycleAbRepeat()
    fun setLoopMode(mode: LoopMode)
    fun setHwDecoder(enabled: Boolean)
    fun setBackgroundPlay(enabled: Boolean)
    fun setAutoNext(enabled: Boolean)
    fun setNightMode(enabled: Boolean)
    fun takeScreenshot()
    fun setShuffle(enabled: Boolean)
    /** minutes > 0 = timer, 0 = off, -1 = end of current video. */
    fun setSleep(minutes: Int)
    fun frameStep(forward: Boolean)
    fun shiftAudioDelay(deltaMs: Long)
    fun setVolumeBoost(percent: Int)
    fun setEqPreset(index: Int)
    fun setBass(db: Int)
    fun setSubStyle(color: Int, background: Boolean, bold: Boolean)
    /** Title guess used to prefill the online subtitle search. */
    fun subtitleQuery(): String
    fun searchOnlineSubs(query: String)
    fun downloadOnlineSub(sub: com.novaplay.player.subs.OnlineSub)
    fun startCastScan()
    fun stopCastScan()
    /** null = stop casting, play on this phone. */
    fun castTo(name: String?)
    /** Frame at [ms] for the seek-bar preview, or null if it can't be read. */
    suspend fun previewFrame(ms: Long): Bitmap?
    /** delta is a fraction of screen height; returns new brightness in percent. */
    fun adjustBrightness(delta: Float): Int
    /** delta is a fraction of screen height; returns new volume in percent. */
    fun adjustVolume(delta: Float): Int
    fun endGesture()
    fun toggleRotation()
    fun back()
}
