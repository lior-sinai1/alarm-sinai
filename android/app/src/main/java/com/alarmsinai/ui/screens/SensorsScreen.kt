package com.alarmsinai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alarmsinai.data.model.SENSOR_BYPASS_MAP
import com.alarmsinai.data.model.SENSOR_NAMES
import com.alarmsinai.viewmodel.AlarmViewModel

@Composable
fun SensorsScreen(vm: AlarmViewModel) {
    val disabled by vm.disabledSensors.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("ניהול חיישנים", style = MaterialTheme.typography.titleLarge)
        Text(
            "כבה חיישן כדי לבטל אותו בבקר",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(SENSOR_NAMES.entries.toList()) { (addr, name) ->
                val hasBypass = addr in SENSOR_BYPASS_MAP
                val enabled = addr !in disabled
                SensorToggleRow(
                    name      = name,
                    addr      = addr,
                    enabled   = enabled,
                    hasBypass = hasBypass,
                    onToggle  = { if (hasBypass) vm.toggleSensor(addr) }
                )
            }
        }
    }
}

@Composable
private fun SensorToggleRow(
    name: String,
    addr: String,
    enabled: Boolean,
    hasBypass: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(
                alpha = if (enabled) 1f else 0.5f
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    name,
                    color = if (enabled) Color.White else Color.Gray
                )
                Text(
                    if (hasBypass) "M${SENSOR_BYPASS_MAP[addr]}" else "ללא ביטול",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                enabled = hasBypass
            )
        }
    }
}
