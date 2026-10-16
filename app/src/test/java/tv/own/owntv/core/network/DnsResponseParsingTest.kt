package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * DnsConfigHolder.parseDnsResponse / dohWireUrl / parseDohJson (upstream OwnTV_Core e6282f29).
 *
 * The parser used to start the question section at byte 8 (after QDCOUNT/ANCOUNT) instead of the end of
 * the 12-byte header, so every real answer parsed as nothing and custom plain DNS silently fell back to
 * the system resolver.
 */
class DnsResponseParsingTest {

    private val parse = DnsConfigHolder.Companion::parseDnsResponse

    // --- response builder -------------------------------------------------------------------------

    private class Msg(val id: Int, flags: Int = 0x8180, qd: Int = 1, an: Int, ns: Int = 0, ar: Int = 0) {
        val out = ByteArrayOutputStream()
        init { short(id); short(flags); short(qd); short(an); short(ns); short(ar) }
        fun short(v: Int) = apply { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun bytes(vararg b: Int) = apply { b.forEach { out.write(it and 0xFF) } }
        fun name(host: String) = apply {
            host.split('.').forEach { out.write(it.length); out.write(it.encodeToByteArray()) }
            out.write(0)
        }
        fun question(host: String, qtype: Int = 1) = name(host).short(qtype).short(1)
        /** An answer whose owner is a compression pointer back to the question name (offset 12). */
        fun answer(type: Int, rdata: ByteArray, ttl: Int = 300) = apply {
            short(0xC00C); short(type); short(1); short(ttl ushr 16); short(ttl and 0xFFFF)
            short(rdata.size); out.write(rdata)
        }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun ipv4(vararg b: Int) = ByteArray(4) { b[it].toByte() }

    // --- valid responses ----------------------------------------------------------------------------

    @Test
    fun `A answer after the question is read`() {
        val msg = Msg(id = 0x1234, an = 1).question("example.com").answer(1, ipv4(93, 184, 216, 34)).build()
        val result = parse(msg, 0x1234)
        assertEquals(listOf("93.184.216.34"), result.map { it.hostAddress })
        assertTrue(result.single() is Inet4Address)
    }

    @Test
    fun `nonzero NSCOUNT and ARCOUNT do not shift the question section`() {
        // The old parser started at byte 8, i.e. inside NSCOUNT/ARCOUNT. Non-zero values there made the
        // misread visible as garbage; zero values made it skip the question wrongly. Both must parse.
        val msg = Msg(id = 7, an = 1, ns = 1, ar = 1).question("panel.example.com").answer(1, ipv4(10, 0, 0, 1)).build()
        assertEquals(listOf("10.0.0.1"), parse(msg, 7).map { it.hostAddress })
    }

    @Test
    fun `CNAME chain is skipped and the final A and AAAA are kept`() {
        val cname = ByteArrayOutputStream().apply {
            "edge.cdn.example".split('.').forEach { write(it.length); write(it.encodeToByteArray()) }; write(0)
        }.toByteArray()
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 1 }
        val msg = Msg(id = 9, an = 3).question("www.example.com")
            .answer(5, cname)
            .answer(1, ipv4(192, 0, 2, 10))
            .answer(28, v6)
            .build()
        val result = parse(msg, 9)
        assertEquals(2, result.size)
        assertTrue(result[0] is Inet4Address)
        assertTrue(result[1] is Inet6Address)
        assertEquals("192.0.2.10", result[0].hostAddress)
    }

    @Test
    fun `answers without an expected id are accepted (DoH uses id 0)`() {
        val msg = Msg(id = 0, an = 1).question("dns.google").answer(1, ipv4(8, 8, 8, 8)).build()
        assertEquals(listOf("8.8.8.8"), parse(msg, 0).map { it.hostAddress })
    }

    // --- rejected responses -------------------------------------------------------------------------

    @Test
    fun `a different query id is rejected`() {
        val msg = Msg(id = 0x1111, an = 1).question("example.com").answer(1, ipv4(1, 2, 3, 4)).build()
        assertTrue(parse(msg, 0x2222).isEmpty())
    }

    @Test
    fun `an error rcode or a query (not a response) yields nothing`() {
        val nxdomain = Msg(id = 1, flags = 0x8183, an = 0).question("nope.invalid").build()
        val query = Msg(id = 1, flags = 0x0100, an = 1).question("example.com").answer(1, ipv4(1, 2, 3, 4)).build()
        assertTrue(parse(nxdomain, 1).isEmpty())
        assertTrue(parse(query, 1).isEmpty())
    }

    // --- malformed and truncated ----------------------------------------------------------------------

    @Test
    fun `shorter than a header yields nothing`() {
        assertTrue(parse(ByteArray(0), null).isEmpty())
        assertTrue(parse(ByteArray(11), null).isEmpty())
    }

    @Test
    fun `truncated answer rdata keeps what was complete and does not throw`() {
        val full = Msg(id = 3, an = 2).question("example.com")
            .answer(1, ipv4(1, 1, 1, 1))
            .answer(1, ipv4(2, 2, 2, 2))
            .build()
        val cut = full.copyOf(full.size - 2)
        assertEquals(listOf("1.1.1.1"), parse(cut, 3).map { it.hostAddress })
    }

    @Test
    fun `truncated inside the question yields nothing`() {
        val full = Msg(id = 4, an = 1).question("example.com").answer(1, ipv4(1, 1, 1, 1)).build()
        assertTrue(parse(full.copyOf(16), 4).isEmpty())
    }

    @Test
    fun `a label length running past the end yields nothing`() {
        val msg = Msg(id = 5, an = 1).bytes(63, 'a'.code).build()
        assertTrue(parse(msg, 5).isEmpty())
    }

    @Test
    fun `an A record with the wrong length is ignored`() {
        val msg = Msg(id = 6, an = 1).question("example.com").answer(1, byteArrayOf(1, 2, 3)).build()
        assertTrue(parse(msg, 6).isEmpty())
    }

    @Test
    fun `answer count larger than the records present stops cleanly`() {
        val msg = Msg(id = 8, an = 5).question("example.com").answer(1, ipv4(4, 4, 4, 4)).build()
        assertEquals(listOf("4.4.4.4"), parse(msg, 8).map { it.hostAddress })
    }

    // --- query building and RFC 8484 ------------------------------------------------------------------

    @Test
    fun `query round trips through the parser's question skip`() {
        val q = DnsConfigHolder.buildDnsQuery("panel.example.com", 1, id = 42)
        assertEquals(12 + "panel.example.com".length + 2 + 4, q.size)
        assertEquals(42, ((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF))
    }

    @Test
    fun `wire url is the RFC 8484 example encoding`() {
        // RFC 8484 §4.1.1: www.example.com, type A, id 0, RD set.
        assertEquals(
            "https://dns.example/dns-query?dns=AAABAAABAAAAAAAAA3d3dwdleGFtcGxlA2NvbQAAAQAB",
            DnsConfigHolder.dohWireUrl("https://dns.example/dns-query/", "www.example.com", 1),
        )
        assertTrue(DnsConfigHolder.dohWireUrl("https://x.example/q?ct=1", "a.b", 28).contains("?ct=1&dns="))
    }

    @Test
    fun `DoH presets are unchanged`() {
        assertEquals(
            listOf("https://dns.google/dns-query", "https://cloudflare-dns.com/dns-query", "https://dns.quad9.net/dns-query"),
            DohPresets.all.map { it.second },
        )
    }

    @Test
    fun `json answers keep only A and AAAA literals, never resolving a CNAME target`() {
        val json = """{"Status":0,"Answer":[
            {"name":"www.example.com.","type":5,"data":"edge.example.net."},
            {"name":"edge.example.net.","type":1,"data":"203.0.113.5"},
            {"name":"edge.example.net.","type":28,"data":"2001:db8::5"},
            {"name":"edge.example.net.","type":1,"data":"not-an-ip.example"}
        ]}"""
        val result = DnsConfigHolder.parseDohJson(json)
        assertEquals(listOf("203.0.113.5"), result.filterIsInstance<Inet4Address>().map { it.hostAddress })
        assertEquals(1, result.filterIsInstance<Inet6Address>().size)
        assertEquals(2, result.size)
    }

    @Test
    fun `json without answers yields nothing`() {
        assertTrue(DnsConfigHolder.parseDohJson("""{"Status":3}""").isEmpty())
    }
}
