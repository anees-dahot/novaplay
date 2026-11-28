package com.novaplay.player.subs

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.novaplay.player.ui.player.engine.SubCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lines subtitles up with the movie's sound, like ffsubsync / alass do on desktop:
 *
 * 1. Decode a few minutes of audio from several spots in the video and mark every 50 ms frame as
 *    "someone talks" or not (energy in the voice band, against that spot's own loudness).
 * 2. Turn each subtitle into the same on/off track, and slide it (and stretch it, for files made for
 *    a 25 vs 23.976 fps release) until it correlates best with the speech.
 *
 * The correlation tells which of several downloaded subtitles fits this file best, and the slide is
 * the shift that makes it fit.
 */
object SubSync {
    private const val FRAME_MS = 50L
    private const val WINDOW_MS = 150_000L
    private const val MAX_SHIFT_MS = 60_000L
    /** Frame-rate mix-ups between releases: same, 25↔23.976, 24↔23.976, 25↔24. */
    private val SCALES = doubleArrayOf(1.0, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25.0 / 24, 24.0 / 25)

    /** Speech flags for one decoded stretch of the video, starting at [startMs]. */
    class Window(val startMs: Long, val speech: BooleanArray)

    class Speech(val windows: List<Window>)

    /** How well a subtitle fits: Pearson correlation with the speech, after shifting/stretching. */
    data class Fit(val score: Double, val offsetMs: Long, val scale: Double, val scoreUnshifted: Double) {
        /** Clear enough to trust the shift. */
        val confident get() = score >= 0.12 && score - scoreUnshifted >= 0.03
    }

    private var cacheKey: String? = null
    private var cache: Speech? = null

    /** Decodes and analyses the sound of [uri] (cached for the last video). */
    suspend fun speech(context: Context, uri: Uri, lengthMs: Long): Speech? = withContext(Dispatchers.Default) {
        val key = uri.toString()
        if (key == cacheKey) cache?.let { return@withContext it }
        val s = runCatching { analyse(context, uri, lengthMs) }.getOrNull()
        if (s != null && s.windows.isNotEmpty()) { cacheKey = key; cache = s }
        s
    }

    private suspend fun analyse(context: Context, uri: Uri, lengthMs: Long): Speech? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val dur = if (lengthMs > 0) lengthMs else if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (dur < 60_000) return null
            // Short videos: the whole thing; otherwise three spots spread through it.
            val starts = if (dur <= WINDOW_MS * 3) listOf(0L) else listOf(0.12, 0.45, 0.75).map { (dur * it).toLong() }
            val len = if (dur <= WINDOW_MS * 3) dur else WINDOW_MS
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val windows = starts.mapNotNull { st ->
                    coroutineContext.ensureActive()
                    decodeWindow(ex, codec, fmt, st, len)?.let { Window(st, vad(it)) }
                }
                return Speech(windows)
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
        } finally {
            ex.release()
        }
    }

    /** Voice-band log energy per 50 ms frame for [startMs, startMs+lenMs). */
    private suspend fun decodeWindow(ex: MediaExtractor, codec: MediaCodec, inFmt: MediaFormat, startMs: Long, lenMs: Long): DoubleArray? {
        val frames = (lenMs / FRAME_MS).toInt()
        val energy = DoubleArray(frames)
        val counts = IntArray(frames)
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        val endUs = (startMs + lenMs) * 1000
        var inputDone = false
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        val info = MediaCodec.BufferInfo()
        // Band-pass ~300–3400 Hz (one-pole high-pass + low-pass): where speech lives, less bass/music.
        var hpPrevIn = 0.0; var hpPrevOut = 0.0; var lp = 0.0
        var idle = 0
        while (true) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(1_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0 || ex.sampleTime > endUs) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            // Don't block on output while there's still input to feed: each wait would cost a whole frame.
            val o = codec.dequeueOutputBuffer(info, if (inputDone) 5_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                o >= 0 -> {
                    idle = 0
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val dt = 1.0 / rate
                    val aHp = 1.0 / (1.0 + 2 * Math.PI * 300 * dt)
                    val aLp = (2 * Math.PI * 3400 * dt) / (1.0 + 2 * Math.PI * 3400 * dt)
                    var t = info.presentationTimeUs / 1000.0 - startMs
                    val step = 1000.0 / rate
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        val n = fb.remaining() / channels
                        for (k in 0 until n) {
                            var s = 0.0
                            for (c in 0 until channels) s += fb.get()
                            s /= channels
                            val hp = aHp * (hpPrevOut + s - hpPrevIn); hpPrevIn = s; hpPrevOut = hp
                            lp += aLp * (hp - lp)
                            val fi = (t / FRAME_MS).toInt()
                            if (fi in 0 until frames) { energy[fi] += lp * lp; counts[fi]++ }
                            t += step
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        val n = sb.remaining() / channels
                        for (k in 0 until n) {
                            var s = 0.0
                            for (c in 0 until channels) s += sb.get()
                            s /= channels * 32768.0
                            val hp = aHp * (hpPrevOut + s - hpPrevIn); hpPrevIn = s; hpPrevOut = hp
                            lp += aLp * (hp - lp)
                            val fi = (t / FRAME_MS).toInt()
                            if (fi in 0 until frames) { energy[fi] += lp * lp; counts[fi]++ }
                            t += step
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> if (inputDone && ++idle > 400) break // decoder stuck: use what we have
            }
        }
        if (counts.count { it > 0 } < frames / 3) return null
        return DoubleArray(frames) { if (counts[it] > 0) ln(energy[it] / counts[it] + 1e-10) else Double.NaN }
    }

    /** Talking = louder than this window's own typical level (adapts to quiet and loud films). */
    private fun vad(logE: DoubleArray): BooleanArray {
        val valid = logE.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return BooleanArray(logE.size)
        val p20 = valid[(valid.size * 0.2).toInt()]
        val p90 = valid[(valid.size * 0.9).toInt().coerceAtMost(valid.size - 1)]
        val thr = p20 + (p90 - p20) * 0.45
        // Smooth over ~150 ms so single-frame clicks or pauses between words don't flip it.
        val raw = BooleanArray(logE.size) { !logE[it].isNaN() && logE[it] > thr }
        return BooleanArray(raw.size) { i ->
            var on = 0
            for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(raw.size - 1)) if (raw[j]) on++
            on >= 3
        }
    }

    /** Best shift/stretch of [cues] against [speech]. */
    suspend fun fit(speech: Speech, cues: List<SubCue>): Fit? = withContext(Dispatchers.Default) {
        if (cues.isEmpty() || speech.windows.isEmpty()) return@withContext null
        val lastMs = cues.maxOf { it.end }
        val totalFrames = (lastMs / FRAME_MS + 1).toInt()
        // Subtitle on/off per frame of the subtitle's own timeline.
        val sub = BooleanArray(totalFrames)
        cues.forEach { c ->
            val a = (c.start / FRAME_MS).toInt().coerceIn(0, totalFrames - 1)
            val b = (c.end / FRAME_MS).toInt().coerceIn(0, totalFrames - 1)
            for (f in a..b) sub[f] = true
        }
        val maxShift = (MAX_SHIFT_MS / FRAME_MS).toInt()
        var best: Fit? = null
        var unshifted = Double.NaN
        for (scale in SCALES) {
            ensureActive()
            // Coarse first (every 2 frames), then refine around the best.
            var bestOff = 0; var bestScore = Double.NEGATIVE_INFINITY
            var off = -maxShift
            while (off <= maxShift) {
                val s = corr(speech, sub, off, scale)
                if (s > bestScore) { bestScore = s; bestOff = off }
                off += 2
            }
            for (o in (bestOff - 1)..(bestOff + 1)) { val s = corr(speech, sub, o, scale); if (s > bestScore) { bestScore = s; bestOff = o } }
            if (scale == 1.0) unshifted = corr(speech, sub, 0, 1.0)
            if (best == null || bestScore > best.score + 0.01) best = Fit(bestScore, bestOff * FRAME_MS, scale, 0.0)
        }
        best?.copy(scoreUnshifted = unshifted.takeIf { !it.isNaN() } ?: 0.0)
    }

    /** Pearson correlation between speech and the subtitle shown at t·scale + off (frames). */
    private fun corr(speech: Speech, sub: BooleanArray, off: Int, scale: Double): Double {
        var n = 0L; var sv = 0L; var ss = 0L; var svs = 0L
        val inv = 1.0 / scale
        for (w in speech.windows) {
            val base = (w.startMs / FRAME_MS).toInt()
            val sp = w.speech
            for (i in sp.indices) {
                // Media frame (base+i) shows subtitle frame ((base+i) - off) / scale.
                val sf = ((base + i - off) * inv).toInt()
                val s = sf in sub.indices && sub[sf]
                val v = sp[i]
                n++
                if (v) sv++
                if (s) { ss++; if (v) svs++ }
            }
        }
        if (n == 0L) return 0.0
        val num = n.toDouble() * svs - sv.toDouble() * ss
        val den = sqrt((n.toDouble() * sv - sv.toDouble() * sv) * (n.toDouble() * ss - ss.toDouble() * ss))
        return if (den <= 0) 0.0 else num / den
    }
}
