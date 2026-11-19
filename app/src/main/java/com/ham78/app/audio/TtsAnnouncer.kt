package com.ham78.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/*
 * TTS 文字转语音播报：把一段文字合成为语音 PCM，再经音频管线发射出去。
 *
 * Android TTS 只能直接写到音频设备，不能直接拿到 PCM 数据；
 * 所以这里把合成结果写到本地 WAV 文件，再读回内存转成裸 PCM。
 * 实例初始化异步（TTS 引擎要连接服务），调用方用 awaitReady() 等就绪。
 *
 * 调用示例（TalkService 内）：
 *   val announcer = TtsAnnouncer(context)
 *   val pcm = announcer.synthesize("B H 1 A B C 呼叫")   // 挂起直到合成完
 *   if (pcm != null) multiServerManager.transmitPcmToActive(pcm)
 */
class TtsAnnouncer(private val context: Context) {

    companion object {
        private const val TAG = "TtsAnnouncer"
        private const val MAX_INPUT_LEN = 200      // 单条播报上限，防止超长文本卡语音信道
        private const val READY_TIMEOUT_MS = 8000L // TTS 引擎初始化兜底超时
    }

    private var tts: TextToSpeech? = null
    private var initStatus: Int? = null   // TTS 初始化结果；null = 还在初始化

    /** 创建实例即开始异步初始化 TTS 引擎 */
    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            initStatus = status
            if (status == TextToSpeech.SUCCESS) {
                // 中文优先，中文 TTS 缺失时 TTS 自动回落到默认语言
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    /**
     * 等待 TTS 引擎就绪（挂起协程）。
     * 初始化失败或超时返回 false，调用方放弃本次播报即可。
     */
    suspend fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (initStatus == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        return initStatus == TextToSpeech.SUCCESS
    }

    /**
     * 把文字合成为 8kHz/16bit/单声道 PCM。
     *
     * 返回 null 表示：TTS 未就绪 / 文本为空 / 合成失败 / 生成文件读不出。
     * TTS 引擎输出 16kHz 或 22kHz 采样率，这里用 AudioTrack 不行的另一条路：
     * 直接把 WAV 数据按采样率比例线性重采样到 8kHz（管线的统一采样率）。
     */
    suspend fun synthesize(text: String): ByteArray? {
        if (text.isBlank()) return null
        if (!awaitReady()) return null

        val clipText = text.take(MAX_INPUT_LEN)
        val wavFile = kotlin.io.path.createTempFile(prefix = "tts_", suffix = ".wav")

        try {
            // TTS 写 WAV 文件（File 模式合成，排队后异步写盘）
            val result = tts?.synthesizeToFile(
                clipText,
                null,
                java.io.File(wavFile.toString()),
                "78ham_tts"
            )
            // synthesizeToFile 返回的是排队句柄，真正完成要等写盘；
            // 简化处理：轮询文件大小稳定（连续 300ms 不再增长）视为写完
            if (result == TextToSpeech.SUCCESS && waitForFileStable(wavFile)) {
                return WavReader.readPcm8k(wavFile.toFile())
            }
            Log.w(TAG, "TTS synthesize failed, result=$result")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesize error", e)
            return null
        } finally {
            try { java.nio.file.Files.deleteIfExists(wavFile) } catch (_: Exception) {}
        }
    }

    /** 轮询 WAV 文件直到大小稳定（写完），最长 10 秒 */
    private suspend fun waitForFileStable(path: java.nio.file.Path): Boolean {
        var lastSize = -1L
        var stableCount = 0
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val size = try { java.nio.file.Files.size(path) } catch (_: Exception) { 0L }
            if (size > 44 && size == lastSize) {   // 44 = WAV 头最小长度
                stableCount++
                if (stableCount >= 3) return true   // 连续 300ms 大小不变 = 写完
            } else {
                stableCount = 0
            }
            lastSize = size
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * 极简 WAV 读取器：解析头，抽出 PCM 数据，并重采样到 8kHz。
     * 只支持 16bit PCM WAV（Android TTS 的标准输出格式）。
     */
    object WavReader {
        fun readPcm8k(file: java.io.File): ByteArray? {
            val bytes = try { file.readBytes() } catch (_: Exception) { return null }
            if (bytes.size < 44) return null

            // 从 "fmt " 块取声道数、采样率、位深（不假设固定偏移，跳过可变元数据）
            val fmtPos = indexOf(bytes, "fmt ".toByteArray(), from = 12) ?: return null
            val channels = readShort(bytes, fmtPos + 10)
            val sampleRate = readInt(bytes, fmtPos + 12)
            val bitsPerSample = readShort(bytes, fmtPos + 22)
            if (bitsPerSample != 16) return null    // 只处理 16bit

            // 从 "data" 块取 PCM 数据
            val dataPos = indexOf(bytes, "data".toByteArray(), from = fmtPos) ?: return null
            val dataLen = readInt(bytes, dataPos + 4)
                .coerceAtMost(bytes.size - dataPos - 8)
            val src = bytes.copyOfRange(dataPos + 8, dataPos + 8 + dataLen)

            return resampleTo8k(src, sampleRate, channels)
        }

        /**
         * 重采样到 8kHz 单声道。
         * 多声道取首声道；频率比按整数取样点跳跃（线性抽取，语音场景足够）。
         */
        private fun resampleTo8k(src: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            if (sampleRate == 8000 && channels == 1) return src

            val frameBytes = 2 * channels
            val srcFrames = src.size / frameBytes
            val outFrames = srcFrames.toLong() * 8000 / sampleRate
            val out = ByteArray((outFrames * 2).toInt())

            for (i in 0 until outFrames.toInt()) {
                // 输出第 i 帧对应输入第 (i * sampleRate / 8000) 帧的首声道样本
                val srcIdx = (i.toLong() * sampleRate / 8000).toInt()
                    .coerceAtMost(srcFrames - 1) * frameBytes
                val sample = ((src[srcIdx + 1].toInt() shl 8) or (src[srcIdx].toInt() and 0xFF))
                out[i * 2] = (sample and 0xFF).toByte()
                out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return out
        }

        private fun readShort(b: ByteArray, pos: Int): Int =
            (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)

        private fun readInt(b: ByteArray, pos: Int): Int =
            (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
            ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)

        private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int? {
            for (i in from..(data.size - pattern.size)) {
                var matched = true
                for (j in pattern.indices) {
                    if (data[i + j] != pattern[j]) { matched = false; break }
                }
                if (matched) return i
            }
            return null
        }
    }
}
