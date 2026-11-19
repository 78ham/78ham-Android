package com.ham78.app.audio

import com.ham78.app.data.RogerBeepStyle

/**
 * 尾音（Roger Beep）：松开 PTT 后追加的结束提示音。
 *
 * 各样式的音频定义集中在这里，TalkService 只需调用 [encode] 拿 PCM。
 * 每种样式前都垫一小段静音，把尾音和最后的语音帧隔开，听感更干净。
 *
 * 调用示例：
 *   val pcm = RogerBeep.encode(RogerBeepStyle.CLASSIC)
 *   AudioManager.transmitPcm(pcm)
 */
object RogerBeep {

    /** 尾音前垫 80ms 静音：与最后的语音帧拉开，避免粘连 */
    private const val LEAD_SILENCE_MS = 80

    /** 按样式生成尾音 PCM；NONE 返回空数组，调用方按"不发"处理 */
    fun encode(style: RogerBeepStyle): ByteArray = when (style) {
        RogerBeepStyle.NONE -> ByteArray(0)
        RogerBeepStyle.CLASSIC ->
            Tone.sine(1000, 250)
        RogerBeepStyle.K ->
            // 摩尔斯 K（─·─）：划 + 点 + 划，对讲机里俗称"over"音
            Tone.sine(800, 180) + Tone.silence(60) +
            Tone.sine(800, 60) + Tone.silence(60) +
            Tone.sine(800, 180)
        RogerBeepStyle.TWO_TONE ->
            Tone.sweep(1500, 500, 300)
        RogerBeepStyle.QUINDAR ->
            // NASA Apollo 任务地面指令音：2525Hz 极短促，很有辨识度
            Tone.sine(2525, 200)
    }.let { beep ->
        // 尾音前垫静音：与最后的语音帧拉开，避免粘连
        val lead = Tone.silence(LEAD_SILENCE_MS)
        ByteArray(lead.size + beep.size).also { merged ->
            System.arraycopy(lead, 0, merged, 0, lead.size)
            System.arraycopy(beep, 0, merged, lead.size, beep.size)
        }
    }
}
