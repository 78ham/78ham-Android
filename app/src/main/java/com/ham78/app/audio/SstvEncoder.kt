package com.ham78.app.audio

/**
 * SSTV（慢扫描电视）编码器：把图片编码为 Robot36 制式音频并发射。
 *
 * Robot36 是业余无线电最常用的 SSTV 制式：36 秒传一张 320×240 彩色图。
 * 编码流程：图片缩放为 320×240 → 逐行取 YUV → 每分量调制成 FM 音频 → 前导音 + VIS 头。
 *
 * 时序全部对照 sstv 解码器规范（JL Barber N7CXI / colaclanth sstv spec.py）：
 *   行结构：sync 1200Hz 9ms → porch 1500Hz 3ms → Y 扫描 88ms
 *           → 分隔 1500Hz 4.5ms → porch 1900Hz 1.5ms → 色度扫描 44ms
 *   奇数行带 B-Y，偶数行带 R-Y（交替）；每行总时长 150ms，240 行 = 36 秒。
 *   电平映射：黑 1500Hz → 白 2300Hz（1200Hz 只用于同步，绝不能当"黑"用，
 *   旧实现把整幅图压在 1200-1500Hz，解码器全当同步脉冲，出来全是垃圾）。
 *   VIS 头：1200Hz 起始位 30ms + 8 位数据（1100Hz=1, 1300Hz=0，每 30ms，
 *   LSB 先发，第 8 位是偶校验位）+ 1200Hz 停止位 30ms。
 *   Robot36 的 VIS 编号是 8（旧实现写成 44，那是 Martin M1）。
 *
 * 图片来源：用户从相册选图（拿到的就是已缩放好的像素数组），
 * 这里只做纯数学调制，不依赖 Android Bitmap，便于单元测试。
 *
 * 调用示例：
 *   val pixels = IntArray(320*240)  // ARGB 像素（从相册图片缩放得到）
 *   val pcm = SstvEncoder.encodeRobot36(pixels, 320, 240)
 *   AudioManager.transmitPcm(pcm)
 */
object SstvEncoder {

    const val WIDTH = 320
    const val HEIGHT = 240

    /** VIS 编号：8 = Robot36（44 是 Martin M1，旧实现张冠李戴导致解码器拒收） */
    private const val VIS_ROBOT36 = 8

    /** 电平映射：黑电平 1500Hz、白电平 2300Hz（2400Hz 以内是 SSTV 的音频带宽） */
    private const val BLACK_HZ = 1500
    private const val WHITE_HZ = 2300

    /** 同步/分隔频率：1200 只做同步，1500 兼做分隔脉冲，1900 是色度前 porch */
    private const val SYNC_HZ = 1200
    private const val PORCH_HZ = 1500
    private const val SEP_HZ = 1500
    private const val SEP_PORCH_HZ = 1900

    /** 各脉冲时长（毫秒，规范值） */
    private const val SYNC_PULSE_MS = 9
    private const val SYNC_PORCH_MS = 3
    private const val SCAN_MS = 88
    private const val SEP_PULSE_MS = 4          // 规范 4.5ms，8kHz 下取整为 4ms
    private const val SEP_PORCH_MS = 2          // 规范 1.5ms，取整为 2ms
    private const val CHROMA_SCAN_MS = 44

    /** VIS 位宽：起始位 + 8 数据位（含校验）+ 停止位，每位 30ms */
    private const val VIS_BIT_MS = 30

    /** 扫描信号幅度：留余量，避免 FM 峰值叠加后削顶 */
    private const val SCAN_AMPLITUDE = 0.6

    /**
     * 把 320×240 ARGB 像素数组编码为 Robot36 音频 PCM。
     * 8kHz 采样下约 576KB，按 20ms 帧约 2880 个 UDP 包（transmitPcm 按节奏发）。
     */
    fun encodeRobot36(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size >= width * height) { "像素数组大小不足: ${pixels.size} < ${width * height}" }

        val out = java.io.ByteArrayOutputStream()
        // 相位容器：整个图像共用一个，保证所有扫描段之间相位连续
        val phase = DoubleArray(1)

        // --- 前导音 + VIS 头：唤醒接收端的 VOX 并声明制式 ---
        // 标准 300ms 1900Hz + 10ms 1200Hz 断点 + 300ms 1900Hz + 30ms 1200Hz 起始位
        out.write(Tone.sine(1900, 300))
        out.write(Tone.sine(SYNC_HZ, 10))
        out.write(Tone.sine(1900, 300))
        writeVisHeader(out)

        // --- 逐行扫描：Robot36 每行发 Y + 一个色度分量（奇偶行交替 R-Y / B-Y）---
        for (line in 0 until HEIGHT) {
            val y0 = lineY(pixels, width, height, line)

            // 行同步 1200Hz 9ms + 同步后 porch 1500Hz 3ms
            appendTone(out, SYNC_HZ, SYNC_PULSE_MS, phase)
            appendTone(out, PORCH_HZ, SYNC_PORCH_MS, phase)

            // Y 分量：这一行的亮度，88ms
            appendScan(out, y0, SCAN_MS, phase)

            // 分隔 1500Hz 4.5ms + porch 1900Hz 1.5ms，然后色度 44ms
            appendTone(out, SEP_HZ, SEP_PULSE_MS, phase)
            appendTone(out, SEP_PORCH_HZ, SEP_PORCH_MS, phase)
            // 偶数行发 R-Y（红差），奇数行发 B-Y（蓝差）
            val chromaR = line % 2 == 0
            appendScan(out, lineChroma(pixels, width, height, line, chromaR), CHROMA_SCAN_MS, phase)
        }
        return out.toByteArray()
    }

    /**
     * 把一行像素的某一分量调制为 FM 音频并写入输出流。
     * 每毫秒一个频率步进，电平 0..255 线性映射到 1500..2300Hz。
     *
     * 【关键】相位连续累加，不是一毫秒一段重新起头的正弦。
     * 毫秒边界相位跳变会产生大量咔哒声，接收端解出来是横条纹。
     */
    private fun appendScan(
        out: java.io.ByteArrayOutputStream,
        lineData: FloatArray,
        durationMs: Int,
        phaseRef: DoubleArray   // 跨调用保持相位的容器（单元素数组）
    ) {
        val samplesPerMs = Tone.SAMPLE_RATE / 1000
        var phase = phaseRef[0]
        // 整行共用一个缓冲：按毫秒切片写入，避免每毫秒 new 一个小数组
        val buf = ByteArray(durationMs * samplesPerMs * 2)

        for (ms in 0 until durationMs) {
            // 这一笔毫秒对应的像素位置：durationMs 比 WIDTH 短时按比例抽样
            val idx = (ms * (lineData.size - 1) / durationMs.coerceAtLeast(1))
                .coerceIn(0, lineData.size - 1)
            val level = lineData[idx].coerceIn(0f, 255f)
            val hz = BLACK_HZ + (WHITE_HZ - BLACK_HZ) * level / 255f
            val step = 2.0 * Math.PI * hz / Tone.SAMPLE_RATE

            val base = ms * samplesPerMs * 2
            for (i in 0 until samplesPerMs) {
                val sample = (Math.sin(phase) * SCAN_AMPLITUDE * Short.MAX_VALUE).toInt()
                    .coerceIn(-32768, 32767)
                val o = base + i * 2
                buf[o] = (sample and 0xFF).toByte()
                buf[o + 1] = ((sample shr 8) and 0xFF).toByte()
                phase += step
            }
        }

        out.write(buf)
        phaseRef[0] = phase
    }

    /** 写一段定频正弦，相位同样连续（同步/分隔脉冲也走相位累加，避免咔哒声） */
    private fun appendTone(
        out: java.io.ByteArrayOutputStream,
        hz: Int,
        durationMs: Int,
        phaseRef: DoubleArray
    ) {
        val samplesPerMs = Tone.SAMPLE_RATE / 1000
        var phase = phaseRef[0]
        val buf = ByteArray(durationMs * samplesPerMs * 2)
        val step = 2.0 * Math.PI * hz / Tone.SAMPLE_RATE

        for (i in 0 until durationMs * samplesPerMs) {
            val sample = (Math.sin(phase) * SCAN_AMPLITUDE * Short.MAX_VALUE).toInt()
                .coerceIn(-32768, 32767)
            buf[i * 2] = (sample and 0xFF).toByte()
            buf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            phase += step
        }
        out.write(buf)
        phaseRef[0] = phase
    }

    /**
     * VIS 头：1200Hz 起始位 30ms + 8 位（1100Hz=1 / 1300Hz=0，LSB 先发，
     * 第 8 位 = 偶校验）+ 1200Hz 停止位 30ms。
     * 解码器会检查奇偶性，校验位不对整张图直接拒收。
     */
    private fun writeVisHeader(out: java.io.ByteArrayOutputStream) {
        val visCode = VIS_ROBOT36
        appendVisTone(out, SYNC_HZ, VIS_BIT_MS)   // 起始位

        // LSB 先发；第 8 位放偶校验（前 7 位里 1 的个数为奇时校验位 = 1）
        var ones = 0
        for (bit in 0 until 7) {
            val isOne = (visCode shr bit) and 1 == 1
            if (isOne) ones++
            appendVisTone(out, if (isOne) 1100 else 1300, VIS_BIT_MS)
        }
        val parityOne = ones % 2 == 1   // 偶校验：凑成偶数个 1
        appendVisTone(out, if (parityOne) 1100 else 1300, VIS_BIT_MS)

        appendVisTone(out, SYNC_HZ, VIS_BIT_MS)   // 停止位
    }

    /** VIS 数据位按 Tone.sine 生成即可（短音，无需跨段相位连续） */
    private fun appendVisTone(out: java.io.ByteArrayOutputStream, hz: Int, ms: Int) {
        out.write(Tone.sine(hz, ms))
    }

    // --- 像素 → YUV 分量提取 ---

    /** 提取一行（或半分辨率）的 Y 亮度分量 */
    private fun lineY(pixels: IntArray, width: Int, height: Int, line: Int): FloatArray {
        val result = FloatArray(WIDTH)
        val y = line.coerceIn(0, height - 1)
        for (x in 0 until WIDTH) {
            val px = pixels[y * width + (x * width / WIDTH).coerceIn(0, width - 1)]
            result[x] = yOf(px)
        }
        return result
    }

    /** 提取一行的色度分量（R-Y 或 B-Y，128 为中性） */
    private fun lineChroma(
        pixels: IntArray, width: Int, height: Int, line: Int, chromaR: Boolean
    ): FloatArray {
        val result = FloatArray(WIDTH)
        val y = line.coerceIn(0, height - 1)
        for (x in 0 until WIDTH) {
            val px = pixels[y * width + (x * width / WIDTH).coerceIn(0, width - 1)]
            result[x] = if (chromaR) chromaROf(px) else chromaBOf(px)
        }
        return result
    }

    /** ARGB → Y（BT.601 亮度） */
    private fun yOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** ARGB → R-Y（128 中性） */
    private fun chromaROf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.713f * (r - (0.299f * r + 0.587f * g + 0.114f * b)) + 128f
    }

    /** ARGB → B-Y（128 中性） */
    private fun chromaBOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.564f * (b - (0.299f * r + 0.587f * g + 0.114f * b)) + 128f
    }
}
