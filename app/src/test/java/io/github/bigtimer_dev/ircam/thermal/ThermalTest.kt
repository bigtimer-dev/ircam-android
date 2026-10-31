package io.github.bigtimer_dev.ircam.thermal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** Compares the Kotlin port against values computed by ircam-linux (tools/make_test_vectors.py). */
class ThermalTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())

    private fun pool2Frame(): ByteArray {
        val w = 256
        val out = ByteArray(w * 384 * 2)
        val half = w * 192 * 2
        for (y in 0 until 192) for (x in 0 until w) {
            var s = 22.0 + 4.0 * (y / 192.0) + 1.5 * sin(x / 25.0)
            s += 48.0 * exp(-(((x - 150.0) * (x - 150.0) + (y - 80.0) * (y - 80.0)) / (2 * 14.0 * 14.0)))
            s -= 14.0 * exp(-(((x - 200.0) * (x - 200.0) + (y - 50.0) * (y - 50.0)) / (2 * 10.0 * 10.0)))
            val raw = ((s + 273.15) * 64.0).toInt().coerceIn(0, 65535)
            val img = raw shr 6
            val i = y * w + x
            out[2 * i] = img.toByte(); out[2 * i + 1] = (img shr 8).toByte()
            out[half + 2 * i] = raw.toByte(); out[half + 2 * i + 1] = (raw shr 8).toByte()
        }
        return out
    }

    private fun pool2Cam() = CameraState().apply {
        setGeometry(256, 384, Pools.POOL_2)
        emissivity = 0.9; ambient = 20.0; reflected = 30.0; humidity = 0.5; distance = 3.0; tempCorrection = 0.5
        Thermal.generateLut(this)
    }

    @Test
    fun pool2MatchesPython() {
        val p = v.getJSONObject("pool2")
        val cam = pool2Cam()
        assertEquals(p.getDouble("corr_factor"), cam.corrFactor, 1e-12)
        assertEquals(p.getDouble("corr_offset"), cam.corrOffset, 1e-9)
        val td = Thermal.decodeFrame(cam, pool2Frame())
        Thermal.readMetadata(cam, td)
        val tol = 0.02
        assertEquals(p.getDouble("tmax"), Thermal.toCelsius(cam, cam.tmaxRaw.toDouble()), tol)
        assertEquals(p.getDouble("tmin"), Thermal.toCelsius(cam, cam.tminRaw.toDouble()), tol)
        assertEquals(p.getDouble("center"), Thermal.toCelsius(cam, cam.centerRaw.toDouble()), tol)
        assertEquals(p.getDouble("avg"), Thermal.toCelsius(cam, cam.tavgRaw), tol)
        assertEquals(p.getJSONArray("tmax_xy").getInt(0), cam.tmaxX)
        assertEquals(p.getJSONArray("tmax_xy").getInt(1), cam.tmaxY)
        assertEquals(p.getJSONArray("tmin_xy").getInt(0), cam.tminX)
        assertEquals(p.getJSONArray("tmin_xy").getInt(1), cam.tminY)
        val px = p.getJSONArray("pixels")
        for (k in 0 until px.length()) {
            val e = px.getJSONArray(k)
            val t = Thermal.toCelsius(cam, td[e.getInt(1) * 256 + e.getInt(0)].toDouble())
            assertEquals("pixel $k", e.getDouble(2), t, tol)
        }
    }

    @Test
    fun rawIdRowIsByteCompatible() {
        val cam = pool2Cam()
        val meta = RawMeta.of(cam)
        val buf = RawFiles.buildRawFrame(pool2Frame(), 256, 384, meta)
        val s = 383 * 256 * 3
        val hex = buf.copyOfRange(s, s + 39).joinToString("") { "%02x".format(it) }
        assertEquals(v.getString("id_row_hex"), hex)
        val back = RawFiles.readIdRow(buf, 256, 384)!!
        assertEquals(meta.pool, back.pool)
        assertEquals(meta.heightOffset, back.heightOffset)
        assertEquals(0.9, back.emissivity, 1e-6)
        assertEquals(3, back.distance)
    }

    @Test
    fun pool1LutMatchesPython() {
        val cam = CameraState().apply {
            pool = Pools.POOL_1; width = 256; height = 196
            metaRows = Pools.metadataRows(256, 196)
            initConstants()
            cal = doubleArrayOf(8000.0, 1.0, 0.0, 0.0, 0.0, 0.01)
            tempShutter = 30.0; tempFpa = 35.0
            emissivity = 0.95; ambient = 22.0; reflected = 24.0; humidity = 0.4; distance = 2.0
        }
        val md = v.getJSONArray("pool1_md")
        assertEquals(md.getInt(0), cam.md1)
        assertEquals(md.getInt(1), cam.md2)
        assertEquals(md.getInt(2), cam.metaRows)
        Thermal.generateLut(cam)
        val lut = v.getJSONArray("pool1_lut")
        for (k in 0 until lut.length()) {
            val e = lut.getJSONArray(k)
            assertEquals("lut[${e.getInt(0)}]", e.getDouble(1), cam.lut[e.getInt(0)], 1e-9)
        }
    }
}
