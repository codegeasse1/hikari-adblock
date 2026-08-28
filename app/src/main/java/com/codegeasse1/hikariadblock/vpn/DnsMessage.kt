package com.codegeasse1.hikariadblock.vpn

object DnsMessage {

    fun questionName(packet: ByteArray, offset: Int): String? {
        if (packet.size < offset + 12) return null
        val qdcount = ((packet[offset + 4].toInt() and 0xFF) shl 8) or (packet[offset + 5].toInt() and 0xFF)
        if (qdcount < 1) return null
        var pos = offset + 12
        val sb = StringBuilder()
        var jumped = false
        var jumps = 0
        while (true) {
            if (pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= packet.size) return null
                val ptr = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (ptr >= packet.size) return null
                if (!jumped) pos += 2
                if (++jumps > 32) return null
                pos = ptr
                jumped = true
                continue
            }
            if (len > 63) return null
            if (pos + 1 + len > packet.size) return null
            if (sb.length > 0) sb.append('.')
            for (i in 0 until len) sb.append((packet[pos + 1 + i].toInt() and 0xFF).toChar())
            pos += 1 + len
        }
        return sb.toString().lowercase()
    }

    fun buildNxDomain(query: ByteArray): ByteArray {
        if (query.size < 12) return query.copyOf()
        var pos = 12
        var jumped = false
        var jumps = 0
        while (true) {
            if (pos >= query.size) return query.copyOf()
            val len = query[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= query.size) return query.copyOf()
                if (!jumped) pos += 2
                if (++jumps > 32) return query.copyOf()
                jumped = true
                break
            }
            if (len > 63) return query.copyOf()
            if (pos + 1 + len > query.size) return query.copyOf()
            pos += 1 + len
        }
        var qEnd = pos + 4
        if (qEnd > query.size) qEnd = query.size
        val resp = ByteArray(12 + (qEnd - 12))
        query.copyInto(resp, 0, 0, qEnd)
        resp[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
        resp[3] = 0x83.toByte()
        resp[4] = 0.toByte(); resp[5] = 1.toByte()
        for (i in 6..11) resp[i] = 0.toByte()
        return resp
    }
}
