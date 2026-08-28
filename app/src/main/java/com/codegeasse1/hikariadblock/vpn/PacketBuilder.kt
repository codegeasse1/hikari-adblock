package com.codegeasse1.hikariadblock.vpn

object PacketBuilder {

    fun ipv4ToString(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    fun ipv6ToString(buf: ByteArray, off: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 16 step 2) {
            if (i > 0) sb.append(':')
            sb.append(String.format("%02x%02x", buf[off + i].toInt() and 0xFF, buf[off + i + 1].toInt() and 0xFF))
        }
        return sb.toString()
    }

    data class IPv4Header(val ihl: Int, val totalLen: Int, val protocol: Int, val src: ByteArray, val dst: ByteArray)
    data class IPv6Header(val src: ByteArray, val dst: ByteArray, val udpOffset: Int)

    fun parseIPv4Header(buf: ByteArray, off: Int, len: Int): IPv4Header? {
        if (len - off < 20) return null
        val version = (buf[off].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val ihl = (buf[off].toInt() and 0x0F) * 4
        if (ihl < 20 || len - off < ihl) return null
        val protocol = buf[off + 9].toInt() and 0xFF
        val totalLen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
        if (totalLen < ihl || totalLen > len - off) return null
        return IPv4Header(
            ihl = ihl, totalLen = totalLen, protocol = protocol,
            src = buf.copyOfRange(off + 12, off + 16),
            dst = buf.copyOfRange(off + 16, off + 20)
        )
    }

    fun parseIPv6Header(buf: ByteArray, off: Int, len: Int): IPv6Header? {
        if (len - off < 40) return null
        val version = (buf[off].toInt() and 0xF0) shr 4
        if (version != 6) return null
        val payloadLen = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
        if (payloadLen > len - off - 40) return null
        var next = buf[off + 6].toInt() and 0xFF
        var pos = off + 40
        var guard = 0
        while (guard++ < 8) {
            if (next == 17) {
                return IPv6Header(
                    src = buf.copyOfRange(off + 8, off + 24),
                    dst = buf.copyOfRange(off + 24, off + 40),
                    udpOffset = pos
                )
            }
            if (next == 0 || next == 43 || next == 60) {
                if (pos + 2 > len) return null
                val hdrLen = (buf[pos + 1].toInt() and 0xFF) * 8 + 8
                if (pos + hdrLen > len) return null
                next = buf[pos].toInt() and 0xFF
                pos += hdrLen
            } else {
                return null
            }
        }
        return null
    }

    fun ipv4Packet(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val p = ByteArray(totalLen)
        p[0] = 0x45.toByte()
        p[2] = (totalLen shr 8).toByte(); p[3] = (totalLen and 0xFF).toByte()
        p[8] = 64.toByte()
        p[9] = 17.toByte()
        src.copyInto(p, 12)
        dst.copyInto(p, 16)
        val udpStart = 20
        p[udpStart] = (srcPort shr 8).toByte(); p[udpStart + 1] = (srcPort and 0xFF).toByte()
        p[udpStart + 2] = (dstPort shr 8).toByte(); p[udpStart + 3] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        p[udpStart + 4] = (udpLen shr 8).toByte(); p[udpStart + 5] = (udpLen and 0xFF).toByte()
        payload.copyInto(p, udpStart + 8)
        val ck = udpChecksumV4(src, dst, udpLen, sumRange(p, udpStart, 8 + payload.size))
        p[udpStart + 6] = (ck shr 8).toByte(); p[udpStart + 7] = (ck and 0xFF).toByte()
        val hck = finish(sumRange(p, 0, 20))
        p[10] = (hck shr 8).toByte(); p[11] = (hck and 0xFF).toByte()
        return p
    }

    fun ipv6Packet(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val p = ByteArray(40 + udpLen)
        p[0] = 0x60.toByte()
        p[4] = (udpLen shr 8).toByte(); p[5] = (udpLen and 0xFF).toByte()
        p[6] = 17.toByte()
        p[7] = 64.toByte()
        src.copyInto(p, 8)
        dst.copyInto(p, 24)
        val udpStart = 40
        p[udpStart] = (srcPort shr 8).toByte(); p[udpStart + 1] = (srcPort and 0xFF).toByte()
        p[udpStart + 2] = (dstPort shr 8).toByte(); p[udpStart + 3] = (dstPort and 0xFF).toByte()
        p[udpStart + 4] = (udpLen shr 8).toByte(); p[udpStart + 5] = (udpLen and 0xFF).toByte()
        payload.copyInto(p, udpStart + 8)
        val ck = udpChecksumV6(src, dst, udpLen, sumRange(p, udpStart, 8 + payload.size))
        p[udpStart + 6] = (ck shr 8).toByte(); p[udpStart + 7] = (ck and 0xFF).toByte()
        return p
    }

    private fun word(a: Byte, b: Byte): Int = ((a.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)

    private fun sumRange(buf: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += word(buf[i], buf[i + 1])
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun finish(sum: Long): Int {
        var s = sum
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        val r = (0xFFFF - s).toInt()
        return if (r == 0) 0xFFFF else r
    }

    private fun udpChecksumV4(src: ByteArray, dst: ByteArray, udpLen: Int, udpSum: Long): Int {
        var sum = 0L
        sum += word(src[0], src[1]); sum += word(src[2], src[3])
        sum += word(dst[0], dst[1]); sum += word(dst[2], dst[3])
        sum += 17
        sum += udpLen
        sum += udpSum
        return finish(sum)
    }

    private fun udpChecksumV6(src: ByteArray, dst: ByteArray, udpLen: Int, udpSum: Long): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) sum += word(src[i], src[i + 1])
        for (i in 0 until 16 step 2) sum += word(dst[i], dst[i + 1])
        sum += (udpLen shr 16); sum += (udpLen and 0xFFFF)
        sum += 17
        sum += udpSum
        return finish(sum)
    }
}
