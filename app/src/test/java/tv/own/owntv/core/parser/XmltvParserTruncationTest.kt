package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/**
 * A guide download that is cut off must fail, not pass as the whole feed: EpgRepository prunes and
 * caches only after a parse that returns normally (upstream OwnTV_Core 991118af). Malformed XML is
 * still skipped as before. Synthetic feeds only.
 */
class XmltvParserTruncationTest {

    private fun feed(programmes: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        append("<channel id=\"synth.one\"><display-name>Synthetic One</display-name></channel>\n")
        append("<channel id=\"synth.two\"><display-name>Synthetic Two</display-name></channel>\n")
        repeat(programmes) { i ->
            val channel = if (i % 2 == 0) "synth.one" else "synth.two"
            append("<programme channel=\"$channel\" start=\"${hhmm(i)} +0000\" stop=\"${hhmm(i + 1)} +0000\">")
            append("<title>Programme $i</title><desc>Synthetic description number $i for padding.</desc></programme>\n")
        }
        append("</tv>\n")
    }

    /** `yyyyMMddHHmmss` for minute [i] after 2026-01-01 00:00. */
    private fun hhmm(i: Int): String {
        val day = 1 + i / 1440
        val h = (i % 1440) / 60
        val m = i % 60
        return "202601%02d%02d%02d00".format(day, h, m)
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private class Counts { var channels = 0; var programmes = 0 }

    private fun parse(input: InputStream, counts: Counts = Counts()): Counts = runBlocking {
        XmltvParser.parse(
            input,
            onChannel = { _, _, _ -> counts.channels++ },
            onProgramme = { _, _, _, _, _ -> counts.programmes++ },
        )
        counts
    }

    /** Delivers [limit] bytes of [data], then fails the way a dropped connection does. */
    private class DroppingStream(data: ByteArray, private val limit: Int, val failure: IOException) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        private var served = 0
        override fun read(): Int {
            if (served >= limit) throw failure
            val b = inner.read()
            if (b >= 0) served++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (served >= limit) throw failure
            val n = inner.read(b, off, minOf(len, limit - served))
            if (n > 0) served += n
            return n
        }
    }

    @Test
    fun `a complete feed parses fully, plain and gzipped`() {
        val text = feed(500)
        val plain = parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals(2, plain.channels)
        assertEquals(500, plain.programmes)

        val gz = parse(ByteArrayInputStream(gzip(text)))
        assertEquals(2, gz.channels)
        assertEquals(500, gz.programmes)
    }

    @Test
    fun `a gzip cut off mid-download throws instead of ending as a complete feed`() {
        val bytes = gzip(feed(3000))
        val cut = bytes.copyOf(bytes.size * 6 / 10)
        val counts = Counts()
        assertThrows(IOException::class.java) { parse(ByteArrayInputStream(cut), counts) }
        // What arrived before the cut was still delivered (EpgRepository keeps those rows).
        assertTrue("some programmes before the cut", counts.programmes in 1 until 3000)
    }

    @Test
    fun `a connection dropped mid-feed propagates its IOException`() {
        val bytes = feed(3000).toByteArray()
        val failure = IOException("synthetic connection drop")
        val counts = Counts()
        val thrown = assertThrows(IOException::class.java) {
            parse(DroppingStream(bytes, bytes.size / 2, failure), counts)
        }
        assertSame(failure, thrown)
        assertTrue(counts.programmes in 1 until 3000)
    }

    @Test
    fun `a connection dropped inside the gzip stream propagates too`() {
        val bytes = gzip(feed(3000))
        val failure = IOException("synthetic connection drop")
        assertThrows(IOException::class.java) { parse(DroppingStream(bytes, bytes.size / 2, failure)) }
    }

    @Test
    fun `malformed XML is still skipped and the rest of the feed kept`() {
        // An unescaped ampersand and a mismatched close tag inside one programme; relaxed parsing and the
        // per-element recovery keep going, exactly as before this change.
        val text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
            <channel id="synth.one"><display-name>Synthetic One</display-name></channel>
            <programme channel="synth.one" start="20260101000000 +0000" stop="20260101010000 +0000"><title>Good one</title></programme>
            <programme channel="synth.one" start="20260101010000 +0000" stop="20260101020000 +0000"><title>Tom & Jerry</title></programme>
            <programme channel="synth.one" start="20260101020000 +0000" stop="20260101030000 +0000"><title>Good three</title></programme>
            </tv>
        """.trimIndent()
        val counts = parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals(1, counts.channels)
        assertTrue("well-formed programmes kept", counts.programmes >= 2)
    }
}
