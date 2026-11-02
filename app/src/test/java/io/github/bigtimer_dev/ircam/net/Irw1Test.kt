package io.github.bigtimer_dev.ircam.net

import io.github.bigtimer_dev.ircam.thermal.Mdns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Irw1Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The same test pattern the Python generator used. */
    private fun expected(rows: Int): IntArray = IntArray(rows * 256) { i ->
        val y = i / 256; val x = i % 256
        if (y == 3) (if (x % 2 == 0) 65530 else 5)
        else (20000 + x * 13 + y * 101 + ((x * 7919 + y * 104729) % 97)) and 0xFFFF
    }

    @Test fun decodesZlibBandsFromThePythonBridge() {
        val a = Irw1Assembler(16)
        val frames = Irw1Vectors.zlib.flatMap { val d = hex(it); a.feed(d, d.size) }
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(7L, f.frameId)
        assertEquals(123456789L, f.tsUs)
        assertEquals(Irw1.FLAG_HIGH_RANGE, f.flags)
        assertArrayEquals(expected(16), f.values)
        assertEquals(0.0, a.loss(), 0.0)
    }

    @Test fun decodesRawBands() {
        val a = Irw1Assembler(2)
        val d = hex(Irw1Vectors.raw[0])
        val f = a.feed(d, d.size).single()
        assertArrayEquals(expected(2), f.values)
    }

    @Test fun missingBandIsFilledFromPreviousFrameAndCounted() {
        val a = Irw1Assembler(16)
        val d0 = hex(Irw1Vectors.zlib[0]); val d1 = hex(Irw1Vectors.zlib[1])
        a.feed(d0, d0.size); a.feed(d1, d1.size)               // frame 7 complete
        val next = d0.copyOf().also { it[8] = 9 }               // frame 9, first band only
        assertTrue(a.feed(next, next.size).isEmpty())
        val newer = d0.copyOf().also { it[8] = 10 }              // frame 10 starts -> 9 published
        val out = a.feed(newer, newer.size)
        assertEquals(1, out.size)
        assertEquals(9L, out[0].frameId)
        assertArrayEquals(expected(16), out[0].values)          // second band kept from frame 7
        assertTrue(a.loss() > 0)
    }

    @Test fun staleDuplicateAndJunkDatagramsAreIgnored() {
        val a = Irw1Assembler(16)
        val d0 = hex(Irw1Vectors.zlib[0]); val d1 = hex(Irw1Vectors.zlib[1])
        a.feed(d0, d0.size); a.feed(d1, d1.size)
        assertTrue(a.feed(d1, d1.size).isEmpty())                // duplicate of a finished frame
        val old = d0.copyOf().also { it[8] = 6 }
        assertTrue(a.feed(old, old.size).isEmpty())              // older frame
        val junk = ByteArray(40) { 1 }
        assertTrue(a.feed(junk, junk.size).isEmpty())
        val bad = d0.copyOf(d0.size - 5)
        val fresh = Irw1Assembler(16)
        assertTrue(fresh.feed(bad, bad.size).isEmpty())          // truncated zlib payload
        assertEquals(1L, fresh.bad)
    }

    @Test fun frameIdWrapsAround() {
        assertTrue(Irw1.newer(0, 0xFFFFFFFFL))
        assertTrue(Irw1.newer(5, 3))
        assertTrue(!Irw1.newer(3, 5))
    }

    @Test fun mdnsQueryAndAnswer() {
        val q = Mdns.query("p2pro.local")
        assertEquals(12 + 1 + 5 + 1 + 5 + 1 + 4, q.size)
        // answer: header (1 answer), name with compression pointer to offset 12, A 192.168.1.10
        val resp = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0) +
            byteArrayOf(5) + "p2pro".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 1, 10)
        assertEquals("192.168.1.10", Mdns.answer(resp, resp.size, "p2pro.local")?.hostAddress)
        assertNull(Mdns.answer(resp, resp.size, "other.local"))
    }
}
