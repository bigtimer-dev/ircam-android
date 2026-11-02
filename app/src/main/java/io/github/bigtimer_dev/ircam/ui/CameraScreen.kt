@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.bigtimer_dev.ircam.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bigtimer_dev.ircam.CamState
import io.github.bigtimer_dev.ircam.CameraViewModel
import java.util.Locale

@Composable
fun CameraScreen(vm: CameraViewModel, state: CamState, onGallery: () -> Unit, onSettings: () -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    var rangeDialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (landscape) LandscapeLayout(vm, state, onGallery, onSettings) { rangeDialog = true }
        else PortraitLayout(vm, state, onGallery, onSettings) { rangeDialog = true }
    }
    if (rangeDialog) ColorRangeDialog(vm) { rangeDialog = false }
}

@Composable
private fun PortraitLayout(vm: CameraViewModel, state: CamState, onGallery: () -> Unit, onSettings: () -> Unit, onRange: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, state, onSettings)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Top) {
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 8.dp)) { ImageArea(vm, state) }
            ColorBarRow(vm, onRange, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Readouts(vm, Modifier.padding(horizontal = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        ToolRow(vm, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(6.dp))
        PaletteStrip(vm)
        BottomControls(vm, state, onGallery, Modifier.padding(top = 4.dp, bottom = 10.dp))
    }
}

@Composable
private fun LandscapeLayout(vm: CameraViewModel, state: CamState, onGallery: () -> Unit, onSettings: () -> Unit, onRange: () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(6.dp)) { ImageArea(vm, state) }
        VerticalColorBar(vm, onRange, Modifier.fillMaxHeight().width(54.dp).padding(vertical = 12.dp))
        Column(Modifier.width(340.dp).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TopBar(vm, state, onSettings)
                Readouts(vm, Modifier.padding(horizontal = 10.dp))
                Spacer(Modifier.height(6.dp))
                ToolRow(vm, Modifier.padding(horizontal = 10.dp), showHint = false)
                Spacer(Modifier.height(4.dp))
                PaletteStrip(vm)
            }
            BottomControls(vm, state, onGallery, Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun ImageArea(vm: CameraViewModel, state: CamState) {
    val frame by vm.frame.collectAsState()
    val settings by vm.settings.collectAsState()
    val tool by vm.tool.collectAsState()
    val selection by vm.selection.collectAsState()
    val rec by vm.recording.collectAsState()
    val f = frame
    val ar = if (f != null) f.width.toFloat() / f.height else 4f / 3f
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ar).clip(RoundedCornerShape(14.dp)).background(Ui.Panel), contentAlignment = Alignment.Center) {
            if (f == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ui.Accent)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (state is CamState.Connecting) "Starting ${state.name}…" else "Waiting for frames…",
                        color = Ui.TextDim,
                    )
                }
            } else {
                ThermalView(vm, f, settings, tool, selection, Modifier.fillMaxSize())
            }
            if (rec != null) RecordingBadge(rec!!.startedAt, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
    }
}

@Composable
private fun TopBar(vm: CameraViewModel, state: CamState, onSettings: () -> Unit) {
    val frame by vm.frame.collectAsState()
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().height(if (LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp) 50.dp else 56.dp).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is CamState.Analysis) {
            IconButton(onClick = vm::closeAnalysis) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to camera") }
            Column(Modifier.weight(1f)) {
                Text("Snapshot analysis", style = MaterialTheme.typography.titleMedium)
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(state.item.dateMillis)), style = MaterialTheme.typography.bodySmall, color = Ui.TextDim)
            }
            ShareButton {
                state.item.imageUri?.let { uri ->
                    val i = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(i, "Share snapshot"))
                }
            }
        } else {
            val live = state as? CamState.Live
            Box(
                Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape)
                    .background(if (live != null) Color4(0xFF3DDC84) else Ui.TextDim),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    live?.name ?: (state as? CamState.Connecting)?.name ?: "",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val fps = frame?.fps ?: 0.0
                val link by vm.link.collectAsState()
                Text(
                    if (live != null) String.format(Locale.US, "%.0f fps", fps) + when {
                        live.demo -> " · simulated"
                        live.wireless -> link?.let { l ->
                            buildString {
                                append(" · WiFi")
                                l.latencyMs?.let { append(String.format(Locale.US, " %.0f ms", it)) }
                                if (l.loss >= 0.01) append(String.format(Locale.US, " · %.0f%% loss", l.loss * 100))
                                l.rssiDbm?.let { append(" · $it dBm") }
                                l.batteryPct?.let { append(String.format(Locale.US, " · %.0f%%", it)) }
                                if (!l.cameraOk) append(" · no camera on bridge")
                            }
                        } ?: " · WiFi"
                        else -> ""
                    } else "connecting",
                    style = MaterialTheme.typography.bodySmall, color = Ui.TextDim,
                )
            }
            if (live?.canSwitchRange == true) RangeToggle(vm)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@Suppress("FunctionName")
internal fun Color4(v: Long) = androidx.compose.ui.graphics.Color(v)

@Composable
private fun RangeToggle(vm: CameraViewModel) {
    val high by vm.highRange.collectAsState()
    val busy by vm.rangeBusy.collectAsState()
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ui.Line, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, isHigh) in listOf("LOW" to false, "HIGH" to true)) {
            val sel = high == isHigh
            Box(
                Modifier
                    .background(if (sel) Ui.Accent else Ui.Panel)
                    .clickable(enabled = !busy && !sel) { vm.setHighRange(isHigh) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else Ui.TextDim,
                )
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(14.dp), strokeWidth = 2.dp, color = Ui.Accent)
    }
}
