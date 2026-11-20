package com.ham78.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume

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
        private const val SYNTH_TIMEOUT_MS = 15000L // 单次合成兜底超时
    }

    private var tts: TextToSpeech? = null
    private var initStatus: Int? = null   // TTS 初始化结果；null = 还在初始化
    private var progressListener: android.speech.tts.UtteranceProgressListener? = null

    /** 创建实例即开始异步初始化 TTS 引擎 */
    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            initStatus = status
            if (status == TextToSpeech.SUCCESS) {
                // 中文优先；很多引擎没有中文语音包，setLanguage 返回值非
                // LANG_MISSING_DATA/NOT_SUPPORTED 时保持引擎默认语言，
                // 之前不检查返回值，有的引擎被设成空语言后合成直接失败
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Chinese TTS unavailable (result=$result), using engine default")
                    tts?.language = Locale.getDefault()
                }
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
        // 用 cacheDir 而不是 java.nio.file：后者的 Path/Files 是 API 26+，
        // 本项目 minSdk 23，在 Android 6/7 上会直接 NoClassDefFoundError。
        val wavFile = java.io.File.createTempFile("tts_", ".wav", context.cacheDir)

        try {
            // 合成完成的信号：UtteranceProgressListener 的 onDone 最准，
            // 文件大小轮询只作为兜底（有些引擎不回调 onDone）。
            // withTimeoutOrNull 兜住"引擎既不回调也不写文件"的死等情况。
            val done = kotlinx.coroutines.withTimeoutOrNull(SYNTH_TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                    // 取消防护：withTimeoutOrNull 超时会取消协程，TTS 回调线程稍后
                    // 触发 onDone/onError 时再 cont.resume 会抛 IllegalStateException
                    // 并在 binder 线程里炸掉整个 TTS 客户端（"播报合成失败"的元凶之一）。
                    cont.invokeOnCancellation { finished.set(true) }

                    // UtteranceProgressListener 的三个方法都是抽象的，必须全部实现，
                    // 少一个就编译不过（onStop 是 API 23 引入的，本项目 minSdk 23 正好可用）
                    val listener = object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(true)
                            }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                    }
                    progressListener = listener
                    tts?.setOnUtteranceProgressListener(listener)

                    val result = tts?.synthesizeToFile(clipText, null, wavFile, "78ham_tts")
                    if (result != TextToSpeech.SUCCESS) {
                        if (finished.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(false)
                        }
                    }
                }
            }

            // onDone 没来（引擎不回调）时退回"文件大小稳定"判断
            val complete = done == true || waitForFileStable(wavFile)
            if (!complete) {
                Log.w(TAG, "TTS synthesis did not complete")
                return null
            }

            val pcm = WavReader.readPcm8k(wavFile)
            if (pcm == null) {
                Log.w(TAG, "TTS WAV read failed (size=${wavFile.length()})")
            }
            return pcm
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesize error", e)
            return null
        } finally {
            try { wavFile.delete() } catch (_: Exception) {}
            progressListener = null
        }
    }

    /**
     * 轮询 WAV 文件直到大小稳定（写完），最长 10 秒。
     * 用 java.io.File 而非 java.nio.file.Files：后者是 API 26+。
     */
    private suspend fun waitForFileStable(file: java.io.File): Boolean {
        var lastSize = -1L
        var stableCount = 0
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val size = try { file.length() } catch (_: Exception) { 0L }
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
        tts?.setOnUtteranceProgressListener(null)
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
         * 多声道取首声道；用线性插值而不是"隔点抽取"——
         * 隔点抽取会把 22kHz 的高频折回基带，听起来又尖又毛（金属声）。
         */
        private fun resampleTo8k(src: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            if (sampleRate == 8000 && channels == 1) return src
            if (sampleRate <= 0) return ByteArray(0)

            val frameBytes = 2 * channels
            val srcFrames = src.size / frameBytes
            if (srcFrames <= 0) return ByteArray(0)

            val outFrames = (srcFrames.toLong() * 8000 / sampleRate).toInt()
            val out = ByteArray(outFrames * 2)
            val ratio = sampleRate.toDouble() / 8000.0

            for (i in 0 until outFrames) {
                // 输出第 i 帧在输入中的位置（带小数），取相邻两点线性插值
                val pos = i * ratio
                val idx = pos.toInt().coerceAtMost(srcFrames - 1)
                val frac = pos - idx
                val next = (idx + 1).coerceAtMost(srcFrames - 1)

                val s0 = readSample(src, idx * frameBytes)
                val s1 = readSample(src, next * frameBytes)
                val sample = (s0 + (s1 - s0) * frac).toInt()
                out[i * 2] = (sample and 0xFF).toByte()
                out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return out
        }

        /** 从指定字节位置读一个 16bit 小端采样 */
        private fun readSample(b: ByteArray, pos: Int): Double =
            ((b[pos + 1].toInt() shl 8) or (b[pos].toInt() and 0xFF)).toDouble()

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
