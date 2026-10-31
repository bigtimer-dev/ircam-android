package io.github.bigtimer_dev.ircam.thermal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Raw frame decoding and temperature maths, ported from ircam-linux thermal.py
 * (itself a port of RMH_ThermalCameraSupport_Library.cpp). The flat-array indexing of the
 * original is kept so readings match the desktop apps.
 */
object Pools {
    const val POOL_1 = 1
    const val POOL_2 = 2
    const val POOL_3 = 3
    const val POOL_4 = 4
    const val POOL_5 = 5
    const val POOL_6 = 6

    const val POOL2_W = 256
    const val POOL2_H = 384
    const val POOL2_HEIGHT_OFFSET = 192
    const val POOL4_HEIGHT_OFFSET = 194
    const val POOL5_WIDTH_OFFSET = 6
    const val POOL6_WIDTH_OFFSET = 5
    const val RES14 = 16383

    const val DEFAULT_TEMP_CORRECTION = 0.0
    const val DEFAULT_AMBIENT = 25.1
    const val DEFAULT_REFLECTED = 25.1
    const val DEFAULT_HUMIDITY = 0.45
    const val DEFAULT_EMISSIVITY = 0.98
    const val DEFAULT_DISTANCE = 1.0

    fun metadataRows(width: Int, height: Int) = (height - width / (4.0 / 3.0)).roundToInt()
}

class CameraState {
    var pool = 0
    var width = 0
    var height = 0
    var metaRows = 0
    var widthOffset = 0
    var heightOffset = 0
    var rangeFlag = 1 // 1 = low, 2 = high

    // pool 1/3 constants
    var fpaOff = 0.0
    var fpaDiv = 0.0
    var cal0Offset = 0.0
    var cal0FpaMul = 0.0
    var md1 = 0
    var md2 = 0
    var md3 = 0

    // per-frame metadata
    var tmaxX = 0; var tmaxY = 0; var tmaxRaw = 0
    var tminX = 0; var tminY = 0; var tminRaw = 0
    var tavgRaw = 0.0
    var centerRaw = 0
    var fpaRaw = 0; var shutterRaw = 0; var coreRaw = 0
    var tempFpa = 0.0; var tempShutter = 0.0; var tempCore = 0.0
    var cal = DoubleArray(6)

    // configuration
    var tempCorrection = Pools.DEFAULT_TEMP_CORRECTION
    var reflected = Pools.DEFAULT_REFLECTED
    var ambient = Pools.DEFAULT_AMBIENT
    var humidity = Pools.DEFAULT_HUMIDITY
    var emissivity = Pools.DEFAULT_EMISSIVITY
    var distance = Pools.DEFAULT_DISTANCE

    // derived
    var corrFactor = 1.0
    var corrOffset = 0.0
    var lut = DoubleArray(16384)

    val imageHeight get() = height - metaRows

    fun setGeometry(w: Int, h: Int, pool: Int) {
        this.pool = pool; width = w; height = h
        metaRows = Pools.metadataRows(w, h)
        frameOffsetsForPool()
        initConstants()
    }

    fun frameOffsetsForPool() {
        when (pool) {
            Pools.POOL_2 -> { widthOffset = 0; heightOffset = Pools.POOL2_HEIGHT_OFFSET }
            Pools.POOL_4 -> { widthOffset = 0; heightOffset = Pools.POOL4_HEIGHT_OFFSET }
            Pools.POOL_5 -> { widthOffset = Pools.POOL5_WIDTH_OFFSET; heightOffset = Pools.POOL4_HEIGHT_OFFSET }
            Pools.POOL_6 -> { widthOffset = Pools.POOL6_WIDTH_OFFSET; heightOffset = 0 }
            else -> { widthOffset = 0; heightOffset = 0 }
        }
    }

    fun initConstants() {
        val w = width
        val hm = height - metaRows
        if (pool == Pools.POOL_1 || pool == Pools.POOL_3) {
            cal0Offset = 390.0; cal0FpaMul = 7.05
            val m1 = w * hm
            var m2 = m1
            val row: Triple<Double, Double, Int>? = when (w) {
                640 -> Triple(6867.0, 33.8, 3)
                384 -> Triple(7800.0, 36.0, 3)
                256 -> Triple(8617.0, if (pool == Pools.POOL_1) 37.682 else 13.9, 1)
                240 -> Triple(7800.0, 36.0, 1)
                else -> null
            }
            if (row != null) {
                fpaOff = row.first; fpaDiv = row.second
                m2 = m1 + w * row.third
            }
            if (pool == Pools.POOL_1 && w == 256) { cal0Offset = 170.0; cal0FpaMul = 0.0 }
            md1 = m1 - 1; md2 = m2 - 1
            if (pool == Pools.POOL_3) md3 = w * hm
        } else if (pool == Pools.POOL_2) {
            md1 = w * hm
        } else {
            md1 = w * hm
            md2 = md1 + w
        }
    }

    fun resetConfigToDefaults() {
        tempCorrection = Pools.DEFAULT_TEMP_CORRECTION
        ambient = Pools.DEFAULT_AMBIENT
        reflected = Pools.DEFAULT_REFLECTED
        humidity = Pools.DEFAULT_HUMIDITY
        emissivity = Pools.DEFAULT_EMISSIVITY
        distance = Pools.DEFAULT_DISTANCE
    }
}

object Thermal {
    private fun u16x2ToFloat(hi: Int, lo: Int): Double =
        java.lang.Float.intBitsToFloat(((hi and 0xFFFF) shl 16) or (lo and 0xFFFF)).toDouble()

    /**
     * RMH_IRThermalCamera_ConvertYUY2To14BitThermalDataArray: flat uint16 values
     * (width*height, metadata rows included). For pools 2-6 max/min/centre pixel data is
     * written into the first metadata words, like the original.
     */
    fun decodeFrame(cam: CameraState, raw: ByteArray, out: IntArray? = null): IntArray {
        val w = cam.width
        val h = cam.height
        val n = w * h
        val td = if (out != null && out.size == n) out else IntArray(n)
        var p = (cam.heightOffset * w + cam.widthOffset) * 2
        for (i in 0 until n) {
            td[i] = if (p + 1 < raw.size) (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8) else 0
            p += 2
        }
        val imgN = w * (h - cam.metaRows)
        if (imgN <= 0) { cam.tavgRaw = 0.0; return td }
        var sum = 0L
        var imax = 0
        var imin = 0
        for (i in 0 until imgN) {
            val v = td[i]
            sum += v
            if (v > td[imax]) imax = i
            if (v < td[imin]) imin = i
        }
        cam.tavgRaw = sum.toDouble() / imgN
        if (cam.pool != Pools.POOL_1) {
            val hm = h - cam.metaRows
            val center = (hm * 0.5).toInt() * w + (w * 0.5).toInt()
            val centerVal = td[minOf(center, imgN - 1)]
            val vals = intArrayOf(imax % w, imax / w, td[imax], imin % w, imin / w, td[imin], centerVal)
            val k = maxOf(0, minOf(7, n - imgN))
            for (i in 0 until k) td[imgN + i] = vals[i]
        }
        return td
    }

    fun readMetadata(cam: CameraState, td: IntArray) {
        if (cam.metaRows >= cam.height) return
        fun g(i: Int) = if (i >= 0 && i < td.size) td[i] else 0
        val idx: IntArray
        if (cam.pool == Pools.POOL_1 || cam.pool == Pools.POOL_3) {
            cam.fpaRaw = g(cam.md1 + 2)
            cam.tempFpa = if (cam.fpaDiv != 0.0) 20.0 - (cam.fpaRaw - cam.fpaOff) / cam.fpaDiv else 0.0
            cam.shutterRaw = g(cam.md2 + 2)
            cam.coreRaw = g(cam.md2 + 3)
            if (cam.pool == Pools.POOL_1) {
                cam.tempShutter = cam.shutterRaw / 10.0 - 273.15
                cam.tempCore = cam.coreRaw / 10.0 - 273.15
                val b = cam.md1 + 3
                idx = intArrayOf(b, b + 1, b + 2, b + 3, b + 4, b + 5, cam.md1 + 13)
            } else {
                cam.tempShutter = ((cam.shutterRaw * 0.625 + 2731.5) / 10.0) - 273.15
                cam.tempCore = ((cam.coreRaw * 0.625 + 2731.5) / 10.0) - 273.15
                idx = IntArray(7) { cam.md3 + it }
            }
        } else {
            cam.fpaRaw = 1; cam.shutterRaw = 1; cam.coreRaw = 1
            cam.tempFpa = 1.0; cam.tempShutter = 1.0; cam.tempCore = 1.0
            idx = IntArray(7) { cam.md1 + it }
        }
        cam.tmaxX = g(idx[0]); cam.tmaxY = g(idx[1]); cam.tmaxRaw = g(idx[2])
        cam.tminX = g(idx[3]); cam.tminY = g(idx[4]); cam.tminRaw = g(idx[5])
        cam.centerRaw = g(idx[6])
    }

    fun readCalibration(cam: CameraState, td: IntArray) {
        if (cam.pool == Pools.POOL_1 || cam.pool == Pools.POOL_3) {
            fun g(i: Int) = if (i >= 0 && i < td.size) td[i] else 0
            val b = cam.md2 + 2
            cam.cal = DoubleArray(6) { k ->
                if (k == 0) g(cam.md2 + 1).toDouble() else u16x2ToFloat(g(b + 3 + 2 * (k - 1)), g(b + 4 + 2 * (k - 1)))
            }
        } else {
            cam.cal = DoubleArray(6)
        }
    }

    private fun waterVapour(humidity: Double, ambient: Double): Double {
        val h1 = 1.5587; val h2 = 0.069390; val h3 = -0.000278160; val h4 = 0.68455e-6
        return humidity * exp(ambient.pow(3) * h4 + ambient.pow(2) * h3 + ambient * h2 + h1)
    }

    private fun attenuation(omega: Double, distanceM: Double): Double {
        val d = (maxOf(0.0, distanceM).toInt() and 0xFFFF).toDouble() // unsigned short in the original
        val katm = 1.9; val a1 = 0.0066; val a2 = 0.0126; val b1 = -0.0023; val b2 = -0.0067
        val so = sqrt(maxOf(omega, 0.0))
        return katm * exp(-sqrt(d) * (a1 + b1 * so)) + (1 - katm) * exp(-sqrt(d) * (a2 + b2 * so))
    }

    /** RMH_IRThermalCamera_GenerateThermoGrapicLookUpTable. */
    fun generateLut(cam: CameraState) {
        val omega = waterVapour(cam.humidity, cam.ambient)
        val tau = attenuation(omega, cam.distance)
        if (cam.pool == Pools.POOL_1 || cam.pool == Pools.POOL_3) {
            val (c0, c1, c2) = Triple(cam.cal[0], cam.cal[1], cam.cal[2])
            val (c3, c4, c5) = Triple(cam.cal[3], cam.cal[4], cam.cal[5])
            if (c1 == 0.0 || cam.emissivity * tau == 0.0) { cam.lut = DoubleArray(16384); return }
            val num = (1.0 - cam.emissivity) * tau * (cam.reflected + 273.15).pow(4) + (1.0 - tau) * (cam.ambient + 273.15).pow(4)
            val den = cam.emissivity * tau
            val a = c2 / (c1 + c1)
            val b = c2 * c2 / (c1 * c1 * 4.0)
            val cc = c1 * cam.tempShutter * cam.tempShutter + cam.tempShutter * c2
            val d = c3 * cam.tempFpa * cam.tempFpa + c4 * cam.tempFpa + c5
            var corr = 0.0
            if (cam.rangeFlag == 1) corr = maxOf(0.0, cam.cal0Offset - cam.tempFpa * cam.cal0FpaMul)
            val off = c0 - corr
            val lut = DoubleArray(16384)
            for (i in 0 until 16384) {
                val s = ((i - off) * d + cc) / c1 + b
                val n = sqrt(abs(s))
                val wtot = (n - a + 273.15).pow(4)
                val ttot = ((wtot - num) / den).pow(0.25) - 273.15
                val v = ttot + (cam.distance * 0.85 - 1.125) * (ttot - cam.ambient) / 100.0 + cam.tempCorrection
                lut[i] = if (v.isNaN()) 0.0 else v
            }
            cam.lut = lut
        } else {
            val sigma = 0.0000000567
            val e = cam.emissivity
            val refl = (1.0 - e) * tau * sigma * cam.reflected
            val amb = (1.0 - tau) * sigma * cam.ambient
            val core = if (cam.tempCore != 0.0) (1.0 - cam.tempShutter / cam.tempCore) * sigma * cam.tempShutter else 0.0
            val ets = if (e * tau != 0.0) 1.0 / (e * tau * sigma) else 0.0
            cam.corrOffset = -(refl * ets) - (amb * ets) - (core * ets)
            cam.corrFactor = ets * sigma * e
        }
    }

    /** RMH_IRThermalCamera_ReadPixelTemperature, in degrees Celsius. */
    fun toCelsius(cam: CameraState, raw: Double): Double = when (cam.pool) {
        Pools.POOL_1, Pools.POOL_3 -> cam.lut[raw.toInt() and 0x3FFF]
        Pools.POOL_6 -> raw / 10.0 - 273.15 + cam.tempCorrection
        else -> (raw * 0.015625 - 273.15) * cam.corrFactor + cam.corrOffset + cam.tempCorrection
    }
}
