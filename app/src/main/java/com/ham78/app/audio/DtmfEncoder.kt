package com.ham78.app.audio

/**
 * DTMF（双音多频）编码器：把数字串转成电话拨号音序列。
 *
 * 每个按键由一个低频组音 + 一个高频组音叠加构成（ITU-T Q.23）。
 * 常见用途：中继台远程控制命令、节点连接/断开（如 IRLP/AllStar 节点号）。
 *
 * 调用示例：
 *   val pcm = DtmfEncoder.encode("*1234#")
 *   AudioManager.transmitPcm(pcm)
 */
object DtmfEncoder {

    /** 单音持续时间与间隔：150ms 音 + 50ms 静音，拨号节奏清晰可辨 */
    private const val TONE_MS = 150
    private const val GAP_MS = 50

    /** 双音幅度各取一半，叠加后不超出满幅 */
    private const val AMPLITUDE = 0.45f

    /** DTMF 频率表：按键 → (低频组 Hz, 高频组 Hz) */
    private val FREQUENCIES = mapOf(
        '1' to (697 to 1209), '2' to (697 to 1336), '3' to (697 to 1477),
        '4' to (770 to 1209), '5' to (770 to 1336), '6' to (770 to 1477),
        '7' to (852 to 1209), '8' to (852 to 1336), '9' to (852 to 1477),
        '*' to (941 to 1209), '0' to (941 to 1336), '#' to (941 to 1477),
        'A' to (697 to 1633), 'B' to (770 to 1633), 'C' to (852 to 1633), 'D' to (941 to 1633)
    )

    /**
     * 把 DTMF 字符串编码为音频 PCM。
     * 只接受 0-9 * # A-D，其余字符跳过。
     */
    fun encode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        text.uppercase().forEach { c ->
            val freq = FREQUENCIES[c] ?: return@forEach

            // 双音叠加：两个正弦各 0.45 幅度，峰值之和不超过满幅
            val sampleCount = TONE_MS * Tone.SAMPLE_RATE / 1000
            val pcm = ByteArray(sampleCount * 2)
            val stepLo = 2.0 * Math.PI * freq.first / Tone.SAMPLE_RATE
            val stepHi = 2.0 * Math.PI * freq.second / Tone.SAMPLE_RATE

            for (i in 0 until sampleCount) {
                val sample = ((Math.sin(stepLo * i) + Math.sin(stepHi * i)) *
                    AMPLITUDE * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
                pcm[i * 2] = (sample and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            out.write(pcm)
            out.write(Tone.silence(GAP_MS))
        }
        return out.toByteArray()
    }
}
