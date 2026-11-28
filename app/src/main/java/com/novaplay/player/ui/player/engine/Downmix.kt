package com.novaplay.player.ui.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Surround (5.1 / 7.1) → stereo downmix with louder dialogue.
 *
 * Left to Android, multichannel audio is downmixed with every channel scaled down so it can never
 * clip, which leaves films (where speech sits in the centre channel) much quieter than stereo
 * files. This mixes at full level (centre and surrounds at −3 dB, LFE at −6 dB) and soft-limits
 * the rare peaks instead, the way desktop players do.
 */
@UnstableApi
class Downmix : BaseAudioProcessor() {
    private var channels = 0
    private var float = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (inputAudioFormat.channelCount !in 3..8 || (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT))
            return AudioProcessor.AudioFormat.NOT_SET // inactive: pass through untouched
        channels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, enc)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inBytes = if (float) 4 else 2
        val frames = inputBuffer.remaining() / (channels * inBytes)
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }
        val out = replaceOutputBuffer(frames * 2 * inBytes)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val s = FloatArray(channels)
        repeat(frames) {
            for (ch in 0 until channels) {
                s[ch] = if (float) src.getFloat() else src.getShort() / 32768f
            }
            mix(s)
            val l = outL; val r = outR
            if (float) {
                out.putFloat(limit(l)); out.putFloat(limit(r))
            } else {
                out.putShort((limit(l) * 32767f).toInt().toShort())
                out.putShort((limit(r) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    /** Android channel order: FL FR FC LFE [BL BR] [SL SR]; 3/4-channel layouts skip what they lack. */
    private var outL = 0f
    private var outR = 0f

    private fun mix(s: FloatArray) {
        val c = 0.7071f
        var l = s[0]
        var r = s[1]
        if (channels >= 3) { l += c * s[2]; r += c * s[2] }
        if (channels >= 4) { l += 0.5f * s[3]; r += 0.5f * s[3] }
        if (channels >= 6) { l += c * s[4]; r += c * s[5] }
        else if (channels == 5) { l += c * s[4]; r += c * s[4] }
        if (channels >= 8) { l += c * s[6]; r += c * s[7] }
        else if (channels == 7) { l += c * s[6]; r += c * s[6] }
        outL = l; outR = r
    }

    /** Transparent below 0.85, then curves smoothly towards full scale instead of hard clipping. */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.85f) return x
        val y = 0.85f + 0.15f * tanh((a - 0.85f) / 0.15f)
        return if (x < 0) -y else y
    }
}
