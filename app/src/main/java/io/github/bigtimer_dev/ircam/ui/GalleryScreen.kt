@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package io.github.bigtimer_dev.ircam.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bigtimer_dev.ircam.CameraViewModel
import io.github.bigtimer_dev.ircam.data.SnapshotItem
import io.github.bigtimer_dev.ircam.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(vm: CameraViewModel, onBack: () -> Unit, onOpen: (SnapshotItem) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<android.net.Uri>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload++ }
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permLauncher.launch(perms)
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { selected = emptySet(); reload++; vm.refreshLastThumb() }
    }

    val snapshots by produceState(emptyList<SnapshotItem>(), reload) {
        value = withContext(Dispatchers.IO) { runCatching { vm.media.listSnapshots() }.getOrDefault(emptyList()) }
    }
    val videos by produceState(emptyList<VideoItem>(), reload) {
        value = withContext(Dispatchers.IO) { runCatching { vm.media.listVideos() }.getOrDefault(emptyList()) }
    }

    fun selectedUris(): List<android.net.Uri> =
        if (tab == 0) snapshots.filter { it.rawUri in selected }.flatMap { listOfNotNull(it.rawUri, it.imageUri) }
        else selected.toList()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(if (selected.isEmpty()) "Gallery" else "${selected.size} selected") },
            navigationIcon = {
                if (selected.isEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                else IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
            },
            actions = {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = {
                        val uris = if (tab == 0) snapshots.filter { it.rawUri in selected }.mapNotNull { it.imageUri } else selected.toList()
                        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType(if (tab == 0) "image/png" else "video/mp4")
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(i, "Share"))
                    }) { Icon(Icons.Rounded.Share, "Share") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ui.Bg),
        )
        TabRow(selectedTabIndex = tab, containerColor = Ui.Bg, contentColor = Ui.Accent) {
            Tab(tab == 0, { tab = 0; selected = emptySet() }, text = { Text("Snapshots (${snapshots.size})") })
            Tab(tab == 1, { tab = 1; selected = emptySet() }, text = { Text("Videos (${videos.size})") })
        }
        val empty = if (tab == 0) snapshots.isEmpty() else videos.isEmpty()
        if (empty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) "No snapshots yet.\nTap the white shutter button in the live view to take one."
                    else "No videos yet.\nTap the red button in the live view to record.",
                    color = Ui.TextDim, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == 0) items(snapshots, key = { it.rawUri.toString() }) { item ->
                    val isSel = item.rawUri in selected
                    Tile(
                        uri = item.imageUri ?: item.rawUri, date = item.dateMillis, selected = isSel, video = false,
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (isSel) selected - item.rawUri else selected + item.rawUri
                            else onOpen(item)
                        },
                        onLongClick = { selected = selected + item.rawUri },
                    )
                } else items(videos, key = { it.uri.toString() }) { v ->
                    val isSel = v.uri in selected
                    Tile(
                        uri = v.uri, date = v.dateMillis, selected = isSel, video = true,
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (isSel) selected - v.uri else selected + v.uri
                            else ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        },
                        onLongClick = { selected = selected + v.uri },
                    )
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${selected.size} item${if (selected.size == 1) "" else "s"}?") },
        text = { Text(if (tab == 0) "Both the image and its RAW temperature file are removed." else "The videos are removed from the phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                val uris = selectedUris()
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        uris.filter { u -> runCatching { ctx.contentResolver.delete(u, null, null) }.isFailure }
                    }
                    if (failed.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                        // files from an earlier install: the system asks the user to confirm
                        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, failed)
                        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        selected = emptySet(); reload++; vm.refreshLastThumb()
                    }
                }
            }) { Text("Delete", color = Ui.Hot) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Tile(uri: android.net.Uri, date: Long, selected: Boolean, video: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(uri, Size(400, 300), null) }.getOrNull() }
    }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Ui.Panel)
            .border(2.dp, if (selected) Ui.Accent else Ui.Panel, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Ui.PanelHigh), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (video) Icon(Icons.Rounded.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(40.dp))
            if (selected) Icon(
                Icons.Rounded.CheckCircle, null, tint = Ui.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Ui.Bg),
            )
        }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(date)),
            style = MaterialTheme.typography.bodySmall, color = Ui.TextDim,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
    Spacer(Modifier.height(0.dp))
}
