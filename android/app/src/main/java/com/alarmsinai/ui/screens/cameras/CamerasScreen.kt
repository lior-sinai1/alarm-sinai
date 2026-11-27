package com.alarmsinai.ui.screens.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alarmsinai.data.CameraRepository
import com.alarmsinai.data.model.CameraInfo
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmOrange
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.CamerasViewModel

@Composable
fun CamerasScreen(vm: CamerasViewModel) {
    val overview by vm.overview.collectAsState()
    val error by vm.error.collectAsState()
    val message by vm.message.collectAsState()
    val route by vm.route.collectAsState()
    val openEvent by vm.openEvent.collectAsState()
    var section by rememberSaveable { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
    }
    // Opened from an intruder notification → go to recordings.
    LaunchedEffect(openEvent) { if (openEvent != null) section = 1 }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StatusStrip(
                frigateOk = overview?.frigateOk,
                detectionActive = overview?.detectionActive == true,
                route = route,
                error = error
            )
            if (overview?.sirenOn == true) SirenBanner(onStop = vm::stopSiren)

            TabRow(selectedTabIndex = section) {
                listOf("שידור חי", "הקלטות", "הגדרות").forEachIndexed { i, t ->
                    Tab(selected = section == i, onClick = { section = i }, text = { Text(t) })
                }
            }
            when (section) {
                0 -> LiveGrid(vm, overview?.cameras.orEmpty(), onAdd = { section = 2 })
                1 -> RecordingsTab(vm)
                2 -> CameraSettingsTab(vm)
            }
        }
    }
}

@Composable
private fun StatusStrip(frigateOk: Boolean?, detectionActive: Boolean, route: CameraRepository.Route?, error: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (txt, col) = when {
            error != null -> error to AlarmRed
            frigateOk == false -> "מערכת המצלמות לא זמינה" to AlarmOrange
            frigateOk == true -> "מחובר" to AlarmGreen
            else -> "מתחבר..." to Color.Gray
        }
        Chip(txt, col)
        if (error == null && frigateOk == true)
            Chip(if (detectionActive) "זיהוי פעיל" else "זיהוי כבוי", if (detectionActive) AlarmGreen else Color.Gray)
        Spacer(Modifier.weight(1f))
        route?.let { Text(it.label, fontSize = 11.sp, color = Color.Gray) }
    }
}

@Composable
internal fun Chip(text: String, color: Color) {
    Text(
        text, fontSize = 12.sp, color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
private fun SirenBanner(onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AlarmRed).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("הסירנה הופעלה על ידי המצלמות", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AlarmRed)) {
            Text("עצור סירנה")
        }
    }
}

// ── Grid of all cameras ─────────────────────────────────────────────────────────
@Composable
private fun LiveGrid(vm: CamerasViewModel, cameras: List<CameraInfo>, onAdd: () -> Unit) {
    var fullScreen by remember { mutableStateOf<CameraInfo?>(null) }

    if (cameras.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("עדיין לא הוגדרו מצלמות", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAdd) { Text("הוספת מצלמה") }
        }
        return
    }

    // One camera fills the width; more cameras → 2 per row.
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (cameras.size == 1) 1 else 2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(cameras, key = { it.id }) { cam -> CameraTile(vm, cam, onClick = { fullScreen = cam }) }
    }

    fullScreen?.let { cam ->
        // Re-read from the overview so rotation changes show immediately.
        val fresh = cameras.find { it.id == cam.id } ?: cam
        LiveDialog(vm, fresh, onClose = { fullScreen = null })
    }
}

@Composable
private fun CameraTile(vm: CamerasViewModel, cam: CameraInfo, onClick: () -> Unit) {
    val (bmp, failed) = rememberAuthBitmap(vm.repo, cam.id, refreshMs = 2_000) { vm.repo.snapshotUrl(cam.id, 360) }
    val aspect = bmp?.let { shownAspect(it.width, it.height, cam.rotation) } ?: (16f / 9f)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 2.5f)).background(Color.Black)) {
            if (bmp != null) RotatedImage(bmp, cam.rotation, cam.tilt, Modifier.fillMaxSize())
            else if (failed) Text("אין תמונה", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            else CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            Text(
                cam.name, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ── Full screen live ────────────────────────────────────────────────────────────
@Composable
private fun LiveDialog(vm: CamerasViewModel, cam: CameraInfo, onClose: () -> Unit) {
    val route by vm.route.collectAsState()
    // Away from home use the light sub-stream by default (data usage).
    var hq by remember { mutableStateOf(route == CameraRepository.Route.LAN) }
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var rotation by remember(cam.id) { mutableIntStateOf(cam.rotation) }
    var tilt by remember(cam.id) { mutableDoubleStateOf(cam.tilt) }
    var showRotate by remember { mutableStateOf(false) }

    LaunchedEffect(hq) { failed = false; url = vm.repo.liveUrl(cam.id, sub = !hq) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val u = url
            when {
                failed -> SnapshotFallback(vm, cam, rotation, tilt)
                u != null -> ZoomableVideo(
                    repo = vm.repo, url = u, live = true, rotation = rotation, tilt = tilt,
                    modifier = Modifier.fillMaxSize(), onError = { failed = true }
                )
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "סגור", tint = Color.White) }
                Text(cam.name, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { hq = !hq }) {
                    Icon(if (hq) Icons.Default.HighQuality else Icons.Default.Sd, "איכות", tint = Color.White)
                }
                IconButton(onClick = { showRotate = !showRotate }) {
                    Icon(Icons.Default.RotateRight, "סיבוב", tint = Color.White)
                }
            }
            if (route == CameraRepository.Route.NGROK) Text(
                "צפייה דרך ngrok צורכת מהמכסה החודשית",
                color = AlarmOrange, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
            )
            if (showRotate) RotatePanel(
                rotation = rotation, tilt = tilt,
                onRotate = { rotation = it }, onTilt = { tilt = it },
                onSave = { r, t -> vm.saveView(cam, r, t); showRotate = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun SnapshotFallback(vm: CamerasViewModel, cam: CameraInfo, rotation: Int, tilt: Double) {
    val (bmp, _) = rememberAuthBitmap(vm.repo, "fs-" + cam.id, refreshMs = 1_000) { vm.repo.snapshotUrl(cam.id, 720) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) RotatedImage(bmp, rotation, tilt, Modifier.fillMaxSize())
        Text(
            "וידאו לא זמין — מוצגת תמונה מתחלפת", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(6.dp)
        )
    }
}

/** 90° steps plus a fine tilt slider. Saved per camera on the server (display only). */
@Composable
internal fun RotatePanel(
    rotation: Int, tilt: Double,
    onRotate: (Int) -> Unit,
    onTilt: (Double) -> Unit,
    onSave: (Int, Double) -> Unit,
    modifier: Modifier = Modifier,
    showSave: Boolean = true
) {
    Card(modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("סיבוב התמונה", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 90, 180, 270).forEach { r ->
                    FilterChip(selected = rotation == r, onClick = { onRotate(r) }, label = { Text("$r°") })
                }
            }
            Text("כיוונון עדין: %.1f°".format(tilt), fontSize = 12.sp)
            Slider(value = tilt.toFloat(), onValueChange = { onTilt(Math.round(it * 2) / 2.0) }, valueRange = -45f..45f)
            if (showSave) Button(onClick = { onSave(rotation, tilt) }, modifier = Modifier.fillMaxWidth()) { Text("שמור למצלמה הזו") }
        }
    }
}
