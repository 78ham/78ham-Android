package com.ham78.app.audio

/**
 * G.711 A-law 编解码器
 * 使用查表法实现高效的线性 PCM 与 A-law 格式转换
 */
class G711Codec {
    companion object {
        private val encodeTable: ByteArray by lazy {
            ByteArray(65536).apply {
                for (i in -32768..32767) {
                    this[i + 32768] = linear2alawInternal(i).toByte()
                }
            }
        }

        private val decodeTable: ShortArray by lazy {
            ShortArray(256).apply {
                for (i in 0..255) {
                    this[i] = alaw2linearInternal(i).toShort()
                }
            }
        }
    }

    private fun linear2alawInternal(sample: Int): Int {
        var s = sample
        var sign = 0

        if (s < 0) {
            sign = 0x80
            s = s.inv()
        }

        s = s shr 4

        var ix = s
        if (ix > 15) {
            var iexp = 1
            while (ix > 31) {
                ix = ix shr 1
                iexp++
            }
            ix -= 16
            ix += iexp shl 4
        }

        if (sign == 0) {
            ix = ix or 0x80
        }

        return ix xor 0x55
    }

    private fun alaw2linearInternal(code: Int): Int {
        var c = code xor 0x55
        val seg = (c and 0x70) shr 4
        val quant = c and 0x0f
        var sample = (quant shl 4) or 0x08

        if (seg > 0) {
            sample = (sample + 0x100) shl (seg - 1)
        }

        return if ((c and 0x80) != 0) sample else -sample
    }

    /**
     * 编码线性 PCM 采样为 A-law 格式
     * @param pcmData 线性 PCM 采样数组（16-bit signed）
     * @return A-law 编码后的字节数组
     */
    fun encode(pcmData: ShortArray): ByteArray {
        val encoded = ByteArray(pcmData.size)
        for (i in pcmData.indices) {
            val index = (pcmData[i] + 32768) and 0xffff
            encoded[i] = encodeTable[index]
        }
        return encoded
    }

    /**
     * 解码 A-law 格式为线性 PCM 采样
     * @param code A-law 编码字节
     * @return 线性 PCM 采样值（16-bit signed）
     */
    fun alaw2linear(code: Int): Int {
        return decodeTable[code and 0xff].toInt()
    }
}
