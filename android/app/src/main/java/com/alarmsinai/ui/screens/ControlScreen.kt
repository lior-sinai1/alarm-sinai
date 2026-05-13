package com.alarmsinai.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmsinai.data.model.SENSOR_NAMES
import com.alarmsinai.data.model.StatusResponse
import com.alarmsinai.ui.theme.AlarmGray
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmOrange
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.AlarmViewModel

@Composable
fun ControlScreen(vm: AlarmViewModel) {
    val status   by vm.status.collectAsState()
    val error    by vm.connectionError.collectAsState()
    val cmdErr   by vm.commandError.collectAsState()
    val disabled by vm.disabledSensors.collectAsState()

    val alarm      = status?.m19 == 1
    val armed      = status?.m175 == 1
    val serverMw1  = status?.mw1 ?: 0
    val serverMw2  = status?.mw2 ?: 0
    val mw1Running by vm.mw1Running.collectAsState()
    val mw2Running by vm.mw2Running.collectAsState()

    // Local countdown — syncs from server, ticks every 500ms for smooth display
    var mw1 by remember { mutableIntStateOf(serverMw1) }
    var mw2 by remember { mutableIntStateOf(serverMw2) }
    LaunchedEffect(serverMw1) { mw1 = serverMw1 }
    LaunchedEffect(serverMw2) { mw2 = serverMw2 }
    LaunchedEffect(mw1Running) {
        while (mw1Running) { kotlinx.coroutines.delay(500); if (mw1 > 0) mw1-- }
    }
    LaunchedEffect(mw2Running) {
        while (mw2Running) { kotlinx.coroutines.delay(500); if (mw2 > 0) mw2-- }
    }

    val breachedSensors = status?.sensors
        ?.filter { it.value == 1 && it.key !in disabled }
        ?.mapNotNull { SENSOR_NAMES[it.key] }
        ?: emptyList()

    val bypassedSensors = disabled
        .mapNotNull { SENSOR_NAMES[it] }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConnectionBadge(connected = !error && status?.connected == true)
        StatusCard(status, breachedSensors, bypassedSensors, mw1Running, mw1)
        if (mw2Running) TimerRow(seconds = mw2, label = "דלת כניסה")
        ZoneButtons(status, vm, alarm, armed)
    }

    cmdErr?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            vm.clearCommandError()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(modifier = Modifier.padding(16.dp)) { Text(msg) }
        }
    }
}

@Composable
private fun TimerRow(seconds: Int, label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 18.sp, color = AlarmGreen)
            Text(
                text = "$seconds שניות",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmGreen
            )
        }
    }
}

@Composable
private fun ConnectionBadge(connected: Boolean) {
    val color = if (connected) AlarmGreen else AlarmRed
    val label = if (connected) "מחובר" else "מנסה להתחבר..."
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun StatusCard(
    status: StatusResponse?,
    breachedSensors: List<String>,
    bypassedSensors: List<String> = emptyList(),
    mw1Running: Boolean = false,
    mw1: Int = 0
) {
    val alarm = status?.m19 == 1
    val armed = status?.m175 == 1

    val (text, color) = when {
        status == null               -> "מתחבר..." to AlarmGray
        alarm                        -> "אזעקה!" to AlarmRed
        mw1Running && mw1 > 0        -> "מתחבר בעוד\n$mw1 שניות" to AlarmGreen
        armed                        -> "מערכת דרוכה" to AlarmGreen
        breachedSensors.isNotEmpty() -> breachedSensors.joinToString("\n") to AlarmOrange
        else                         -> "המערכת מוכנה" to AlarmGray
    }

    val infinite = rememberInfiniteTransition(label = "blink")
    val rawBlink by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.15f, label = "blink",
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
    )
    val blinkAlpha = if (alarm) rawBlink else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp)
                .alpha(if (alarm) blinkAlpha else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center,
                lineHeight = 40.sp
            )
            if (alarm && breachedSensors.isNotEmpty()) {
                Text(
                    text = breachedSensors.joinToString(" · "),
                    fontSize = 15.sp,
                    color = AlarmOrange,
                    textAlign = TextAlign.Center
                )
            }
            if (bypassedSensors.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    color = AlarmRed.copy(alpha = 0.3f)
                )
                bypassedSensors.forEach { name ->
                    Text(
                        text = "⛔ $name",
                        fontSize = 11.sp,
                        color = AlarmRed,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneButtons(
    status: StatusResponse?,
    vm: AlarmViewModel,
    alarm: Boolean,
    armed: Boolean
) {
    val zones = listOf(9 to "היקפית", 10 to "נפח", 11 to "קומה א'", 12 to "כללית")

    val infinite = rememberInfiniteTransition(label = "btnBlink")
    val rawBlink by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.25f, label = "btnBlink",
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse)
    )
    val blinkAlpha = if (alarm) rawBlink else 1f

    val btnColor = when {
        alarm -> AlarmRed
        armed -> AlarmGreen
        else  -> AlarmGray
    }

    val hint = if (armed || alarm) "לחץ לנטרול" else "בחר מעגל לדריכה"

    Text(
        hint,
        style = MaterialTheme.typography.titleSmall,
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        zones.forEach { (zone, label) ->
            Button(
                onClick = { vm.toggleZone(zone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .alpha(blinkAlpha),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Text(
                    if (armed || alarm) "נטרל" else label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
