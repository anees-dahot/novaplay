package com.novaplay.player.ui.player

import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs

/** Exact one-frame-forward stepping via libVLC's native next_frame. */
object FrameStepper {
    private val loaded = runCatching { System.loadLibrary("novastep") }.isSuccess

    @JvmStatic private external fun nativeTime(mp: Long): Long
    @JvmStatic private external fun nativeNextFrame(mp: Long): Boolean

    /**
     * Steps [player] one frame forward. Only does it after checking that the native handle
     * really is this player (its native time matches the Java one); returns false otherwise.
     */
    fun next(player: MediaPlayer): Boolean {
        if (!loaded) return false
        return runCatching {
            val handle = player.instance
            val nt = nativeTime(handle)
            if (nt < 0 || abs(nt - player.time) > 50) return false
            nativeNextFrame(handle)
        }.getOrDefault(false)
    }
}
