package io.github.bigtimer_dev.ircam.thermal

import android.graphics.Bitmap
import io.github.bigtimer_dev.ircam.data.Settings
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A temperature spot, in displayed-image pixel coordinates. */
data class Spot(val x: Float, val y: Float)

/** A measurement box, in displayed-image pixel coordinates (x0 < x1, y0 < y1). */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun normalized() = Box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
}

data class AreaStat(
    val max: Double, val min: Double, val avg: Double,
    val maxX: Int, val maxY: Int, val minX: Int, val minY: Int,
)

class FrameResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    /** Celsius, row-major in displayed orientation. */
    val temps: FloatArray,
    val tmax: Double,
    val tmin: Double,
    val tcenter: Double,
    val tavg: Double,
    val maxX: Int, val maxY: Int,
    val minX: Int, val minY: Int,
    val barLo: Double,
    val barHi: Double,
    val palette: IntArray,
    val spots: List<Pair<Spot, Double>>,
    val boxes: List<Pair<Box, AreaStat>>,
    val frameNo: Long,
    val fps: Double,
) {
    fun tempAt(x: Float, y: Float): Double {
        val px = x.toInt().coerceIn(0, width - 1)
        val py = y.toInt().coerceIn(0, height - 1)
        return temps[py * width + px].toDouble()
    }
}

/**
 * Turns raw frames into a coloured image plus measurements. Mirrors Engine.process of
 * ircam-linux (decode -> temperatures -> orientation -> AGC -> sharpening -> palette).
 */
class Processor(private val palettes: Palettes) {
    private var td = IntArray(0)
    private val bitmaps = ArrayDeque<Bitmap>()
    private var kernel: DoubleArray? = null

    private fun nextBitmap(w: Int, h: Int): Bitmap {
        bitmaps.removeAll { it.width != w || it.height != h }
        if (bitmaps.size < 4) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmaps.addLast(b)
            return b
        }
        val b = bitmaps.removeFirst()
        bitmaps.addLast(b)
        return b
    }

    fun process(
        cam: CameraState,
        raw: ByteArray,
        s: Settings,
        spots: List<Spot>,
        boxes: List<Box>,
        frameNo: Long,
        fps: Double,
    ): FrameResult {
        td = Thermal.decodeFrame(cam, raw, td)
        Thermal.readMetadata(cam, td)
        val sw = cam.width
        val sh = cam.imageHeight
        val rot = ((s.rotation / 90) % 4 + 4) % 4
        val w = if (rot % 2 == 0) sw else sh
        val h = if (rot % 2 == 0) sh else sw
        val n = w * h
        val temps = FloatArray(n)
        val rawImg = IntArray(n)
        val linear = cam.pool != Pools.POOL_1 && cam.pool != Pools.POOL_3
        var imax = 0
        var imin = 0
        for (y in 0 until h) for (x in 0 until w) {
            val xm = if (s.mirror) w - 1 - x else x
            // map the displayed pixel back to the sensor pixel (rotation is clockwise)
            val sx = when (rot) { 1 -> y; 2 -> sw - 1 - xm; 3 -> sw - 1 - y; else -> xm }
            val sy = when (rot) { 1 -> sh - 1 - xm; 2 -> sh - 1 - y; 3 -> xm; else -> y }
            val v = td[sy * sw + sx]
            val o = y * w + x
            rawImg[o] = v
            val t = Thermal.toCelsius(cam, v.toDouble()).toFloat()
            temps[o] = t
            if (t > temps[imax]) imax = o
            if (t < temps[imin]) imin = o
        }
        val tmax = Thermal.toCelsius(cam, cam.tmaxRaw.toDouble())
        val tmin = Thermal.toCelsius(cam, cam.tminRaw.toDouble())
        val tcenter = temps[(h / 2) * w + w / 2].toDouble()
        val tavg = Thermal.toCelsius(cam, if (linear) cam.tavgRaw else cam.tavgRaw.toInt().toDouble())

        var lo = if (s.rangeLocked) s.lockLo else tmin
        var hi = if (s.rangeLocked) s.lockHi else tmax
        if (hi <= lo) hi = lo + 0.1

        // automatic / manual gain control -> 14-bit grey (16383 = hottest)
        val grey = IntArray(n)
        val res = Pools.RES14.toDouble()
        if (!s.rangeLocked && linear && cam.tmaxRaw != cam.tminRaw) {
            val mx = cam.tmaxRaw.toDouble(); val mn = cam.tminRaw.toDouble()
            val sc = res / (mx - mn)
            for (i in 0 until n) grey[i] = ((rawImg[i].coerceIn(mn.toInt(), mx.toInt()) - mn) * sc).toInt()
        } else {
            val sc = res / (hi - lo)
            for (i in 0 until n) grey[i] = ((temps[i].toDouble().coerceIn(lo, hi) - lo) * sc).toInt()
        }
        if (s.sharpen) sharpen(grey, w, h, 1.5)

        val pal = palettes.argb(s.palette, s.invertPalette)
        val px = IntArray(n)
        for (i in 0 until n) px[i] = pal[grey[i].coerceIn(0, Pools.RES14)]
        val bmp = nextBitmap(w, h)
        bmp.setPixels(px, 0, w, 0, 0, w, h)

        val spotRes = spots.map { it to temps[clampY(it.y, h) * w + clampX(it.x, w)].toDouble() }
        val boxRes = boxes.map { it to area(temps, w, h, it) }

        return FrameResult(
            bmp, w, h, temps, tmax, tmin, tcenter, tavg,
            imax % w, imax / w, imin % w, imin / w, lo, hi, pal, spotRes, boxRes, frameNo, fps,
        )
    }

    private fun clampX(x: Float, w: Int) = x.toInt().coerceIn(0, w - 1)
    private fun clampY(y: Float, h: Int) = y.toInt().coerceIn(0, h - 1)

    private fun area(temps: FloatArray, w: Int, h: Int, b: Box): AreaStat {
        val x0 = b.x0.roundToInt().coerceIn(0, w - 1)
        val y0 = b.y0.roundToInt().coerceIn(0, h - 1)
        val x1 = max(b.x1.roundToInt().coerceIn(0, w - 1), x0 + 1).coerceAtMost(w)
        val y1 = max(b.y1.roundToInt().coerceIn(0, h - 1), y0 + 1).coerceAtMost(h)
        var mx = -Double.MAX_VALUE; var mn = Double.MAX_VALUE; var sum = 0.0; var cnt = 0
        var mxx = x0; var mxy = y0; var mnx = x0; var mny = y0
        for (y in y0 until y1) for (x in x0 until x1) {
            val t = temps[y * w + x].toDouble()
            sum += t; cnt++
            if (t > mx) { mx = t; mxx = x; mxy = y }
            if (t < mn) { mn = t; mnx = x; mny = y }
        }
        return AreaStat(mx, mn, if (cnt > 0) sum / cnt else Double.NaN, mxx, mxy, mnx, mny)
    }

    /** RMH 3x3 unsharp mask (sigma 2.0 like the desktop default), edge pixels clamped. */
    private fun sharpen(img: IntArray, w: Int, h: Int, strength: Double) {
        val k = kernel ?: run {
            val sigma = 2.0
            val g = DoubleArray(9) { i ->
                val dx = (i % 3) - 1; val dy = (i / 3) - 1
                exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
            }
            val m = g.min()
            DoubleArray(9) { g[it] / m }.also { kernel = it }
        }
        val ksum = k.sum()
        val src = img.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var b = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                b += k[(dy + 1) * 3 + dx + 1] * src[yy * w + xx]
            }
            b /= ksum
            val f = src[y * w + x]
            img[y * w + x] = (f + (f - b) * strength).toInt().coerceIn(0, Pools.RES14)
        }
    }
}
