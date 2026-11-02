package io.github.bigtimer_dev.ircam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bigtimer_dev.ircam.CamState

@Composable
fun ConnectScreen(
    state: CamState.Idle,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onGallery: () -> Unit,
    wirelessHost: String = "p2pro.local",
    onWireless: (String) -> Unit = {},
) {
    var askHost by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (askHost) WirelessDialog(wirelessHost, onDismiss = { askHost = false }, onConnect = { askHost = false; onWireless(it) })
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val land = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
            HeatLogo(Modifier.size(if (land) 72.dp else 132.dp))
            Spacer(Modifier.height(if (land) 8.dp else 24.dp))
            Text("IRCAM Thermal", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.usbPresent) "Camera detected. Allow USB access to start the live view."
                else "Plug your thermal camera into the phone's USB-C port.",
                style = MaterialTheme.typography.bodyLarge, color = Ui.TextDim, textAlign = TextAlign.Center,
            )
            state.message?.let {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1A12)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, null, tint = Ui.Accent, modifier = Modifier.size(20.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Usb, null)
                Text(if (state.usbPresent) "Allow & connect" else "Connect camera", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { askHost = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp))
                Text("Wireless camera", Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Text("Try demo", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                    Text("Gallery", Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Supported: InfiRay P2 / P2 Pro / Tiny1-C, TOPDON TC001 and other 256×192 cameras of the same family.\n" +
                    "Tip: the app opens automatically when you plug in the camera.",
                style = MaterialTheme.typography.bodySmall, color = Ui.TextDim, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text("Needs a phone with USB OTG (host) support.", style = MaterialTheme.typography.bodySmall, color = Ui.TextDim)
        }
    }
}

/** Asks for the wireless bridge's address. */
@Composable
private fun WirelessDialog(initial: String, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Wifi, null) },
        title = { Text("Wireless camera") },
        text = {
            Column {
                Text(
                    "The P2 Pro on the Raspberry Pi bridge. At home use p2pro.local (or its IP address); " +
                        "on the bridge's own hotspot (P2Pro-xxxx) use 10.42.0.1.",
                    style = MaterialTheme.typography.bodyMedium, color = Ui.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Bridge address") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onConnect(text) }),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in listOf("p2pro.local", "10.42.0.1")) {
                        androidx.compose.material3.AssistChip(onClick = { text = h }, label = { Text(h) })
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onConnect(text) }) { Text("Connect") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HeatLogo(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val gc = Offset(center.x + r * 0.08f, center.y - r * 0.06f)
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFFFF4C2), 0.25f to Color(0xFFFFB020), 0.55f to Color(0xFFE0402A),
                0.8f to Color(0xFF4A1470), 1f to Color(0x004A1470),
                center = gc, radius = r * 0.92f,
            ),
            radius = r * 0.9f, center = gc,
        )
        val s = Stroke(width = r * 0.06f)
        val l = r * 0.32f
        val g = r * 0.12f
        listOf(
            Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f),
        ).forEach { d ->
            drawLine(Color.White, center + d * g, center + d * (g + l), strokeWidth = s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
