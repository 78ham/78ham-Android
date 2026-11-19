package com.ham78.app.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * 信号音发生器：按频率和时长生成 8kHz/16bit/单声道小端 PCM。
 *
 * 所有音频信号（CW/DTMF/尾音/静音垫片）都从这一个源头生成，
 * 保证采样率与整条语音管线一致，能直接喂给 AudioManager.transmitPcm。
 *
 * 调用示例：
 *   val tone = Tone.sine(hz = 1000, ms = 250)        // 1kHz 250ms 正弦
 *   val gap  = Tone.silence(ms = 100)                 // 100ms 静音
 *   val slide = Tone.sweep(fromHz = 1500, toHz = 500, ms = 300)  // 降调滑音
 */
object Tone {

    /** 全管线统一的音频参数：8kHz 采样、16bit 小端、单声道 */
    const val SAMPLE_RATE = AudioRecorder.SAMPLE_RATE
    const val BYTES_PER_SAMPLE = 2

    /**
     * 生成一段正弦音。
     * amplitude 取 0..1，1 = 满幅 ±32767；信号音统一用 0.6，
     * 留出余量避免编码后削顶失真。
     */
    fun sine(hz: Int, ms: Int, amplitude: Float = 0.6f): ByteArray {
        val sampleCount = ms * SAMPLE_RATE / 1000
        val pcm = ByteArray(sampleCount * BYTES_PER_SAMPLE)
        val step = 2.0 * PI * hz / SAMPLE_RATE

        for (i in 0 until sampleCount) {
            val sample = (sin(step * i) * amplitude * Short.MAX_VALUE).toInt()
            writeSample(pcm, i, sample)
        }
        return pcm
    }

    /**
     * 生成一段从 fromHz 滑到 toHz 的扫频音（线性变频）。
     * 用于双音尾音这类"频率连续变化"的提示音。
     */
    fun sweep(fromHz: Int, toHz: Int, ms: Int, amplitude: Float = 0.6f): ByteArray {
        val sampleCount = ms * SAMPLE_RATE / 1000
        val pcm = ByteArray(sampleCount * BYTES_PER_SAMPLE)

        // 相位累加生成：每前进一步相位增加 2π·f/SR，f 随时间线性变化
        var phase = 0.0
        for (i in 0 until sampleCount) {
            val progress = i.toDouble() / sampleCount
            val hz = fromHz + (toHz - fromHz) * progress
            val sample = (sin(phase) * amplitude * Short.MAX_VALUE).toInt()
            writeSample(pcm, i, sample)
            phase += 2.0 * PI * hz / SAMPLE_RATE
        }
        return pcm
    }

    /** 生成一段数字静音（占位用）：CW 字符间隔、DTMF 位间隔都靠它垫时长 */
    fun silence(ms: Int): ByteArray = ByteArray(ms * SAMPLE_RATE / 1000 * BYTES_PER_SAMPLE)

    /** 把一个 16bit 采样按小端写入 PCM 缓冲的第 index 个采样位 */
    private fun writeSample(pcm: ByteArray, index: Int, sample: Int) {
        val clipped = sample.coerceIn(-32768, 32767)
        pcm[index * 2] = (clipped and 0xFF).toByte()
        pcm[index * 2 + 1] = ((clipped shr 8) and 0xFF).toByte()
    }
}
