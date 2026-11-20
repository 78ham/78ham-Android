package com.ham78.app.audio

/**
 * CW（摩尔斯电码）编码器：把文本转成点/划/间隔的标准音频序列。
 *
 * 标准时序以"点"为单位（dit）：划=3点、字符内间隔=1点、字符间=3点、词间=7点。
 * WPM（每分钟词数）决定点的长度：PARIS 标准下 点(ms) = 1200 / WPM。
 * 音频用 600Hz 正弦——业余无线电 CW 的传统中心频率。
 *
 * 调用示例：
 *   val pcm = CwEncoder.encode("CQ CQ DE BH1ABC")   // 整段 CW 音频
 *   AudioManager.transmitPcm(pcm)                    // 经音频管线发出
 */
object CwEncoder {

    /** CW 音调频率：600Hz 是业余无线电通联的传统中心频率 */
    private const val TONE_HZ = 600

    /** CW 信号幅度：稍低于语音，电键声更清脆 */
    private const val AMPLITUDE = 0.55f

    /** 国际摩尔斯电码表：字符 → 点划串（. = 点，- = 划） */
    private val MORSE = mapOf(
        'A' to ".-",    'B' to "-...",  'C' to "-.-.",  'D' to "-..",
        'E' to ".",     'F' to "..-.",  'G' to "--.",   'H' to "....",
        'I' to "..",    'J' to ".---",  'K' to "-.-",   'L' to ".-..",
        'M' to "--",    'N' to "-.",    'O' to "---",   'P' to ".--.",
        'Q' to "--.-",  'R' to ".-.",   'S' to "...",   'T' to "-",
        'U' to "..-",   'V' to "...-",  'W' to ".--",   'X' to "-..-",
        'Y' to "-.--",  'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '/' to "-..-.",
        '=' to "-...-",  '-' to "-....-", '@' to ".--.-.", ':' to "---..."
    )

    /** 已知缩略语：作为整词出现时用专门的紧凑码（AS/AR/SK/KN），无对应缩写则逐字发 */
    private val PROSIGN_WORDS = mapOf(
        "AS" to ".-...",   // 等一下（Wait）
        "AR" to ".-.-.",   // 本条结束（End of message）
        "SK" to "...-.-",  // 通联结束（End of contact）
        "KN" to "-.--.",   // 只对指定台发送（Over to named station）
        "BT" to "-...-",   // 分隔符（Break）
        "SOS" to "...---..."
    )

    /**
     * 把文本编码为 CW 音频 PCM。
     * 未识别的字符跳过；大小写不敏感；空格序列决定词间隔。
     */
    fun encode(text: String, wpm: Int = 15): ByteArray {
        val ditMs = 1200 / wpm.coerceIn(5, 35)          // PARIS 标准：点长 = 1200/WPM
        val dahMs = ditMs * 3
        val out = java.io.ByteArrayOutputStream()

        val words = text.uppercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        words.forEachIndexed { wordIdx, word ->
            // 整词命中缩略语：发一个专用 prosign（字符间不留 3 点间隔）
            val prosign = PROSIGN_WORDS[word]
            if (prosign != null) {
                appendSymbol(out, prosign, ditMs, dahMs)
            } else {
                word.forEachIndexed { charIdx, c ->
                    val pattern = MORSE[c] ?: return@forEachIndexed   // 不认识的字符跳过
                    if (charIdx > 0) out.write(silence(ditMs * 3)) // 字符间隔 3 点
                    appendSymbol(out, pattern, ditMs, dahMs)
                }
            }
            if (wordIdx < words.size - 1) out.write(silence(ditMs * 7)) // 词间隔 7 点
        }
        return out.toByteArray()
    }

    /** 把一串点划（一个字符或 prosign）转成音频写入输出流 */
    private fun appendSymbol(out: java.io.ByteArrayOutputStream, pattern: String, ditMs: Int, dahMs: Int) {
        pattern.forEachIndexed { i, ch ->
            if (i > 0) out.write(silence(ditMs))            // 符号内间隔 1 点
            when (ch) {
                '.' -> out.write(Tone.sine(TONE_HZ, ditMs, AMPLITUDE))
                '-' -> out.write(Tone.sine(TONE_HZ, dahMs, AMPLITUDE))
            }
        }
    }

    /**
     * 字符间隔的静音块：同一段文本里长度都一样，按长度缓存复用，
     * 避免长文本反复分配同样大小的静音数组。
     */
    private val silenceCache = HashMap<Int, ByteArray>()

    /** 取一段指定长度的静音（带缓存） */
    private fun silence(ms: Int): ByteArray =
        silenceCache.getOrPut(ms) { Tone.silence(ms) }
}
