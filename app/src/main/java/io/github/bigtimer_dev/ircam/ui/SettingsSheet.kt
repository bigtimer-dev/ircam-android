@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.bigtimer_dev.ircam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bigtimer_dev.ircam.BuildConfig
import io.github.bigtimer_dev.ircam.CamState
import io.github.bigtimer_dev.ircam.CameraViewModel
import io.github.bigtimer_dev.ircam.data.MeasureParams
import io.github.bigtimer_dev.ircam.data.TempUnit
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val s by vm.settings.collectAsState()
    val p by vm.params.collectAsState()
    val state by vm.state.collectAsState()
    val high by vm.highRange.collectAsState()
    var materialDialog by remember { mutableStateOf(false) }
    val analysis = state is CamState.Analysis
    val live = state as? CamState.Live

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Ui.Panel) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // ---------------------------------------------------------- measurement
            Section("Measurement" + if (analysis) " (this snapshot)" else "")
            Row(
                Modifier.fillMaxWidth().clickable { materialDialog = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Material", style = MaterialTheme.typography.bodyLarge)
                    Text(s.emissivityMaterial, style = MaterialTheme.typography.bodySmall, color = Ui.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Ui.TextDim)
            }
            ParamSlider("Emissivity", p.emissivity, 0.01f..1f, 98, { "%.2f".format(Locale.US, it) }) {
                vm.updateParams(p.copy(emissivity = it), material = "Custom")
            }
            val u = s.unit
            ParamSlider("Reflected temperature", p.reflected, -20f..120f, 139, { u.formatWithUnit(it) }) {
                vm.updateParams(p.copy(reflected = it.roundToInt().toDouble()))
            }
            ParamSlider("Ambient temperature", p.ambient, -20f..60f, 79, { u.formatWithUnit(it) }) {
                vm.updateParams(p.copy(ambient = it.roundToInt().toDouble()))
            }
            ParamSlider("Humidity", p.humidity, 0f..1f, 19, { "${(it * 100).roundToInt()} %" }) {
                vm.updateParams(p.copy(humidity = it))
            }
            ParamSlider("Distance", p.distance, 0f..50f, 49, { "${it.toInt()} m" }) {
                vm.updateParams(p.copy(distance = it.roundToInt().toDouble()))
            }
            ParamSlider("Temperature correction", p.tempCorrection, -10f..10f, 199, { "%+.1f ".format(Locale.US, it * u.scale) + u.symbol }) {
                vm.updateParams(p.copy(tempCorrection = (it * 10).roundToInt() / 10.0))
            }
            TextButton(onClick = { vm.updateParams(MeasureParams(), material = "Camera Default") }) { Text("Reset to defaults") }

            // ---------------------------------------------------------- camera
            if (live?.canSwitchRange == true) {
                Section("Camera")
                Text("Temperature range", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Low: about −20…150 °C, finer detail. High: up to about 600 °C.",
                    style = MaterialTheme.typography.bodySmall, color = Ui.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Low range" to false, "High range" to true).forEachIndexed { i, (label, h) ->
                        SegmentedButton(
                            selected = high == h, onClick = { vm.setHighRange(h) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---------------------------------------------------------- display
            Section("Display")
            Text("Unit", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TempUnit.entries.forEachIndexed { i, unit ->
                    SegmentedButton(
                        selected = s.unit == unit, onClick = { vm.updateSettings { it.copy(unit = unit) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TempUnit.entries.size),
                    ) { Text(unit.symbol) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Toggle("Smooth image", "Bilinear filtering when enlarging the 256×192 image", s.smooth) { v -> vm.updateSettings { it.copy(smooth = v) } }
            Toggle("Sharpen", "Unsharp mask for crisper edges", s.sharpen) { v -> vm.updateSettings { it.copy(sharpen = v) } }
            Toggle("Mirror image", "Flip left/right (for selfie-style use)", s.mirror) { v -> vm.updateSettings { it.copy(mirror = v) } }
            Toggle("Show hottest point", null, s.showMax) { v -> vm.updateSettings { it.copy(showMax = v) } }
            Toggle("Show coldest point", null, s.showMin) { v -> vm.updateSettings { it.copy(showMin = v) } }
            Toggle("Show centre point", null, s.showCenter) { v -> vm.updateSettings { it.copy(showCenter = v) } }

            // ---------------------------------------------------------- about
            Section("About")
            if (live != null) {
                OutlinedButton(onClick = { vm.disconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (live.demo) "Stop demo" else "Disconnect camera")
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "IRCAM Thermal ${BuildConfig.VERSION_NAME}\n" +
                    "Android port of OpenRMH-IRCAM / ircam-linux (MIT). Snapshots are saved to Pictures/IRCAM; " +
                    "SnapShotRAW_*.png files keep the full temperature data and open in ircam-linux and the Windows app. " +
                    "Videos go to Movies/IRCAM.",
                style = MaterialTheme.typography.bodySmall, color = Ui.TextDim,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (materialDialog) MaterialDialog(vm, onDismiss = { materialDialog = false }) { name, e ->
        vm.updateParams(p.copy(emissivity = e), material = name)
        materialDialog = false
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = Ui.Line)
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ui.Accent)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ParamSlider(
    label: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int,
    format: (Double) -> String, onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = Ui.Accent)
        }
        Slider(
            value = value.toFloat().coerceIn(range), onValueChange = { onChange(it.toDouble()) },
            valueRange = range, steps = steps,
            colors = SliderDefaults.colors(activeTickColor = Ui.Accent.copy(alpha = 0f), inactiveTickColor = Ui.Line.copy(alpha = 0f)),
        )
    }
}

@Composable
private fun Toggle(label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Ui.TextDim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun MaterialDialog(vm: CameraViewModel, onDismiss: () -> Unit, onPick: (String, Double) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) { vm.emissivityTable.filter { it.first.contains(q, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Material emissivity") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Search materials") },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.first }) { (name, e) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(name, e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("%.2f".format(Locale.US, e), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = Ui.Accent)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
