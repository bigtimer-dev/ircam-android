package io.github.bigtimer_dev.ircam.thermal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * RAW snapshot files (SnapShotRAW_*.png), interchangeable with IRCAM Thermal Viewer on Windows
 * and ircam-linux: an RGB24 PNG of the camera frame size whose byte stream is the raw camera
 * frame, with an identification row ("IRCAM.RAM" + pool, geometry and configuration) in the
 * last pixel row.
 */
data class RawMeta(
    val pool: Int,
    val metaRows: Int,
    val widthOffset: Int,
    val heightOffset: Int,
    val tempCorrection: Double,
    val ambient: Double,
    val reflected: Double,
    val humidity: Double,
    val emissivity: Double,
    val distance: Int,
) {
    companion object {
        fun of(cam: CameraState) = RawMeta(
            cam.pool, cam.metaRows, cam.widthOffset, cam.heightOffset, cam.tempCorrection,
            cam.ambient, cam.reflected, cam.humidity, cam.emissivity, cam.distance.toInt(),
        )
    }
}

class RawImage(val rgb: ByteArray, val width: Int, val height: Int, val meta: RawMeta)

object RawFiles {
    private val ID = "IRCAM.RAM".toByteArray(Charsets.US_ASCII)

    private fun addIdRow(buf: ByteArray, width: Int, height: Int, m: RawMeta) {
        val bb = ByteBuffer.allocate(39) // big-endian
        bb.put(ID)
        bb.put((m.pool and 0xFF).toByte())
        bb.putShort(m.metaRows.toShort()).putShort(m.widthOffset.toShort()).putShort(m.heightOffset.toShort())
        bb.putFloat(m.tempCorrection.toFloat()).putFloat(m.ambient.toFloat()).putFloat(m.reflected.toFloat())
        bb.putFloat(m.humidity.toFloat()).putFloat(m.emissivity.toFloat())
        bb.putShort(m.distance.toShort())
        bb.put('!'.code.toByte())
        val row = bb.array()
        System.arraycopy(row, 0, buf, (height - 1) * width * 3, row.size)
    }

    fun readIdRow(buf: ByteArray, width: Int, height: Int): RawMeta? {
        val s = (height - 1) * width * 3
        if (s < 0 || s + 39 > buf.size) return null
        for (i in 0 until 8) if (buf[s + i] != ID[i]) return null
        val bb = ByteBuffer.wrap(buf, s, 39)
        bb.position(s + 9)
        val pool = bb.get().toInt() and 0xFF
        val meta = bb.short.toInt() and 0xFFFF
        val wo = bb.short.toInt() and 0xFFFF
        val ho = bb.short.toInt() and 0xFFFF
        val tc = bb.float.toDouble(); val amb = bb.float.toDouble(); val refl = bb.float.toDouble()
        val hum = bb.float.toDouble(); val emi = bb.float.toDouble()
        val dist = bb.short.toInt() and 0xFFFF
        if (pool !in 1..6 || meta >= height || wo >= width || ho >= height) return null
        return RawMeta(pool, meta, wo, ho, tc, amb, refl, hum, emi, dist)
    }

    /** Raw camera bytes -> RGB24 buffer with the ID row. */
    fun buildRawFrame(raw: ByteArray, width: Int, height: Int, meta: RawMeta): ByteArray {
        val buf = ByteArray(width * height * 3)
        System.arraycopy(raw, 0, buf, 0, minOf(raw.size, buf.size))
        addIdRow(buf, width, height, meta)
        return buf
    }

    /** Back to raw camera bytes (pool 5 packs 16-bit pixels as R=low, G=high). */
    fun unpack(img: RawImage): ByteArray {
        if (img.meta.pool != Pools.POOL_5) return img.rgb
        val n = img.rgb.size / 3
        val out = ByteArray(n * 2)
        for (i in 0 until n) { out[2 * i] = img.rgb[3 * i]; out[2 * i + 1] = img.rgb[3 * i + 1] }
        return out
    }

    // ------------------------------------------------------------------ PNG I/O

    /** Writes an 8-bit RGB PNG with exactly these bytes (filter type 0), no colour management. */
    fun writeRgbPng(out: OutputStream, rgb: ByteArray, width: Int, height: Int) {
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0).array()
        chunk(d, "IHDR", ihdr)
        val raw = ByteArray(height * (width * 3 + 1))
        for (y in 0 until height) System.arraycopy(rgb, y * width * 3, raw, y * (width * 3 + 1) + 1, width * 3)
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val tmp = ByteArray(65536)
        while (!def.finished()) { val n = def.deflate(tmp); z.write(tmp, 0, n) }
        def.end()
        chunk(d, "IDAT", z.toByteArray())
        chunk(d, "IEND", ByteArray(0))
        d.flush()
    }

    private fun chunk(d: DataOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    /** Loads a RAW snapshot; null when the PNG has no IRCAM identification row. */
    fun load(input: InputStream): RawImage? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = BitmapFactory.decodeStream(input, null, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[3 * i] = (c shr 16).toByte(); rgb[3 * i + 1] = (c shr 8).toByte(); rgb[3 * i + 2] = c.toByte()
        }
        val meta = readIdRow(rgb, w, h) ?: return null
        return RawImage(rgb, w, h, meta)
    }
}
