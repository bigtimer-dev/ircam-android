package io.github.bigtimer_dev.ircam.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.bigtimer_dev.ircam.data.Settings
import io.github.bigtimer_dev.ircam.data.TempUnit
import io.github.bigtimer_dev.ircam.thermal.FrameResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Selection inside the overlay: spot or box index. */
sealed interface Selection {
    data class SpotSel(val index: Int) : Selection
    data class BoxSel(val index: Int) : Selection
}

/**
 * Draws a processed frame with its measurement overlay onto an android.graphics.Canvas.
 * The live view, snapshots and video recordings all use this, so they look the same.
 */
class Renderer {
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val nearest = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
    }
    private val path = Path()
    private val tmp = RectF()

    companion object {
        const val HOT = 0xFFFF3B30.toInt()
        const val COLD = 0xFF2F9BFF.toInt()
        const val ACCENT = 0xFFFFB020.toInt()
        const val LABEL_BG = 0xB0101014.toInt()
    }

    /**
     * @param dst where the image goes on the canvas
     * @param px base size unit in canvas pixels (≈ 1dp on screen)
     */
    fun drawFrame(c: Canvas, f: FrameResult, dst: RectF, s: Settings, px: Float, selection: Selection? = null) {
        c.drawBitmap(f.bitmap, null, dst, if (s.smooth) bmpPaint else nearest)
        val sx = dst.width() / f.width
        val sy = dst.height() / f.height
        fun mx(x: Float) = dst.left + (x + 0.5f) * sx
        fun my(y: Float) = dst.top + (y + 0.5f) * sy
        val u = s.unit
        text.textSize = 12f * px

        c.save()
        c.clipRect(dst)
        // boxes
        f.boxes.forEachIndexed { i, (b, st) ->
            val sel = selection == Selection.BoxSel(i)
            tmp.set(dst.left + b.x0 * sx, dst.top + b.y0 * sy, dst.left + b.x1 * sx, dst.top + b.y1 * sy)
            stroke.strokeWidth = 3.5f * px; stroke.color = 0x99000000.toInt()
            c.drawRect(tmp, stroke)
            stroke.strokeWidth = 1.6f * px; stroke.color = if (sel) ACCENT else Color.WHITE
            c.drawRect(tmp, stroke)
            if (sel) {
                fill.color = ACCENT
                for ((hx, hy) in listOf(tmp.left to tmp.top, tmp.right to tmp.top, tmp.left to tmp.bottom, tmp.right to tmp.bottom))
                    c.drawCircle(hx, hy, 5f * px, fill)
            }
            triangle(c, mx(st.maxX.toFloat()), my(st.maxY.toFloat()), 5f * px, HOT, true)
            triangle(c, mx(st.minX.toFloat()), my(st.minY.toFloat()), 5f * px, COLD, false)
            val label = "B${i + 1}  ▲${u.format(st.max)} ▼${u.format(st.min)} ⌀${u.format(st.avg)}"
            label(c, label, tmp.left, tmp.top - 3f * px, px, dst, if (sel) ACCENT else Color.WHITE, above = true)
        }
        // spots
        f.spots.forEachIndexed { i, (sp, t) ->
            val sel = selection == Selection.SpotSel(i)
            val x = dst.left + sp.x * sx; val y = dst.top + sp.y * sy
            crosshair(c, x, y, 9f * px, px, if (sel) ACCENT else Color.WHITE)
            label(c, "${i + 1}  ${u.formatWithUnit(t)}", x + 10f * px, y - 10f * px, px, dst, if (sel) ACCENT else Color.WHITE)
        }
        // centre / max / min
        if (s.showCenter) {
            val x = mx((f.width / 2).toFloat()); val y = my((f.height / 2).toFloat())
            crosshair(c, x, y, 11f * px, px, Color.WHITE, gap = true)
            label(c, u.formatWithUnit(f.tcenter), x + 12f * px, y + 16f * px, px, dst, Color.WHITE)
        }
        if (s.showMax) {
            val x = mx(f.maxX.toFloat()); val y = my(f.maxY.toFloat())
            triangle(c, x, y, 8f * px, HOT, true)
            label(c, u.formatWithUnit(f.tmax), x + 9f * px, y - 9f * px, px, dst, 0xFFFF8A80.toInt())
        }
        if (s.showMin) {
            val x = mx(f.minX.toFloat()); val y = my(f.minY.toFloat())
            triangle(c, x, y, 8f * px, COLD, false)
            label(c, u.formatWithUnit(f.tmin), x + 9f * px, y + 20f * px, px, dst, 0xFF9CCBFF.toInt())
        }
        c.restore()
    }

    /** Live "probe" reading under the finger. */
    fun drawProbe(c: Canvas, x: Float, y: Float, s: String, px: Float, bounds: RectF) {
        text.textSize = 13f * px
        crosshair(c, x, y, 14f * px, px, ACCENT, gap = true)
        label(c, s, x - text.measureText(s) / 2f, y - 26f * px, px, bounds, ACCENT)
    }

    private fun crosshair(c: Canvas, x: Float, y: Float, r: Float, px: Float, color: Int, gap: Boolean = false) {
        val g = if (gap) r * 0.35f else 0f
        for (pass in 0..1) {
            stroke.strokeWidth = if (pass == 0) 3.5f * px else 1.6f * px
            stroke.color = if (pass == 0) 0x99000000.toInt() else color
            c.drawLine(x - r, y, x - g, y, stroke); c.drawLine(x + g, y, x + r, y, stroke)
            c.drawLine(x, y - r, x, y - g, stroke); c.drawLine(x, y + g, x, y + r, stroke)
            if (!gap) c.drawCircle(x, y, r * 0.45f, stroke)
        }
    }

    private fun triangle(c: Canvas, x: Float, y: Float, r: Float, color: Int, up: Boolean) {
        path.reset()
        if (up) { path.moveTo(x, y - r); path.lineTo(x + r * 0.9f, y + r * 0.6f); path.lineTo(x - r * 0.9f, y + r * 0.6f) }
        else { path.moveTo(x, y + r); path.lineTo(x + r * 0.9f, y - r * 0.6f); path.lineTo(x - r * 0.9f, y - r * 0.6f) }
        path.close()
        fill.color = color
        c.drawPath(path, fill)
        stroke.strokeWidth = r * 0.22f; stroke.color = Color.WHITE
        c.drawPath(path, stroke)
    }

    /** Text on a dark pill, kept inside [bounds]. (x, y) is the baseline start. */
    private fun label(c: Canvas, s: String, x: Float, y: Float, px: Float, bounds: RectF, color: Int, above: Boolean = false) {
        val w = text.measureText(s)
        val padX = 5f * px; val padY = 3f * px
        val asc = -text.ascent(); val desc = text.descent()
        var lx = x
        var ly = if (above) y - desc - padY else y
        if (lx + w + padX > bounds.right) lx = bounds.right - w - padX
        if (lx - padX < bounds.left) lx = bounds.left + padX
        if (ly - asc - padY < bounds.top) ly = bounds.top + asc + padY
        if (ly + desc + padY > bounds.bottom) ly = bounds.bottom - desc - padY
        tmp.set(lx - padX, ly - asc - padY, lx + w + padX, ly + desc + padY)
        fill.color = LABEL_BG
        c.drawRoundRect(tmp, 5f * px, 5f * px, fill)
        text.color = color
        c.drawText(s, lx, ly, text)
        text.color = Color.WHITE
    }

    /** Vertical colour bar with high/low labels, used in exported images. */
    fun drawColorBar(c: Canvas, f: FrameResult, rect: RectF, unit: TempUnit, px: Float) {
        val pal = f.palette
        val n = 64
        val colors = IntArray(n) { pal[((n - 1 - it) * (pal.size - 1)) / (n - 1)] }
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, colors, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 4f * px, 4f * px, fill)
        fill.shader = null
        text.textSize = 13f * px
        text.color = Color.WHITE
        val hi = unit.format(f.barHi)
        val lo = unit.format(f.barLo)
        val cx = rect.centerX()
        text.textAlign = Paint.Align.CENTER
        c.drawText(hi, cx, rect.top - 8f * px, text)
        c.drawText(lo, cx, rect.bottom + 8f * px - text.ascent(), text)
        text.textAlign = Paint.Align.LEFT
    }

    /** Composite image for snapshots and video: thermal view + side panel with colour bar. */
    fun exportSize(f: FrameResult): Pair<Int, Int> {
        val h = 720
        val imgW = (h.toFloat() * f.width / f.height).toInt()
        val w = (imgW + 150 + 15) / 16 * 16
        return w to h
    }

    fun drawExport(c: Canvas, f: FrameResult, s: Settings, emissivity: Double, timestamp: Long = System.currentTimeMillis()) {
        val (w, h) = exportSize(f)
        val imgW = (h.toFloat() * f.width / f.height)
        c.drawColor(0xFF0E0E12.toInt())
        val px = h / 380f
        drawFrame(c, f, RectF(0f, 0f, imgW, h.toFloat()), s, px)
        val panelL = imgW
        val bar = RectF(panelL + (w - panelL) / 2f - 11f * px, 46f * px, panelL + (w - panelL) / 2f + 11f * px, h - 74f * px)
        drawColorBar(c, f, bar, s.unit, px)
        text.textSize = 12f * px
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFB8B8C0.toInt()
        val cx = panelL + (w - panelL) / 2f
        c.drawText(s.unit.symbol, cx, 16f * px, text)
        c.drawText("ε %.2f".format(Locale.US, emissivity), cx, h - 38f * px, text)
        text.textSize = 9f * px
        c.drawText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp)), cx, h - 19f * px, text)
        c.drawText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp)), cx, h - 8f * px, text)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
    }

    fun renderExport(f: FrameResult, s: Settings, emissivity: Double): Bitmap {
        val (w, h) = exportSize(f)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawExport(Canvas(b), f, s, emissivity)
        return b
    }
}
