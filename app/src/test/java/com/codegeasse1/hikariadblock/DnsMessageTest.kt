package com.codegeasse1.hikariadblock

import com.codegeasse1.hikariadblock.vpn.DnsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {

    private fun buildQuery(name: String): ByteArray {
        val parts = name.split(".")
        var len = 12
        for (p in parts) len += 1 + p.length
        len += 1 + 4
        val b = ByteArray(len)
        b[5] = 1 // QDCOUNT = 1
        var pos = 12
        for (p in parts) {
            b[pos] = p.length.toByte()
            for (i in p.indices) b[pos + 1 + i] = p[i].code.toByte()
            pos += 1 + p.length
        }
        b[pos] = 0
        pos++
        b[pos] = 0; b[pos + 1] = 1 // QTYPE A
        b[pos + 2] = 0; b[pos + 3] = 1 // QCLASS IN
        return b
    }

    @Test
    fun parsesSimpleName() {
        val q = buildQuery("ads.example.com")
        assertEquals("ads.example.com", DnsMessage.questionName(q, 0))
    }

    @Test
    fun parsesSingleLabel() {
        val q = buildQuery("localhost")
        assertEquals("localhost", DnsMessage.questionName(q, 0))
    }

    @Test
    fun rejectsEmptyPacket() {
        assertNull(DnsMessage.questionName(ByteArray(0), 0))
    }

    @Test
    fun nxDomainEchoesIdAndQuestion() {
        val q = buildQuery("ads.doubleclick.net")
        q[0] = 0x12; q[1] = 0x34
        val r = DnsMessage.buildNxDomain(q)
        assertEquals(q[0], r[0])
        assertEquals(q[1], r[1])
        assertEquals(0x81.toByte(), r[2]) // QR + RD
        assertEquals(0x83.toByte(), r[3]) // RA + NXDOMAIN
        assertEquals("ads.doubleclick.net", DnsMessage.questionName(r, 0))
        assertEquals(0, r[7]) // ANCOUNT = 0
        assertEquals(1, r[5]) // QDCOUNT = 1
    }

    @Test
    fun nxDomainDropsAdditionalRecords() {
        val q = buildQuery("ads.example.com")
        val withExtra = q + byteArrayOf(0, 0, 1, 2, 3)
        val r = DnsMessage.buildNxDomain(withExtra)
        assertEquals(q.size, r.size)
    }
}
