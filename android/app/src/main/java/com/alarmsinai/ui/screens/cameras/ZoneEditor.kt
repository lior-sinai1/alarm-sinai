package com.alarmsinai.ui.screens.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alarmsinai.data.model.CameraInfo
import com.alarmsinai.data.model.CameraZone
import com.alarmsinai.viewmodel.CamerasViewModel
import kotlin.math.min

/**
 * Draw the alert area on a fresh snapshot. The picture is shown in the camera's
 * native orientation (no rotation), so the points match what Frigate sees.
 *   polygon: tap 3-20 corners;  line: tap 2 points (someone crossing it triggers).
 */
@Composable
fun ZoneEditor(vm: CamerasViewModel, cam: CameraInfo, initial: CameraZone?, onDone: (CameraZone?) -> Unit, onCancel: () -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: "polygon") }
    val points = remember { mutableStateListOf<Offset>().apply {
        initial?.points?.forEach { add(Offset(it[0].toFloat(), it[1].toFloat())) }
    } }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val (bmp, failed) = rememberAuthBitmap(vm.repo, "zone-" + cam.id) { vm.repo.snapshotUrl(cam.id, 720) }

    // Where the image sits inside the box (ContentScale.Fit, centred).
    val imgRect: Rect? = bmp?.let {
        if (boxSize == IntSize.Zero) null else {
            val s = min(boxSize.width.toFloat() / it.width, boxSize.height.toFloat() / it.height)
            val w = it.width * s; val h = it.height * s
            Rect(Offset((boxSize.width - w) / 2f, (boxSize.height - h) / 2f), androidx.compose.ui.geometry.Size(w, h))
        }
    }
    val maxPoints = if (type == "line") 2 else 20
    val valid = if (type == "line") points.size == 2 else points.size in 3..20

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = type == "polygon", onClick = { type = "polygon"; points.clear() }, label = { Text("אזור") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = type == "line", onClick = { type = "line"; points.clear() }, label = { Text("קו") })
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (type == "line") "גע בשתי נקודות" else "גע בפינות האזור (3 ומעלה)",
                        color = Color.White, fontSize = 12.sp
                    )
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onSizeChanged { boxSize = it }
                        .pointerInput(imgRect, type) {
                            detectTapGestures { tap ->
                                val r = imgRect ?: return@detectTapGestures
                                if (!r.contains(tap)) return@detectTapGestures
                                val rel = Offset((tap.x - r.left) / r.width, (tap.y - r.top) / r.height)
                                if (points.size >= maxPoints) { if (type == "line") points.clear() else return@detectTapGestures }
                                points.add(rel)
                            }
                        }
                ) {
                    when {
                        bmp != null -> Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        failed -> Text("אין תמונה מהמצלמה", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    imgRect?.let { r ->
                        Canvas(Modifier.fillMaxSize()) {
                            val px = points.map { Offset(r.left + it.x * r.width, r.top + it.y * r.height) }
                            val col = Color(0xFFFF5252)
                            if (type == "polygon" && px.size >= 3) {
                                val path = Path().apply {
                                    moveTo(px[0].x, px[0].y); px.drop(1).forEach { lineTo(it.x, it.y) }; close()
                                }
                                drawPath(path, col.copy(alpha = 0.25f))
                                drawPath(path, col, style = Stroke(width = 4f))
                            } else if (px.size >= 2) {
                                for (i in 0 until px.size - 1) drawLine(col, px[i], px[i + 1], strokeWidth = 6f)
                            }
                            px.forEachIndexed { i, p ->
                                drawCircle(Color.White, 12f, p)
                                drawCircle(col, 8f, p)
                                if (i == 0 && type == "polygon") drawCircle(Color.Yellow, 14f, p, style = Stroke(3f))
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { if (points.isNotEmpty()) points.removeAt(points.lastIndex) }) { Text("בטל נקודה") }
                    OutlinedButton(onClick = { onDone(null) }) { Text("כל התמונה") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("ביטול") }
                    Button(enabled = valid, onClick = {
                        onDone(CameraZone(type, points.map { listOf(round3(it.x), round3(it.y)) }))
                    }) { Text("אישור") }
                }
            }
        }
    }
}

private fun round3(v: Float): Double = (Math.round(v.coerceIn(0f, 1f) * 1000) / 1000.0)
