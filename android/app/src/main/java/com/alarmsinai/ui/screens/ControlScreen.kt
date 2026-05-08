package com.alarmsinai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.alarmsinai.data.model.StatusResponse
import com.alarmsinai.ui.theme.AlarmGray
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmOrange
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.AlarmViewModel

@Composable
fun ControlScreen(vm: AlarmViewModel) {
    val status by vm.status.collectAsState()
    val error  by vm.connectionError.collectAsState()
    val cmdErr by vm.commandError.collectAsState()
    val visibleSensors = vm.visibleSensors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection indicator
        ConnectionBadge(connected = !error && status?.connected == true)

        // Main status display
        StatusCard(status)

        // Zone buttons
        ZoneButtons(status, vm)

        // Sensors grid
        if (visibleSensors.isNotEmpty()) {
            Text("חיישנים", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
            SensorsGrid(status, visibleSensors)
        }
    }

    // Command error snackbar
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
private fun ConnectionBadge(connected: Boolean) {
    val color = if (connected) AlarmGreen else AlarmRed
    val label = if (connected) "מחובר" else "לא מחובר"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, shape = RoundedCornerShape(50))
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun StatusCard(status: StatusResponse?) {
    val (text, color) = when {
        status == null          -> "מתחבר..." to AlarmGray
        status.m19 == 1        -> "אזעקה!" to AlarmRed
        status.m175 == 1 && status.mw1 > 0 -> "מערכת דרוכה — ${status.mw1} שניות" to AlarmGreen
        status.m175 == 1       -> "מערכת דרוכה" to AlarmGreen
        status.sensors.any { it.value == 1 } ->
            "פרוץ: ${status.sensors.entries.first { it.value == 1 }.key}" to AlarmOrange
        else                   -> "מערכת מוכנה" to AlarmGray
    }

    val infinite = rememberInfiniteTransition(label = "blink")
    val alpha by if (status?.m19 == 1) {
        infinite.animateFloat(
            initialValue = 1f, targetValue = 0.2f, label = "blink",
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .alpha(if (status?.m19 == 1) alpha else 1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ZoneButtons(status: StatusResponse?, vm: AlarmViewModel) {
    val armed = status?.m175 == 1
    val zones = listOf(
        9  to "היקפית",
        10 to "נפח",
        11 to "קומה א'",
        12 to "כללית"
    )
    val btnColor by animateColorAsState(
        targetValue = if (armed) AlarmRed else AlarmGreen,
        label = "zoneColor"
    )

    Text(
        if (armed) "לחץ לנטרול" else "בחר מעגל לדריכה",
        style = MaterialTheme.typography.titleSmall,
        color = Color.Gray
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        zones.forEach { (zone, label) ->
            Button(
                onClick = { vm.toggleZone(zone) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor)
            ) {
                Text(label, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SensorsGrid(
    status: StatusResponse?,
    visibleSensors: List<Pair<String, String>>
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(visibleSensors) { (addr, name) ->
            val breached = status?.sensors?.get(addr) == 1
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (breached) AlarmRed.copy(alpha = 0.2f)
                                     else MaterialTheme.colorScheme.surface
                ),
                border = if (breached) androidx.compose.foundation.BorderStroke(1.dp, AlarmRed) else null
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (breached) AlarmRed else AlarmGreen,
                                RoundedCornerShape(50)
                            )
                    )
                    Text(
                        name,
                        fontSize = 11.sp,
                        color = if (breached) AlarmRed else Color.White,
                        fontWeight = if (breached) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
