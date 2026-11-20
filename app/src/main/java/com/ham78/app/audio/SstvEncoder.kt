package com.ham78.app.audio

/**
 * SSTV（慢扫描电视）编码器：把图片编码为 Robot36 制式音频并发射。
 *
 * Robot36 是业余无线电最常用的 SSTV 制式：36 秒传一张 320×240 彩色图。
 * 编码流程：图片缩放为 320×240 → 转 YUV → 逐行调制为 FM 音频 → 前导音 + VIS 头。
 *
 * 图片来源：用户从相册选图（拿到的就是已缩放好的像素数组），
 * 这里只做纯数学调制，不依赖 Android Bitmap，便于单元测试。
 *
 * 调用示例：
 *   val pixels = IntArray(320*240)  // ARGB 像素（从相册图片缩放得到）
 *   val pcm = SstvEncoder.encodeRobot36(pixels, 320, 240)
 *   AudioManager.transmitPcm(pcm)
 *
 * 时序参考：https://www.classic-intl.net/sstv/ Robot36 规范（JL Barber, K5JBN）
 */
object SstvEncoder {

    const val WIDTH = 320
    const val HEIGHT = 240

    /** Robot36 音频参数：1200Hz 为黑电平、1500Hz 为白电平，1500Hz 扫描中心 */
    private const val BLACK_HZ = 1200
    private const val WHITE_HZ = 1500

    /** 前导音：VOX 唤醒接收端，约 300ms 1900Hz + 100ms 1200Hz 交替两组 */
    private const val LEADER_MS = 300

    /** 各同步/扫描脉冲时长（毫秒，规范值） */
    private const val SYNC_PULSE_MS = 9          // 行同步 9ms 1200Hz
    private const val SYNC_SEP_MS = 3            // 同步后分隔 3ms 1500Hz
    private const val SCAN_MS = 88               // 每分量扫描 88ms
    private const val Y_ODD_SCAN_MS = 88
    private const val R_Y_EVEN_SCAN_MS = 44      // 偶数行的 R-Y 扫描 44ms
    private const val B_Y_ODD_SCAN_MS = 44       // 奇数行的 B-Y 扫描 44ms

    /** 扫描信号幅度：留余量，避免 FM 峰值叠加后削顶 */
    private const val SCAN_AMPLITUDE = 0.6

    /**
     * 把 320×240 ARGB 像素数组编码为 Robot36 音频 PCM。
     * 耗时约 36 秒的音频：8kHz 采样下约 576KB，按 20ms 帧约 2880 个 UDP 包。
     */
    fun encodeRobot36(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size >= width * height) { "像素数组大小不足: ${pixels.size} < ${width * height}" }

        val out = java.io.ByteArrayOutputStream()
        // 相位容器：整个图像共用一个，保证所有扫描段之间相位连续
        val phase = DoubleArray(1)

        // --- 前导音 + VIS 头：唤醒接收端的 VOX 并声明制式 ---
        out.write(Tone.sine(1900, LEADER_MS))
        out.write(Tone.sine(1200, 10))
        out.write(Tone.sine(1900, LEADER_MS))
        out.write(Tone.sine(1200, 30))
        writeVisHeader(out)   // VIS 代码 44 = Robot36

        // --- 逐行扫描：Robot36 每两行一组（奇数行发 Y+R-Y，偶数行发 Y+B-Y）---
        for (line in 0 until HEIGHT) {
            val y0 = lineY(pixels, width, height, line)

            // 行同步 1200Hz + 分隔 1500Hz
            out.write(Tone.sine(BLACK_HZ, SYNC_PULSE_MS))
            out.write(Tone.sine(WHITE_HZ, SYNC_SEP_MS))

            // Y 分量：这一行的亮度，88ms
            appendScan(out, y0, Y_ODD_SCAN_MS, phase)

            if (line % 2 == 0) {
                // 偶数行：R-Y（色度红差）44ms
                out.write(Tone.sine(BLACK_HZ, SYNC_PULSE_MS))
                out.write(Tone.sine(WHITE_HZ, SYNC_SEP_MS))
                appendScan(out, lineChroma(pixels, width, height, line, chromaR = true), R_Y_EVEN_SCAN_MS, phase)
            } else {
                // 奇数行：B-Y（色度蓝差）44ms
                out.write(Tone.sine(BLACK_HZ, SYNC_PULSE_MS))
                out.write(Tone.sine(WHITE_HZ, SYNC_SEP_MS))
                appendScan(out, lineChroma(pixels, width, height, line, chromaR = false), B_Y_ODD_SCAN_MS, phase)
            }
        }
        return out.toByteArray()
    }

    /**
     * 把一行像素的某一分量调制为 FM 音频并写入输出流。
     * 每毫秒一个频率步进，电平 0..255 线性映射到 BLACK_HZ..WHITE_HZ。
     *
     * 【关键】相位连续累加，不是一毫秒一段重新起头的正弦。
     * 旧写法每毫秒调用一次 Tone.sine(hz, 1)，每次都从 sin(0) 重新开始，
     * 相位在每个毫秒边界上跳变 → 大量咔哒声，接收端解出来是横条纹。
     * 这里沿用同一个相位往下走，得到真正的 FM 调制波。
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

    private fun writeVisHeader(out: java.io.ByteArrayOutputStream) {
        // VIS：起始位 1200Hz 30ms + 8 位数据（1100Hz=1, 1300Hz=0，每 30ms）+ 停止位
        val visCode = 44   // Robot36 的 VIS 编号
        out.write(Tone.sine(1200, 30))
        for (bit in 0 until 8) {
            val isOne = (visCode shr bit) and 1 == 1
            out.write(Tone.sine(if (isOne) 1100 else 1300, 30))
        }
        out.write(Tone.sine(1200, 30))
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
