package com.ham78.app.audio

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException

/**
 * Opus 编解码器封装
 * 基于 Concentus 纯 Java 实现，参数与微信小程序/桌面端对齐：
 * 16kHz / 16bit / 单声道，20ms 帧（320 采样点）。
 *
 * 【关键】Opus 必须用 16kHz（小程序 audioOpus.js 与桌面端 codec.py 都是 16000）：
 * 8kHz 编出来的 Opus 包在它们那边解出来全是噪声，实时语音互相听不到。
 * G.711 分支仍是 8kHz，两套采样率在 AudioManager 里按编码切换。
 */
class OpusCodec {

    companion object {
        private const val TAG = "OpusCodec"
        const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        const val FRAME_SAMPLES = SAMPLE_RATE * AudioRecorder.FRAME_MS / 1000 // 320 (20ms)
        const val BITRATE_BPS = 40000 // 与小程序 OPUS_BITRATE 一致的宽带码率
        private const val COMPLEXITY = 5 // 移动端取中等复杂度，降低 CPU 占用
        private const val MAX_PACKET_BYTES = 1275 // Opus 单包上限
    }

    private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(BITRATE_BPS)
        setComplexity(COMPLEXITY)
    }

    private val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

    private val encodePcmBuffer = ShortArray(FRAME_SAMPLES)
    private val encodeOutBuffer = ByteArray(MAX_PACKET_BYTES)
    private val decodeOutBuffer = ShortArray(FRAME_SAMPLES * 2)

    private val encodeLock = Any()
    private val decodeLock = Any()

    /**
     * 将一帧 PCM（16kHz 下 640 字节 = 20ms）编码为 Opus 包。
     * 输入不足一帧时末尾补零对齐。采用直接位移转换，零 ByteBuffer 分配。
     */
    fun encode(pcmBytes: ByteArray): ByteArray {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount <= 0) return ByteArray(0)

        return synchronized(encodeLock) {
            try {
                val toConvert = minOf(sampleCount, FRAME_SAMPLES)
                for (i in 0 until toConvert) {
                    val lo = pcmBytes[i * 2].toInt() and 0xFF
                    val hi = pcmBytes[i * 2 + 1].toInt()
                    encodePcmBuffer[i] = ((hi shl 8) or lo).toShort()
                }
                if (toConvert < FRAME_SAMPLES) {
                    encodePcmBuffer.fill(0, toConvert, FRAME_SAMPLES)
                }

                val len = encoder.encode(encodePcmBuffer, 0, FRAME_SAMPLES, encodeOutBuffer, 0, MAX_PACKET_BYTES)
                encodeOutBuffer.copyOf(len)
            } catch (e: OpusException) {
                Log.e(TAG, "Opus encode failed", e)
                ByteArray(0)
            }
        }
    }

    /**
     * 将一个 Opus 包解码为 PCM（小端 16bit 字节）。
     * 采用直接位移写入输出数组，零 ByteBuffer 分配。
     * 包损坏时返回空数组，调用方按丢帧处理。
     */
    fun decode(opusBytes: ByteArray): ByteArray {
        if (opusBytes.isEmpty()) return ByteArray(0)

        return synchronized(decodeLock) {
            try {
                val samples = decoder.decode(opusBytes, 0, opusBytes.size, decodeOutBuffer, 0, FRAME_SAMPLES, false)
                if (samples <= 0) return@synchronized ByteArray(0)

                val out = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val s = decodeOutBuffer[i].toInt()
                    out[i * 2] = (s and 0xFF).toByte()
                    out[i * 2 + 1] = (s shr 8).toByte()
                }
                out
            } catch (e: OpusException) {
                Log.w(TAG, "Opus decode failed: ${e.message}")
                ByteArray(0)
            }
        }
    }
}
