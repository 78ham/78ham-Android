package com.ham78.app.audio

class G711Codec {
    companion object {
        private const val SEG_SHIFT = 4
        private const val QUANT_MASK = 0xf
        private const val SEG_MASK = 0x70
        private const val BIAS = 0x84
        
        private lateinit var encodeTable: ByteArray
        private lateinit var decodeTable: ShortArray
        private var initialized = false
    }
    
    init {
        if (!initialized) {
            initTables()
            initialized = true
        }
    }
    
    private fun initTables() {
        encodeTable = ByteArray(65536)
        decodeTable = ShortArray(256)
        
        for (i in -32768..32767) {
            encodeTable[i + 32768] = linear2alawInternal(i).toByte()
        }
        
        for (i in 0..255) {
            decodeTable[i] = alaw2linearInternal(i).toShort()
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
    
    fun encode(pcmData: ShortArray): ByteArray {
        val encoded = ByteArray(pcmData.size)
        for (i in pcmData.indices) {
            val index = (pcmData[i] + 32768) and 0xffff
            encoded[i] = encodeTable[index]
        }
        return encoded
    }
    
    fun alaw2linear(code: Int): Int {
        return decodeTable[code and 0xff].toInt()
    }
}
