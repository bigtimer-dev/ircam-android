package io.github.bigtimer_dev.ircam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bigtimer_dev.ircam.CamState
import io.github.bigtimer_dev.ircam.CameraViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun IrcamApp(vm: CameraViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf("camera") }
    var showSettings by remember { mutableStateOf(false) }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }
    val camPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.onCameraPermission(it) }
    val needCam by vm.needCameraPermission.collectAsState()
    LaunchedEffect(needCam) { if (needCam != null) camPerm.launch(android.Manifest.permission.CAMERA) }

    androidx.compose.material3.Surface(color = Ui.Bg, contentColor = Ui.Text) { Box(Modifier.fillMaxSize()) {
        when (screen) {
            "gallery" -> {
                BackHandler { screen = "camera" }
                GalleryScreen(vm, onBack = { screen = "camera" }, onOpen = { item ->
                    vm.openSnapshot(item)
                    screen = "camera"
                })
            }
            else -> {
                if (state is CamState.Analysis) BackHandler { vm.closeAnalysis() }
                when (val s = state) {
                    is CamState.Idle -> ConnectScreen(
                        state = s,
                        onConnect = vm::connectCamera,
                        onDemo = vm::startDemo,
                        onGallery = { screen = "gallery" },
                    )
                    else -> CameraScreen(
                        vm = vm,
                        state = s,
                        onGallery = { screen = "gallery"; vm.refreshLastThumb() },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 96.dp),
        ) { Snackbar(it, containerColor = Ui.PanelHigh, contentColor = Ui.Text, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) }
    } }

    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
}
