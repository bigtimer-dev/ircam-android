package io.github.bigtimer_dev.ircam.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.bigtimer_dev.ircam.CameraViewModel
import io.github.bigtimer_dev.ircam.Tool
import io.github.bigtimer_dev.ircam.data.Settings
import io.github.bigtimer_dev.ircam.thermal.Box
import io.github.bigtimer_dev.ircam.thermal.FrameResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private sealed interface Drag {
    data class MoveSpot(val i: Int) : Drag
    data class MoveBox(val i: Int, val start: Box, val origin: Offset) : Drag
    /** Dragging one corner; the opposite corner (ax, ay) stays put. */
    data class ResizeBox(val i: Int, val ax: Float, val ay: Float) : Drag
    data object Probe : Drag
}

/** Aspect-fit rectangle of the image inside the view. */
private fun fitRect(vw: Float, vh: Float, iw: Int, ih: Int): RectF {
    val s = min(vw / iw, vh / ih)
    val w = iw * s
    val h = ih * s
    val l = (vw - w) / 2f
    val t = (vh - h) / 2f
    return RectF(l, t, l + w, t + h)
}

@Composable
fun ThermalView(
    vm: CameraViewModel,
    frame: FrameResult,
    settings: Settings,
    tool: Tool,
    selection: Selection?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    var probe by remember { mutableStateOf<Offset?>(null) }
    val cur = rememberUpdatedState(frame)
    val curTool = rememberUpdatedState(tool)
    val curSel = rememberUpdatedState(selection)

    BoxWithConstraints(modifier) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val dstState = rememberUpdatedState(fitRect(vw, vh, frame.width, frame.height))

        fun toImage(p: Offset): Offset {
            val d = dstState.value
            val f = cur.value
            val x = ((p.x - d.left) / d.width() * f.width).coerceIn(0f, f.width.toFloat())
            val y = ((p.y - d.top) / d.height() * f.height).coerceIn(0f, f.height.toFloat())
            return Offset(x, y)
        }

        fun toScreen(x: Float, y: Float): Offset {
            val d = dstState.value
            val f = cur.value
            return Offset(d.left + x / f.width * d.width(), d.top + y / f.height * d.height())
        }

        fun hitSpot(p: Offset): Int? {
            val r = 28f * density
            return vm.spots.value.withIndex().lastOrNull { (_, s) ->
                val q = toScreen(s.x, s.y); hypot(q.x - p.x, q.y - p.y) < r
            }?.index
        }

        fun hitBox(p: Offset): Int? {
            val m = 10f * density
            return vm.boxes.value.withIndex().lastOrNull { (_, b) ->
                val a = toScreen(b.x0, b.y0); val c = toScreen(b.x1, b.y1)
                p.x in (a.x - m)..(c.x + m) && p.y in (a.y - m)..(c.y + m)
            }?.index
        }

        /** Corner of the selected box under the finger -> the opposite (anchor) corner. */
        fun hitCorner(p: Offset): Drag.ResizeBox? {
            val sel = curSel.value as? Selection.BoxSel ?: return null
            val b = vm.boxes.value.getOrNull(sel.index) ?: return null
            val r = 26f * density
            val corners = listOf(
                Triple(b.x0, b.y0, b.x1 to b.y1), Triple(b.x1, b.y0, b.x0 to b.y1),
                Triple(b.x0, b.y1, b.x1 to b.y0), Triple(b.x1, b.y1, b.x0 to b.y0),
            )
            for ((x, y, anchor) in corners) {
                val q = toScreen(x, y)
                if (hypot(q.x - p.x, q.y - p.y) < r) return Drag.ResizeBox(sel.index, anchor.first, anchor.second)
            }
            return null
        }

        var drag by remember { mutableStateOf<Drag?>(null) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val spot = hitSpot(p)
                            val box = if (spot == null) hitBox(p) else null
                            when {
                                spot != null -> vm.select(Selection.SpotSel(spot))
                                box != null -> vm.select(Selection.BoxSel(box))
                                curTool.value == Tool.SPOT && dstState.value.contains(p.x, p.y) -> {
                                    val q = toImage(p); vm.addSpot(q.x, q.y)
                                }
                                else -> vm.select(null)
                            }
                        },
                        onLongPress = { p ->
                            val spot = hitSpot(p)
                            val box = if (spot == null) hitBox(p) else null
                            if (spot != null || box != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.select(if (spot != null) Selection.SpotSel(spot) else Selection.BoxSel(box!!))
                                vm.deleteSelected()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val corner = hitCorner(p)
                            val spot = if (corner == null) hitSpot(p) else null
                            val box = if (corner == null && spot == null) hitBox(p) else null
                            drag = when {
                                corner != null -> corner
                                spot != null -> { vm.select(Selection.SpotSel(spot)); Drag.MoveSpot(spot) }
                                box != null -> {
                                    vm.select(Selection.BoxSel(box))
                                    Drag.MoveBox(box, vm.boxes.value[box], toImage(p))
                                }
                                curTool.value == Tool.BOX -> {
                                    val q = toImage(p)
                                    val i = vm.addBox(Box(q.x, q.y, q.x, q.y))
                                    if (i >= 0) { vm.select(Selection.BoxSel(i)); Drag.ResizeBox(i, q.x, q.y) } else null
                                }
                                else -> { probe = toImage(p); Drag.Probe }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val q = toImage(change.position)
                            when (val d = drag) {
                                is Drag.MoveSpot -> vm.moveSpot(d.i, q.x, q.y)
                                is Drag.MoveBox -> {
                                    val f = cur.value
                                    val bw = d.start.x1 - d.start.x0; val bh = d.start.y1 - d.start.y0
                                    val nx = (d.start.x0 + q.x - d.origin.x).coerceIn(0f, f.width - bw)
                                    val ny = (d.start.y0 + q.y - d.origin.y).coerceIn(0f, f.height - bh)
                                    vm.setBox(d.i, Box(nx, ny, nx + bw, ny + bh))
                                }
                                is Drag.ResizeBox -> vm.setBox(
                                    d.i, Box(min(d.ax, q.x), min(d.ay, q.y), max(d.ax, q.x), max(d.ay, q.y)),
                                )
                                Drag.Probe -> probe = q
                                null -> {}
                            }
                        },
                        onDragEnd = {
                            val d = drag
                            if (d is Drag.ResizeBox) {
                                // drop boxes that are too small to be useful
                                val b = vm.boxes.value.getOrNull(d.i)
                                if (b != null && (abs(b.x1 - b.x0) < 2f || abs(b.y1 - b.y0) < 2f)) vm.deleteSelected()
                            }
                            drag = null; probe = null
                        },
                        onDragCancel = { drag = null; probe = null },
                    )
                },
        ) {
            val dst = dstState.value
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                vm.renderer.drawFrame(nc, frame, dst, settings, density, selection)
                probe?.let { p ->
                    val s = toScreen(p.x, p.y)
                    vm.renderer.drawProbe(nc, s.x, s.y, settings.unit.formatWithUnit(frame.tempAt(p.x, p.y)), density, dst)
                }
            }
        }
    }
}
