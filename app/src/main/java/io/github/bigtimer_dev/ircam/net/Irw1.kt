package io.github.bigtimer_dev.ircam.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * IRW1, the wireless P2 Pro bridge protocol (see ircam-linux docs/WIRELESS_PLAN.md).
 *
 * Frames arrive over UDP as independently decodable row bands of the camera's temperature half
 * (256x192 u16). Codec 1 = per band: horizontal delta, zigzag, hi/lo byte planes, zlib.
 */
object Irw1 {
    const val DATA_PORT = 47800
    const val CTRL_PORT = 47801
    const val DEFAULT_HOST = "p2pro.local"
    const val HOTSPOT_IP = "10.42.0.1"
    const val VERSION = 1
    const val CODEC_RAW = 0
    const val CODEC_ZLIB = 1
    const val FLAG_HIGH_RANGE = 1
    const val HEADER_SIZE = 24
    const val WIDTH = 256
    const val FRAME_ROWS = 384
    const val TEMP_ROW0 = 192
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'R'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())
    private val SUB_MAGIC = "IRWS".toByteArray()

    class ProtocolException(msg: String) : Exception(msg)

    class Band(val codec: Int, val flags: Int, val frameId: Long, val tsUs: Long, val row0: Int, val rows: Int)

    fun parseHeader(d: ByteArray, len: Int): Band {
        if (len < HEADER_SIZE) throw ProtocolException("short datagram")
        for (i in 0..3) if (d[i] != MAGIC[i]) throw ProtocolException("not IRW1")
        val b = ByteBuffer.wrap(d, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        if (b.get(4).toInt() != VERSION) throw ProtocolException("unsupported version")
        return Band(
            codec = b.get(5).toInt() and 0xFF,
            flags = b.getShort(6).toInt() and 0xFFFF,
            frameId = b.getInt(8).toLong() and 0xFFFFFFFFL,
            tsUs = b.getLong(12),
            row0 = b.getShort(20).toInt() and 0xFFFF,
            rows = b.getShort(22).toInt() and 0xFFFF,
        )
    }

    /** Decodes a band payload into [out] (u16 values, row-major, width [WIDTH]) at row [row0]. */
    fun decodeBand(codec: Int, d: ByteArray, off: Int, len: Int, rows: Int, out: IntArray, row0: Int, inflater: Inflater, scratch: ByteArray? = null) {
        val n = rows * WIDTH
        val base = row0 * WIDTH
        when (codec) {
            CODEC_RAW -> {
                if (len != 2 * n) throw ProtocolException("raw band has the wrong size")
                for (i in 0 until n) {
                    out[base + i] = (d[off + 2 * i].toInt() and 0xFF) or ((d[off + 2 * i + 1].toInt() and 0xFF) shl 8)
                }
            }
            CODEC_ZLIB -> {
                val planes = if (scratch != null && scratch.size == 2 * n) scratch else ByteArray(2 * n)
                inflater.reset()
                inflater.setInput(d, off, len)
                val got = try {
                    var total = 0
                    while (total < planes.size && !inflater.finished()) {
                        val k = inflater.inflate(planes, total, planes.size - total)
                        if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        total += k
                    }
                    total
                } catch (e: DataFormatException) {
                    throw ProtocolException("bad zlib band")
                }
                if (got != planes.size || !inflater.finished()) throw ProtocolException("zlib band has the wrong size")
                for (r in 0 until rows) {
                    var acc = 0
                    for (x in 0 until WIDTH) {
                        val i = r * WIDTH + x
                        val z = ((planes[i].toInt() and 0xFF) shl 8) or (planes[n + i].toInt() and 0xFF)
                        val s = (z ushr 1) xor -(z and 1)
                        acc = (acc + s) and 0xFFFF
                        out[base + i] = acc
                    }
                }
            }
            else -> throw ProtocolException("unknown codec $codec")
        }
    }

    fun subscribePacket(token: String): ByteArray = SUB_MAGIC + token.toByteArray().copyOf(minOf(16, token.length))

    /** True when frame id [a] comes after [b] (32-bit wrap-around). */
    fun newer(a: Long, b: Long): Boolean {
        val d = (a - b) and 0xFFFFFFFFL
        return d in 1 until 0x80000000L
    }
}

/**
 * Collects row bands into frames. A frame is published when all rows have arrived, or when a
 * newer frame starts (missing rows keep the previous frame's values). No retransmission.
 */
class Irw1Assembler(val rows: Int) {
    /** [values] is the assembler's own buffer: valid until the next [feed] call. */
    class Frame(val values: IntArray, val frameId: Long, val tsUs: Long, val flags: Int)

    private val buf = IntArray(rows * Irw1.WIDTH)
    private val have = BooleanArray(rows)
    private var haveCount = 0
    private var fid = -1L
    private var done = false
    private var ts = 0L
    private var flags = 0
    private val inflater = Inflater()
    private val scratch = HashMap<Int, ByteArray>()
    var frames = 0L; private set
    var partial = 0L; private set
    var lostRows = 0L; private set
    var bad = 0L; private set

    private fun publish(): Frame {
        val missing = rows - haveCount
        frames++
        if (missing > 0) { partial++; lostRows += missing }
        done = true
        return Frame(buf, fid, ts, flags)
    }

    /** Feeds one datagram; returns 0, 1 or 2 finished frames. */
    fun feed(d: ByteArray, len: Int): List<Frame> {
        val band = try {
            val b = Irw1.parseHeader(d, len)
            if (b.rows == 0 || b.row0 + b.rows > rows) throw Irw1.ProtocolException("band outside the frame")
            b
        } catch (e: Irw1.ProtocolException) { bad++; return emptyList() }
        val out = ArrayList<Frame>(2)
        if (fid < 0 || Irw1.newer(band.frameId, fid)) {
            if (fid >= 0 && !done) out.add(publish())
            fid = band.frameId; ts = band.tsUs; flags = band.flags; done = false
            have.fill(false); haveCount = 0
        } else if (band.frameId != fid || done) {
            return out
        }
        try {
            val sc = scratch.getOrPut(band.rows) { ByteArray(2 * band.rows * Irw1.WIDTH) }
            Irw1.decodeBand(band.codec, d, Irw1.HEADER_SIZE, len - Irw1.HEADER_SIZE, band.rows, buf, band.row0, inflater, sc)
        } catch (e: Irw1.ProtocolException) { bad++; return out }
        for (r in band.row0 until band.row0 + band.rows) if (!have[r]) { have[r] = true; haveCount++ }
        if (haveCount == rows) out.add(publish())
        return out
    }

    fun loss(): Double = if (frames > 0) lostRows.toDouble() / (frames * rows) else 0.0
}
