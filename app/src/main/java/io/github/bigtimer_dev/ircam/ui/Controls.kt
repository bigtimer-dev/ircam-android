@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.bigtimer_dev.ircam.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bigtimer_dev.ircam.CamState
import io.github.bigtimer_dev.ircam.CameraViewModel
import io.github.bigtimer_dev.ircam.Tool
import io.github.bigtimer_dev.ircam.thermal.Palettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Colours of the current palette, sampled for drawing a gradient (cold first). */
@Composable
private fun paletteColors(vm: CameraViewModel): List<Color> {
    val s by vm.settings.collectAsState()
    return remember(s.palette, s.invertPalette) {
        val p = vm.palettes.argb(s.palette, s.invertPalette)
        List(32) { Color(p[(it * (p.size - 1)) / 31]) }
    }
}

// ------------------------------------------------------------------ colour bar

@Composable
fun ColorBarRow(vm: CameraViewModel, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val frame by vm.frame.collectAsState()
    val s by vm.settings.collectAsState()
    val colors = paletteColors(vm)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            frame?.let { s.unit.format(it.barLo) } ?: "—",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = Ui.Cold, modifier = Modifier.width(52.dp),
        )
        Canvas(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).clickable(onClick = onEdit)) {
            drawRoundRect(Brush.horizontalGradient(colors), cornerRadius = CornerRadius(7.dp.toPx()))
        }
        Text(
            frame?.let { s.unit.format(it.barHi) } ?: "—",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = Ui.Hot, textAlign = TextAlign.End, modifier = Modifier.width(52.dp),
        )
        LockButton(vm)
    }
}

@Composable
fun VerticalColorBar(vm: CameraViewModel, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val frame by vm.frame.collectAsState()
    val s by vm.settings.collectAsState()
    val colors = paletteColors(vm)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LockButton(vm)
        Text(frame?.let { s.unit.format(it.barHi) } ?: "—", style = MaterialTheme.typography.labelMedium, color = Ui.Hot)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.weight(1f).width(14.dp).clip(RoundedCornerShape(7.dp)).clickable(onClick = onEdit)) {
            drawRoundRect(Brush.verticalGradient(colors.reversed()), cornerRadius = CornerRadius(7.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Text(frame?.let { s.unit.format(it.barLo) } ?: "—", style = MaterialTheme.typography.labelMedium, color = Ui.Cold)
    }
}

@Composable
private fun LockButton(vm: CameraViewModel) {
    val s by vm.settings.collectAsState()
    IconButton(onClick = vm::toggleRangeLock, modifier = Modifier.size(40.dp)) {
        Icon(
            if (s.rangeLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            if (s.rangeLocked) "Colour range locked (tap for automatic)" else "Lock colour range",
            tint = if (s.rangeLocked) Ui.Accent else Ui.TextDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ColorRangeDialog(vm: CameraViewModel, onDismiss: () -> Unit) {
    val s = vm.settings.value
    val f = vm.frame.value
    val lo0 = if (s.rangeLocked) s.lockLo else f?.barLo ?: 20.0
    val hi0 = if (s.rangeLocked) s.lockHi else f?.barHi ?: 40.0
    var lo by remember { mutableStateOf(s.unit.format(lo0)) }
    var hi by remember { mutableStateOf(s.unit.format(hi0)) }
    val loV = lo.replace(',', '.').toDoubleOrNull()
    val hiV = hi.replace(',', '.').toDoubleOrNull()
    val valid = loV != null && hiV != null && hiV > loV
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour range") },
        text = {
            Column {
                Text(
                    "Lock the palette to a fixed temperature span, so colours mean the same thing from frame to frame.",
                    style = MaterialTheme.typography.bodyMedium, color = Ui.TextDim,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        lo, { lo = it }, Modifier.weight(1f), label = { Text("Low ${s.unit.symbol}") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        hi, { hi = it }, Modifier.weight(1f), label = { Text("High ${s.unit.symbol}") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                vm.updateSettings {
                    it.copy(rangeLocked = true, lockLo = s.unit.toCelsius(loV!!), lockHi = s.unit.toCelsius(hiV!!))
                }
                onDismiss()
            }) { Text("Lock") }
        },
        dismissButton = {
            TextButton(onClick = { vm.updateSettings { it.copy(rangeLocked = false) }; onDismiss() }) { Text("Automatic") }
        },
    )
}

// ------------------------------------------------------------------ readouts

@Composable
fun Readouts(vm: CameraViewModel, modifier: Modifier = Modifier) {
    val frame by vm.frame.collectAsState()
    val s by vm.settings.collectAsState()
    val f = frame
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadoutCard("MAX", f?.tmax, Ui.Hot, s.unit, Modifier.weight(1f))
        ReadoutCard("CENTER", f?.tcenter, Ui.Text, s.unit, Modifier.weight(1f))
        ReadoutCard("MIN", f?.tmin, Ui.Cold, s.unit, Modifier.weight(1f))
    }
}

@Composable
private fun ReadoutCard(label: String, celsius: Double?, color: Color, unit: io.github.bigtimer_dev.ircam.data.TempUnit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ui.Panel).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.9f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(celsius?.let { unit.format(it) } ?: "—", style = ReadoutStyle, color = Ui.Text, maxLines = 1)
            Text(unit.symbol, style = MaterialTheme.typography.labelMedium, color = Ui.TextDim, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
    }
}

// ------------------------------------------------------------------ tools

@Composable
fun ToolRow(vm: CameraViewModel, modifier: Modifier = Modifier, showHint: Boolean = true) {
    val tool by vm.tool.collectAsState()
    val sel by vm.selection.collectAsState()
    val spots by vm.spots.collectAsState()
    val boxes by vm.boxes.collectAsState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                val tools = listOf(
                    Triple(Tool.VIEW, "View", Icons.Rounded.TouchApp),
                    Triple(Tool.SPOT, "Spot", Icons.Rounded.GpsFixed),
                    Triple(Tool.BOX, "Box", Icons.Rounded.CropFree),
                )
                tools.forEachIndexed { i, (t, label, icon) ->
                    SegmentedButton(
                        selected = tool == t,
                        onClick = { vm.setTool(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, tools.size),
                        icon = { Icon(icon, null, Modifier.size(18.dp)) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Ui.Accent.copy(alpha = 0.18f),
                            activeContentColor = Ui.Accent,
                            inactiveContainerColor = Ui.Panel,
                            inactiveContentColor = Ui.TextDim,
                            activeBorderColor = Ui.Line, inactiveBorderColor = Ui.Line,
                        ),
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.width(6.dp))
            ToolIcon(Icons.AutoMirrored.Rounded.RotateRight, "Rotate image") { vm.rotate() }
            if (sel != null) ToolIcon(Icons.Rounded.DeleteOutline, "Delete selected", tint = Ui.Hot) { vm.deleteSelected() }
            else ToolIcon(Icons.Rounded.DeleteSweep, "Remove all spots and boxes", enabled = spots.isNotEmpty() || boxes.isNotEmpty()) { vm.clearObjects() }
        }
        val hint = when {
            sel != null -> if (sel is Selection.BoxSel) "Drag to move · drag a corner to resize · hold to delete" else "Drag to move · hold to delete"
            tool == Tool.SPOT -> "Tap the image to add a temperature spot"
            tool == Tool.BOX -> "Drag on the image to draw a measurement box"
            else -> "Touch and drag to read any point"
        }
        if (showHint) Text(hint, style = MaterialTheme.typography.bodySmall, color = Ui.TextDim, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

@Composable
private fun ToolIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Ui.Text, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Ui.Panel, contentColor = tint, disabledContentColor = Ui.Line),
        modifier = Modifier.padding(start = 4.dp).size(42.dp),
    ) { Icon(icon, desc, Modifier.size(20.dp)) }
}

// ------------------------------------------------------------------ palettes

@Composable
fun PaletteStrip(vm: CameraViewModel) {
    val s by vm.settings.collectAsState()
    val order = remember {
        val all = vm.palettes.names
        Palettes.FEATURED.filter { it in all } + all.filter { it !in Palettes.FEATURED && it != "AllWhite" && it != "AllBlack" }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (order.indexOf(s.palette) + 1 - 2).coerceAtLeast(0))
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val c by animateColorAsState(if (s.invertPalette) Ui.Accent else Ui.Line, label = "inv")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.updateSettings { it.copy(invertPalette = !it.invertPalette) } }) {
                Box(
                    Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, c, RoundedCornerShape(8.dp)).background(Ui.Panel),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Contrast, "Invert palette", tint = if (s.invertPalette) Ui.Accent else Ui.TextDim, modifier = Modifier.size(18.dp)) }
                Text("Invert", fontSize = 10.sp, color = Ui.TextDim, modifier = Modifier.padding(top = 3.dp))
            }
        }
        items(order, key = { it }) { name ->
            val selected = name == s.palette
            val colors = remember(name) { vm.palettes.preview(name).map { Color(it) } }
            val border by animateDpAsState(if (selected) 2.dp else 0.dp, label = "b")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { vm.updateSettings { it.copy(palette = name) } },
            ) {
                Box(
                    Modifier.size(64.dp, 30.dp).clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(colors))
                        .border(border, Ui.Accent, RoundedCornerShape(8.dp)),
                )
                Text(
                    name, fontSize = 10.sp, maxLines = 1,
                    color = if (selected) Ui.Accent else Ui.TextDim,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ bottom controls

@Composable
fun BottomControls(vm: CameraViewModel, state: CamState, onGallery: () -> Unit, modifier: Modifier = Modifier) {
    val rec by vm.recording.collectAsState()
    val thumb by vm.lastThumb.collectAsState()
    val analysis = state is CamState.Analysis
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryThumb(thumb, onGallery)
        if (analysis) {
            BigRoundButton(onClick = vm::saveAnalysisImage, desc = "Save image") {
                Icon(Icons.Rounded.Save, null, tint = Color.Black, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.size(56.dp))
        } else {
            BigRoundButton(onClick = vm::snapshot, desc = "Take snapshot", enabled = state is CamState.Live) {}
            RecordButton(rec != null, enabled = state is CamState.Live, onClick = vm::toggleRecording)
        }
    }
}

@Composable
private fun GalleryThumb(uri: Uri?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
        }
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Ui.Panel).border(1.dp, Ui.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Gallery", contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        else Icon(Icons.Rounded.PhotoLibrary, "Gallery", tint = Ui.TextDim)
    }
}

@Composable
private fun BigRoundButton(onClick: () -> Unit, desc: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, if (enabled) Color.White else Ui.Line, CircleShape)
            .padding(7.dp).clip(CircleShape).background(if (enabled) Color.White else Ui.Line)
            .clickable(enabled = enabled, onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 20.dp else 30.dp, label = "rec")
    val corner by animateDpAsState(if (recording) 5.dp else 15.dp, label = "corner")
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Ui.Panel).border(2.dp, if (recording) Ui.Rec else Ui.Line, CircleShape)
            .clickable(enabled = enabled, onClickLabel = if (recording) "Stop recording" else "Record video", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(if (enabled) Ui.Rec else Ui.Line))
    }
}

@Composable
fun RecordingBadge(startedAt: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val sec = ((now - startedAt) / 1000).coerceAtLeast(0)
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (sec % 2 == 0L) Ui.Rec else Ui.Rec.copy(alpha = 0.4f)))
        Text(
            "REC  %02d:%02d".format(sec / 60, sec % 60),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = Color.White, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun ShareButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Rounded.Share, "Share") }
}
