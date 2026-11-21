package com.ham78.app.audio

/**
 * MDC1200 解码。
 *
 * 移植自小程序 utils/mdc1200.js（Matthew Kaufman 的 mdc_decode.c，FOURPOINT，MDC_ND=5）。
 * 输入是 8 kHz 16-bit 单声道 PCM。信令通常贴在一次通话的开头或结尾，
 * 一次通话里解出多包时保留最后一包的描述，标在呼号旁边。
 */
class Mdc1200Decoder(sampleRate: Int = 8000) {

    private val incru: Long = when (sampleRate) {
        8000 -> 644245094L
        16000 -> 322122547L
        22050 -> 233739716L
        32000 -> 161061274L
        44100 -> 116869858L
        48000 -> 107374182L
        else -> (1200L * 2L * (0x80000000L / sampleRate))
    }
    private val incr5: Long = 5L * incru

    private var good = 0
    private var indouble = 0
    private var op = 0
    private var arg = 0
    private var unitId = 0
    private var extra0 = 0
    private var extra1 = 0
    private var extra2 = 0
    private var extra3 = 0

    private val du = Array(MDC_ND) { i ->
        // 五个解码器相位错开。除法按原库的浮点再取整，不能用整数除。
        val thu = Math.floor(i * 2.0 * (0x80000000.0 / MDC_ND)).toLong().toUInt()
        Decoder(thu = thu, nlstep = i)
    }

    /** 喂一帧小端 PCM。解出信令时返回 "MDC:XXXX 标签"，没有则返回 null。 */
    fun feedPcm16Le(pcm: ByteArray): String? {
        if (pcm.size < 2) return null
        var last: String? = null
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val sample = (lo or (hi shl 8)).toShort().toInt()
            val packet = feedSample(sample)
            if (packet != null) last = packet
            i += 2
        }
        return last
    }

    private fun feedSample(sample: Int): String? {
        val value = sample / 65536.0
        var last: String? = null
        for (j in 0 until MDC_ND) {
            val unit = du[j]
            val prev = unit.thu
            val next = prev + incr5.toUInt()
            unit.thu = next
            if (next < prev) {
                unit.nlstep++
                if (unit.nlstep > 9) unit.nlstep = 0
                unit.nlevel[unit.nlstep] = value
                val label = nlproc(j)
                if (label != null) last = label
            }
        }
        return last
    }

    private fun nlproc(x: Int): String? {
        val unit = du[x]
        val vnow: Double
        val vpast: Double
        when (unit.nlstep) {
            3 -> {
                vnow = (-0.60 * unit.nlevel[3]) + (0.97 * unit.nlevel[1])
                vpast = (-0.60 * unit.nlevel[7]) + (0.97 * unit.nlevel[9])
            }
            8 -> {
                vnow = (-0.60 * unit.nlevel[8]) + (0.97 * unit.nlevel[6])
                vpast = (-0.60 * unit.nlevel[2]) + (0.97 * unit.nlevel[4])
            }
            else -> return null
        }
        unit.xorb = if (vnow > vpast) 1 else 0
        if (unit.invert != 0) unit.xorb = if (unit.xorb != 0) 0 else 1
        return shiftin(x)
    }

    private fun shiftin(x: Int): String? {
        val unit = du[x]
        val bit = unit.xorb
        if (unit.shstate == -1) {
            unit.synchigh = 0u
            unit.synclow = 0u
            unit.shstate = 0
        }
        if (unit.shstate == 0) {
            val carry = if ((unit.synclow and 0x80000000u) != 0u) 1u else 0u
            unit.synchigh = (unit.synchigh shl 1) or carry
            unit.synclow = (unit.synclow shl 1) or (if (bit != 0) 1u else 0u)
            var gcount = Integer.bitCount(0x000000ff and (0x00000007 xor unit.synchigh.toInt()))
            gcount += Integer.bitCount(0x092a446f xor unit.synclow.toInt())
            if (gcount <= MDC_GDTHRESH) {
                unit.shstate = 1
                unit.shcount = 0
                unit.bits.fill(0)
            } else if (gcount >= 40 - MDC_GDTHRESH) {
                unit.shstate = 1
                unit.shcount = 0
                unit.xorb = if (unit.xorb != 0) 0 else 1
                unit.invert = if (unit.invert != 0) 0 else 1
                unit.bits.fill(0)
            }
            return null
        }
        if (unit.shstate == 1 || unit.shstate == 2) {
            if (unit.shcount in unit.bits.indices) unit.bits[unit.shcount] = bit.toByte()
            unit.shcount++
            if (unit.shcount > 111) return procbits(x)
        }
        return null
    }

    private fun procbits(x: Int): String? {
        val unit = du[x]
        val lbits = ByteArray(112)
        var lbc = 0
        for (i in 0 until 16) {
            for (j in 0 until 7) {
                lbits[lbc++] = unit.bits[(j * 16) + i]
            }
        }
        val data = ByteArray(14)
        for (i in 0 until 14) {
            var byte = 0
            for (j in 0 until 8) {
                if (lbits[(i * 8) + j].toInt() != 0) byte = byte or (1 shl j)
            }
            data[i] = byte.toByte()
        }
        gofix(data)
        val ccrc = docrc(data, 4)
        val rcrc = ((data[5].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (ccrc == rcrc) {
            if (unit.shstate == 2) {
                extra0 = data[0].toInt() and 0xFF
                extra1 = data[1].toInt() and 0xFF
                extra2 = data[2].toInt() and 0xFF
                extra3 = data[3].toInt() and 0xFF
                for (k in 0 until MDC_ND) du[k].shstate = -1
                good = 2
                indouble = 0
            } else if (indouble == 0) {
                good = 1
                op = data[0].toInt() and 0xFF
                arg = data[1].toInt() and 0xFF
                unitId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                val opcode = data[0].toInt() and 0xFF
                if (opcode == 0x35 || opcode == 0x55) {
                    good = 0
                    indouble = 1
                    unit.shstate = 2
                    unit.shcount = 0
                    unit.bits.fill(0)
                } else {
                    for (k in 0 until MDC_ND) du[k].shstate = -1
                }
            } else {
                unit.shstate = 2
                unit.shcount = 0
                unit.bits.fill(0)
            }
        } else {
            unit.shstate = -1
        }
        if (good != 0) {
            val frames = good
            good = 0
            return describe(frames, op, unitId)
        }
        return null
    }

    private fun gofix(data: ByteArray) {
        val csr = IntArray(7)
        var syn = 0
        for (i in 0 until 7) {
            for (j in 0..7) {
                for (k in 6 downTo 1) csr[k] = csr[k - 1]
                csr[0] = ((data[i].toInt() and 0xFF) shr j) and 0x01
                val b = csr[0] + csr[2] + csr[5] + csr[6]
                syn = syn shl 1
                val parity = ((data[i + 7].toInt() and 0xFF) shr j) and 0x01
                if ((b and 0x01) xor parity != 0) syn = syn or 1
                var ec = 0
                if (syn and 0x80 != 0) ec++
                if (syn and 0x20 != 0) ec++
                if (syn and 0x04 != 0) ec++
                if (syn and 0x02 != 0) ec++
                if (ec >= 3) {
                    syn = syn xor 0xa6
                    var fixi = i
                    var fixj = j - 7
                    if (fixj < 0) {
                        fixi--
                        fixj += 8
                    }
                    if (fixi >= 0) {
                        val flipped = (data[fixi].toInt() and 0xFF) xor (1 shl fixj)
                        data[fixi] = flipped.toByte()
                    }
                }
            }
        }
    }

    private class Decoder(
        var thu: UInt,
        var xorb: Int = 0,
        var invert: Int = 0,
        var nlstep: Int = 0,
        val nlevel: DoubleArray = DoubleArray(10),
        var synclow: UInt = 0u,
        var synchigh: UInt = 0u,
        var shstate: Int = -1,
        var shcount: Int = 0,
        val bits: ByteArray = ByteArray(112)
    )

    companion object {
        private const val MDC_ND = 5
        private const val MDC_GDTHRESH = 5

        fun describe(frames: Int, op: Int, unitId: Int): String {
            val label = when (op) {
                0x00, 0x01 -> "PTT ID"
                0x40 -> "紧急报警"
                0x11 -> "远程监听"
                0x63 -> "呼叫提醒"
                0x35 -> if (frames == 2) "选呼" else "请求通话"
                0x55 -> "状态消息"
                else -> "OP:0x${(op and 0xFF).toString(16).uppercase().padStart(2, '0')}"
            }
            val id = (unitId and 0xFFFF).toString(16).uppercase().padStart(4, '0')
            return "MDC:$id $label"
        }

        private fun docrc(data: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                val c = flip(data[i].toInt() and 0xFF, 8)
                var mask = 0x80
                while (mask != 0) {
                    val bit = crc and 0x8000
                    crc = crc shl 1
                    var mixed = bit
                    if (c and mask != 0) mixed = mixed xor 0x8000
                    if (mixed != 0) crc = crc xor 0x1021
                    mask = mask shr 1
                }
            }
            crc = flip(crc, 16)
            crc = crc xor 0xffff
            return crc and 0xFFFF
        }

        private fun flip(value: Int, bits: Int): Int {
            var res = 0
            for (i in 0 until bits) {
                if ((value shr i) and 1 != 0) res = res or (1 shl (bits - 1 - i))
            }
            return res
        }
    }
}
