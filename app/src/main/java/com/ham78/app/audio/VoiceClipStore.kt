package com.ham78.app.audio

/**
 * 语音回放缓存
 *
 * 保存最近收到的语音会话解码后的 PCM 数据（8kHz / 16bit / 单声道），
 * 供消息界面点击“语音”气泡时重新播放。采用 LRU 上限避免内存膨胀。
 */
object VoiceClipStore {

    private const val MAX_CLIPS = 60

    // 8000 采样率 * 2 字节 = 每秒 16000 字节
    private const val BYTES_PER_SECOND = 16000

    private val clips = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            return size > MAX_CLIPS
        }
    }

    @Synchronized
    fun put(id: String, pcm: ByteArray) {
        if (id.isEmpty() || pcm.isEmpty()) return
        clips[id] = pcm
    }

    @Synchronized
    fun get(id: String): ByteArray? = clips[id]

    @Synchronized
    fun clear() = clips.clear()

    /** 根据 PCM 长度估算时长（毫秒） */
    fun durationMs(pcm: ByteArray): Long = pcm.size.toLong() * 1000 / BYTES_PER_SECOND
}
