package com.alarmsinai.ui.screens.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import com.alarmsinai.data.model.CameraEvent
import com.alarmsinai.data.model.LABEL_NAMES
import com.alarmsinai.viewmodel.CamerasViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("dd/MM HH:mm:ss", Locale("he"))

@Composable
fun RecordingsTab(vm: CamerasViewModel) {
    val overview by vm.overview.collectAsState()
    val events by vm.events.collectAsState()
    val loading by vm.eventsLoading.collectAsState()
    val end by vm.eventsEnd.collectAsState()
    val openEvent by vm.openEvent.collectAsState()
    var camera by rememberSaveable { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<CameraEvent?>(null) }

    LaunchedEffect(camera) { vm.loadEvents(camera) }
    // From a notification: open that event as soon as it is in the list.
    LaunchedEffect(openEvent, events) {
        val id = openEvent ?: return@LaunchedEffect
        events.find { it.id == id }?.let { playing = it; vm.showEvent(null) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = camera == null, onClick = { camera = null }, label = { Text("הכול") })
            overview?.cameras.orEmpty().forEach { c ->
                FilterChip(selected = camera == c.id, onClick = { camera = c.id }, label = { Text(c.name) })
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.loadEvents(camera) }) { Text("רענון") }
        }

        if (events.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("אין הקלטות", color = Color.Gray)
            }
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(events, key = { it.id }) { e -> EventRow(vm, e, onClick = { playing = e }) }
            item {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(28.dp))
                        !end -> TextButton(onClick = { vm.loadEvents(camera, more = true) }) { Text("טען עוד") }
                    }
                }
            }
        }
    }

    playing?.let { e ->
        val cam = overview?.cameras?.find { it.id == e.cameraId }
        ClipDialog(vm, e, cam?.rotation ?: 0, cam?.tilt ?: 0.0, onClose = { playing = null })
    }
}

private fun describe(e: CameraEvent): String {
    val what = LABEL_NAMES[e.label] ?: e.label
    return if (e.subLabel != null) "$what — ${e.subLabel}" else what
}

private fun duration(e: CameraEvent): String {
    val end = e.endTime ?: return "בתהליך"
    val s = (end - e.startTime).toInt().coerceAtLeast(0)
    return if (s >= 60) "${s / 60}:%02d דק'".format(s % 60) else "$s שנ'"
}

@Composable
private fun EventRow(vm: CamerasViewModel, e: CameraEvent, onClick: () -> Unit) {
    val (thumb, _) = rememberAuthBitmap(vm.repo, "thumb-" + e.id) { vm.repo.eventThumbUrl(e.id) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp, 64.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                if (thumb != null) androidx.compose.foundation.Image(
                    thumb, null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(describe(e))
                Text("${e.cameraName} · ${TIME_FMT.format(Date((e.startTime * 1000).toLong()))}", fontSize = 12.sp, color = Color.Gray)
                Text(duration(e), fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun ClipDialog(vm: CamerasViewModel, e: CameraEvent, rotation: Int, tilt: Double, onClose: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(!e.hasClip) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    LaunchedEffect(e.id) { url = vm.repo.eventClipUrl(e.id) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val u = url
            if (failed) {
                // No clip (or it failed) — show the event snapshot instead.
                val (bmp, _) = rememberAuthBitmap(vm.repo, "snap-" + e.id) { vm.repo.eventSnapshotUrl(e.id) }
                if (bmp != null) RotatedImage(bmp, rotation, tilt, Modifier.fillMaxSize())
            } else if (u != null) {
                ZoomableVideo(
                    repo = vm.repo, url = u, live = false, rotation = rotation, tilt = tilt,
                    modifier = Modifier.fillMaxSize(),
                    onPlayer = { player = it }, onError = { failed = true }
                )
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "סגור", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(describe(e), color = Color.White)
                    Text("${e.cameraName} · ${TIME_FMT.format(Date((e.startTime * 1000).toLong()))}", color = Color.LightGray, fontSize = 12.sp)
                }
                if (!failed) TextButton(onClick = { failed = true }) { Text("תמונה", color = Color.White) }
            }
            if (!failed) player?.let { PlayerControls(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable
private fun PlayerControls(player: ExoPlayer, modifier: Modifier) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying || player.playWhenReady
            delay(300)
        }
    }
    Row(
        modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = !player.playWhenReady
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
        }
        Slider(
            value = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
            modifier = Modifier.weight(1f)
        )
        Text("%d:%02d".format(pos / 60000, pos / 1000 % 60), color = Color.White, fontSize = 12.sp)
    }
}
