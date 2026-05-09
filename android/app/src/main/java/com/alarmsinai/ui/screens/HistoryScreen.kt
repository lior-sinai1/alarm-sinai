package com.alarmsinai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmsinai.data.model.HistoryEvent
import com.alarmsinai.ui.theme.AlarmGray
import com.alarmsinai.ui.theme.AlarmGreen
import com.alarmsinai.ui.theme.AlarmRed
import com.alarmsinai.viewmodel.AlarmViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: AlarmViewModel) {
    val history by vm.history.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("היסטוריה", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { showConfirm = true }, enabled = history.isNotEmpty()) {
                Icon(Icons.Default.Delete, contentDescription = "נקה", tint = AlarmRed)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("אין אירועים", color = AlarmGray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { event -> HistoryItem(event) }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("נקה היסטוריה") },
            text  = { Text("האם למחוק את כל האירועים?") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); showConfirm = false }) {
                    Text("מחק", color = AlarmRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("ביטול") }
            }
        )
    }
}

@Composable
private fun HistoryItem(event: HistoryEvent) {
    val pair = when (event.type) {
        "alarm"  -> Icons.Default.Notifications to AlarmRed
        "arm"    -> Icons.Default.Lock          to AlarmGreen
        "disarm" -> Icons.Default.LockOpen      to AlarmGray
        else     -> Icons.Default.Notifications to AlarmGray
    }
    val icon  = pair.first
    val color = pair.second
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = color)
                Text(event.body, fontSize = 12.sp, color = Color.LightGray)
                Text(
                    fmt.format(Date(event.timestamp)),
                    fontSize = 10.sp,
                    color = AlarmGray
                )
            }
        }
    }
}
