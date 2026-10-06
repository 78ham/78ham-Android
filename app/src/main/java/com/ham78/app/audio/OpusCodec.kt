package com.ham78.app.audio

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException

/**
 * Opus 编解码器封装
 * 基于 Concentus 纯 Java 实现，参数与项目音频管线对齐：
 * 8kHz / 16bit / 单声道，20ms 帧（160 采样点）。
 *
 * Opus 内部会将输入重采样至 48kHz 处理，8000Hz 为其支持的合法输入采样率。
 * 语音场景使用 VOIP 模式，窄带码率约 12kbps，远低于 G.711 的 64kbps。
 */
class OpusCodec {

    companion object {
        private const val TAG = "OpusCodec"
        private const val SAMPLE_RATE = AudioRecorder.SAMPLE_RATE // 8000
        private const val CHANNELS = 1
        private const val FRAME_SAMPLES = AudioRecorder.SAMPLES_PER_FRAME // 160 (20ms)
        private const val BITRATE_BPS = 12000
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
     * 将一帧 PCM（小端 16bit，320 字节 = 20ms）编码为 Opus 包。
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
