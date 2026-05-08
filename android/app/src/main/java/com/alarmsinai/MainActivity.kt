package com.alarmsinai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.alarmsinai.ui.screens.ControlScreen
import com.alarmsinai.ui.screens.HistoryScreen
import com.alarmsinai.ui.screens.SensorsScreen
import com.alarmsinai.ui.theme.AlarmSinaiTheme
import com.alarmsinai.viewmodel.AlarmViewModel
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private val vm: AlarmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register FCM token on first launch
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            vm.registerFcmToken(token)
        }

        setContent {
            AlarmSinaiTheme {
                AlarmApp(vm)
            }
        }
    }

    override fun onResume()  { super.onResume();  vm.startPolling() }
    override fun onPause()   { super.onPause();   vm.stopPolling()  }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("בקרה",   Icons.Default.Home),
    TabItem("היסטוריה", Icons.Default.List),
    TabItem("חיישנים", Icons.Default.Settings),
)

@Composable
private fun AlarmApp(vm: AlarmViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        icon     = { Icon(tab.icon, contentDescription = tab.label) },
                        label    = { Text(tab.label) },
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ControlScreen(vm = vm)
            1 -> HistoryScreen(vm = vm)
            2 -> SensorsScreen(vm = vm)
        }
        // consume insets
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {}
    }
}
