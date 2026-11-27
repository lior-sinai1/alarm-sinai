package com.alarmsinai.ui.screens.cameras

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alarmsinai.data.model.CameraInfo
import com.alarmsinai.data.model.CameraZone
import com.alarmsinai.data.model.CamerasOverview
import com.alarmsinai.data.model.Detection
import com.alarmsinai.data.model.ScheduleRange
import com.alarmsinai.ui.theme.AlarmOrange
import com.alarmsinai.viewmodel.CamerasViewModel

private val DAY_NAMES = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")   // 0 = Sunday

@Composable
fun CameraSettingsTab(vm: CamerasViewModel) {
    val overview by vm.overview.collectAsState()
    val busy by vm.busy.collectAsState()
    var editing by remember { mutableStateOf<CameraInfo?>(null) }   // id "" = new camera
    var scanOpen by remember { mutableStateOf(false) }
    val ov = overview

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ov == null) {
            Text("טוען...", color = Color.Gray)
            return@Column
        }

        // ── Cameras ──
        SectionTitle("מצלמות")
        ov.cameras.forEach { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { editing = c }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.Bold)
                        Text("${c.ip} · ${analysisText(c)} · ${c.retentionDays} ימים", fontSize = 12.sp, color = Color.Gray)
                    }
                    Icon(Icons.Default.Edit, "עריכה")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(onClick = { scanOpen = true; vm.scan() }) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(4.dp)); Text("חיפוש ברשת")
            }
            OutlinedButton(onClick = { editing = CameraInfo() }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("הוספה ידנית")
            }
        }

        // ── When detection runs ──
        DetectionSection(ov, onSave = { d, r -> vm.saveGlobal(d, r) })

        // ── Authorised people ──
        FacesSection(vm, ov)

        // ── Connection ──
        AddressSection(vm)
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { cam ->
        CameraEditor(vm, cam, ov?.faceSupported == true, onClose = { editing = null })
    }
    if (scanOpen) ScanDialog(vm, onPick = { dev ->
        scanOpen = false
        vm.clearScan()
        editing = CameraInfo(name = dev.name ?: "מצלמה ${(ov?.cameras?.size ?: 0) + 1}", ip = dev.ip)
    }, onClose = { scanOpen = false; vm.clearScan() })
}

private fun analysisText(c: CameraInfo) = when {
    c.analysis == "motion" -> "תנועה בלבד"
    c.alertType == "stranger" -> "זר (זיהוי פנים)"
    c.alertType == "person" -> "כל אדם"
    else -> "חכם, ללא התראה"
}

@Composable
internal fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(label: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Color.Unspecified else Color.Gray)
            sub?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> RadioRow(options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled(value)) { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == value, onClick = { onSelect(value) }, enabled = enabled(value))
                Text(label, color = if (enabled(value)) Color.Unspecified else Color.Gray)
            }
        }
    }
}

// ── Detection schedule + response ───────────────────────────────────────────────
@Composable
private fun DetectionSection(ov: CamerasOverview, onSave: (Detection, String) -> Unit) {
    var manual by remember(ov.detection) { mutableStateOf(ov.detection.manual) }
    var armLinked by remember(ov.detection) { mutableStateOf(ov.detection.armLinked) }
    var schedOn by remember(ov.detection) { mutableStateOf(ov.detection.schedule.enabled) }
    val ranges = remember(ov.detection) { mutableStateListOf(*ov.detection.schedule.ranges.toTypedArray()) }
    var response by remember(ov.response) { mutableStateOf(ov.response) }

    SectionTitle("מתי הזיהוי פעיל")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SwitchRow("ידני — פעיל תמיד", checked = manual) { manual = it }
            SwitchRow("כשהאזעקה דרוכה", "הזיהוי נדלק יחד עם דריכת האזעקה", armLinked) { armLinked = it }
            SwitchRow("לפי שעות קבועות", checked = schedOn) { schedOn = it }
            if (schedOn) {
                ranges.forEachIndexed { i, r ->
                    RangeEditor(r, onChange = { ranges[i] = it }, onDelete = { ranges.removeAt(i) })
                }
                if (ranges.size < 10) TextButton(onClick = { ranges.add(ScheduleRange()) }) { Text("+ טווח שעות") }
            }
            Text(
                "ההקלטה עצמה פועלת תמיד; ההגדרה הזו קובעת מתי נשלחות התראות.",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    SectionTitle("מה קורה בהתראה")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            RadioRow(
                listOf("push" to "התראה לטלפון", "siren" to "סירנה", "both" to "סירנה + התראה לטלפון"),
                selected = response, onSelect = { response = it }
            )
            Text(
                "הסירנה (M20) נשארת דולקת עד \"עצור סירנה\" באפליקציה או עד נטרול האזעקה.",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)
            )
        }
    }
    Button(
        onClick = { onSave(Detection(manual, armLinked, com.alarmsinai.data.model.Schedule(schedOn, ranges.toList())), response) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) { Text("שמירת הגדרות זיהוי") }
}

@Composable
private fun RangeEditor(r: ScheduleRange, onChange: (ScheduleRange) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))) {
        Column(Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DAY_NAMES.forEachIndexed { d, n ->
                    FilterChip(
                        selected = d in r.days,
                        onClick = {
                            val days = if (d in r.days) r.days - d else (r.days + d).sorted()
                            if (days.isNotEmpty()) onChange(r.copy(days = days))
                        },
                        label = { Text(n) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeField("מ-", r.start, Modifier.weight(1f)) { onChange(r.copy(start = it)) }
                Spacer(Modifier.width(8.dp))
                TimeField("עד", r.end, Modifier.weight(1f)) { onChange(r.copy(end = it)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "מחיקה") }
            }
        }
    }
}

private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onValid: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it == ':' }.take(5)
            if (TIME_RE.matches(text)) onValid(text)
        },
        label = { Text(label) },
        isError = !TIME_RE.matches(text),
        singleLine = true,
        placeholder = { Text("22:00") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

// ── Camera editor ───────────────────────────────────────────────────────────────
@Composable
private fun CameraEditor(vm: CamerasViewModel, cam: CameraInfo, faceSupported: Boolean, onClose: () -> Unit) {
    val isNew = cam.id.isEmpty()
    val busy by vm.busy.collectAsState()
    var name by remember { mutableStateOf(cam.name) }
    var ip by remember { mutableStateOf(cam.ip) }
    var user by remember { mutableStateOf(cam.username) }
    var pass by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf(cam.analysis) }
    var alertType by remember { mutableStateOf(cam.alertType) }
    var face by remember { mutableStateOf(cam.faceRecognition) }
    var days by remember { mutableStateOf(cam.retentionDays.toString()) }
    var rotation by remember { mutableIntStateOf(cam.rotation) }
    var tilt by remember { mutableDoubleStateOf(cam.tilt) }
    var zone by remember { mutableStateOf(cam.zone) }
    var zoneOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val daysInt = days.toIntOrNull()
    val ipOk = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(ip)
    val valid = name.isNotBlank() && ipOk && daysInt != null && daysInt in 1..365 && (!isNew || pass.isNotEmpty())

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(if (isNew) "מצלמה חדשה" else "עריכת ${cam.name}", style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))

                SectionTitle("חיבור")
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("שם") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ip, { ip = it.trim() }, label = { Text("כתובת IP") }, singleLine = true, isError = ip.isNotEmpty() && !ipOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("שם משתמש של המצלמה") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text(if (isNew) "סיסמת המצלמה" else "סיסמה (ריק = ללא שינוי)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("הסיסמה נשמרת רק במחשב הבית, מוצפנת, ולא חוזרת לטלפון.", fontSize = 11.sp, color = Color.Gray)

                SectionTitle("ניתוח")
                RadioRow(
                    listOf("smart" to "חכם — זיהוי אנשים / רכבים / בעלי חיים", "motion" to "תנועה בלבד (ללא התראות)"),
                    selected = analysis,
                    onSelect = { analysis = it; if (it == "motion") { alertType = "none"; face = false } }
                )

                SectionTitle("זיהוי פנים")
                SwitchRow(
                    "זיהוי פנים במצלמה הזו",
                    if (faceSupported) null else "לא זמין בשרת הזה",
                    checked = face, enabled = faceSupported && analysis == "smart"
                ) { face = it; if (!it && alertType == "stranger") alertType = "person" }

                SectionTitle("התראה")
                RadioRow(
                    listOf("stranger" to "רק אדם לא מורשה (זר)", "person" to "כל אדם", "none" to "ללא התראה"),
                    selected = alertType,
                    enabled = { t -> analysis == "smart" && (t != "stranger" || face) || t == "none" },
                    onSelect = { alertType = it }
                )
                if (alertType == "stranger") Text(
                    "עד שיוזנו אנשים מורשים לא יישלחו התראות \"זר\".",
                    fontSize = 12.sp, color = AlarmOrange
                )

                SectionTitle("אזור או קו")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (zone?.type) { "polygon" -> "אזור מסומן (${zone?.points?.size} נקודות)"; "line" -> "קו מסומן"; else -> "כל התמונה" },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { zoneOpen = true }, enabled = !isNew && analysis == "smart") { Text("סימון") }
                }
                if (isNew) Text("אפשר לסמן אזור אחרי שמירת המצלמה.", fontSize = 12.sp, color = Color.Gray)

                SectionTitle("שמירת הקלטות")
                OutlinedTextField(
                    days, { days = it.filter(Char::isDigit).take(3) }, label = { Text("ימים (1-365)") }, singleLine = true,
                    isError = daysInt == null || daysInt !in 1..365,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )

                SectionTitle("סיבוב תצוגה")
                RotatePanel(rotation, tilt, onRotate = { rotation = it }, onTilt = { tilt = it }, onSave = { _, _ -> }, showSave = false)

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = valid && !busy,
                        onClick = {
                            val body = mutableMapOf<String, Any?>(
                                "name" to name.trim(), "ip" to ip, "username" to user,
                                "analysis" to analysis, "alertType" to alertType, "faceRecognition" to face,
                                "retentionDays" to daysInt, "rotation" to rotation, "tilt" to tilt, "zone" to zone
                            )
                            if (pass.isNotEmpty()) body["password"] = pass
                            vm.saveCamera(cam.id.ifEmpty { null }, body) { ok -> if (ok) onClose() }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("שמירה") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("ביטול") }
                }
                if (!isNew) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("הסרת המצלמה", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "שינוי חיבור / ניתוח / אזור מפעיל מחדש את מערכת המצלמות (כדקה). סיבוב לא.",
                    fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (zoneOpen) ZoneEditor(vm, cam, zone, onDone = { z -> zone = z; zoneOpen = false }, onCancel = { zoneOpen = false })

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("להסיר את ${cam.name}?") },
        text = { Text("המצלמה תוסר מהמערכת. הקלטות קיימות יימחקו לפי זמן השמירה.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCamera(cam.id, onClose) }) { Text("הסרה") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } }
    )
}

// ── LAN scan ────────────────────────────────────────────────────────────────────
@Composable
private fun ScanDialog(vm: CamerasViewModel, onPick: (com.alarmsinai.data.model.ScanDevice) -> Unit, onClose: () -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val found by vm.scan.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("מצלמות ברשת הביתית") },
        text = {
            Column {
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("מחפש... (כ-10 שניות)", fontSize = 12.sp, color = Color.Gray)
                }
                found?.let { list ->
                    if (list.isEmpty()) Text("לא נמצאו מצלמות")
                    list.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = d.addedAs == null) { onPick(d) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.ip, fontWeight = FontWeight.Bold)
                                Text(
                                    listOfNotNull(d.name, if (d.onvif) "ONVIF" else null, if (d.rtsp) "RTSP" else null).joinToString(" · "),
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                            Text(d.addedAs?.let { "קיימת: $it" } ?: "הוספה", color = if (d.addedAs == null) MaterialTheme.colorScheme.primary else Color.Gray)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.scan() }, enabled = !scanning) { Text("חיפוש שוב") } },
        dismissButton = { TextButton(onClick = onClose) { Text("סגירה") } }
    )
}

// ── Authorised faces ────────────────────────────────────────────────────────────
@Composable
private fun FacesSection(vm: CamerasViewModel, ov: CamerasOverview) {
    val faces by vm.faces.collectAsState()
    val context = LocalContext.current
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var newName by remember { mutableStateOf("") }
    var addTo by remember { mutableStateOf<String?>(null) }   // existing person to add a photo to

    LaunchedEffect(ov.faceSupported) { if (ov.faceSupported) vm.loadFaces() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = addTo
        if (target != null) {
            upload(context, vm, target, uri); addTo = null
        } else pendingImage = uri
    }

    SectionTitle("אנשים מורשים")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (!ov.faceSupported) {
                Text("זיהוי פנים לא זמין כרגע בשרת (צריך Frigate 0.16 ומעלה).", color = Color.Gray)
                return@Column
            }
            if (faces.isEmpty()) Text("עדיין לא הוזנו אנשים. הוסף לכל אדם 2-5 תמונות פנים ברורות.", fontSize = 12.sp, color = Color.Gray)
            faces.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name)
                        Text("${f.images} תמונות", fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { addTo = f.name; picker.launch("image/*") }) { Text("+ תמונה") }
                    IconButton(onClick = { vm.deleteFace(f.name) }) { Icon(Icons.Default.Delete, "מחיקה") }
                }
            }
            OutlinedButton(onClick = { addTo = null; picker.launch("image/*") }, modifier = Modifier.padding(top = 6.dp)) {
                Text("+ אדם מורשה חדש")
            }
            Text("התמונות נשמרות רק במחשב הבית, על הדיסק המוצפן.", fontSize = 11.sp, color = Color.Gray)
        }
    }

    pendingImage?.let { uri ->
        val nameOk = Regex("^[\\p{L}\\p{N} _-]{1,40}$").matches(newName) && newName.trim() == newName
        AlertDialog(
            onDismissRequest = { pendingImage = null },
            title = { Text("שם האדם") },
            text = {
                OutlinedTextField(newName, { newName = it.take(40) }, singleLine = true, isError = newName.isNotEmpty() && !nameOk,
                    supportingText = { Text("אותיות, ספרות, רווח, - או _") })
            },
            confirmButton = {
                TextButton(enabled = nameOk, onClick = { upload(context, vm, newName, uri); pendingImage = null; newName = "" }) { Text("שמירה") }
            },
            dismissButton = { TextButton(onClick = { pendingImage = null }) { Text("ביטול") } }
        )
    }
}

private fun upload(context: android.content.Context, vm: CamerasViewModel, name: String, uri: Uri) {
    val cr = context.contentResolver
    val mime = cr.getType(uri)?.takeIf { it == "image/png" || it == "image/jpeg" } ?: "image/jpeg"
    val bytes = try { cr.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null } ?: return
    vm.addFace(name, bytes, mime)
}

// ── Connection routes ───────────────────────────────────────────────────────────
@Composable
private fun AddressSection(vm: CamerasViewModel) {
    val route by vm.route.collectAsState()
    var lan by remember { mutableStateOf(vm.repo.lanUrl) }
    var ts by remember { mutableStateOf(vm.repo.tailscaleUrl) }
    SectionTitle("חיבור לווידאו")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("חיבור נוכחי: ${route?.label ?: "-"}", fontSize = 13.sp)
            OutlinedTextField(lan, { lan = it }, label = { Text("כתובת בבית") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ts, { ts = it }, label = { Text("כתובת Tailscale (לצפייה מרחוק)") }, placeholder = { Text("http://100.x.x.x:3000") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("בבית — חיבור ישיר. מחוץ לבית — Tailscale אם הוגדר, אחרת ngrok.", fontSize = 11.sp, color = Color.Gray)
            Button(onClick = { vm.saveAddresses(lan, ts) }, modifier = Modifier.padding(top = 6.dp)) { Text("שמירה ובדיקה") }
        }
    }
}
