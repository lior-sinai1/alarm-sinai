package com.alarmsinai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalContext
import com.alarmsinai.ui.screens.ControlScreen
import com.alarmsinai.ui.screens.HistoryScreen
import com.alarmsinai.ui.screens.SensorsScreen
import com.alarmsinai.ui.theme.AlarmSinaiTheme
import com.alarmsinai.viewmodel.AlarmViewModel
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private val vm: AlarmViewModel by viewModels()
    private var sirenJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupAlarmChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
            )
        }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { vm.registerFcmToken(it) }

        setContent {
            AlarmSinaiTheme {
                AlarmApp(vm, ::playAlarmSound, ::stopAlarmSound)
            }
        }
    }

    private fun setupAlarmChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("alarm_channel", "התרעות אזעקה", NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun playAlarmSound() {
        if (sirenJob?.isActive == true) return
        sirenJob = lifecycleScope.launch {
            while (true) {
                val hi = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                hi.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
                delay(650)
                hi.release()
                val lo = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                lo.startTone(ToneGenerator.TONE_CDMA_LOW_L, 600)
                delay(650)
                lo.release()
            }
        }
    }

    fun stopAlarmSound() {
        sirenJob?.cancel()
        sirenJob = null
    }

    override fun onResume() { super.onResume(); vm.startPolling() }
    override fun onPause()  { super.onPause();  vm.stopPolling()  }
    override fun onDestroy() { super.onDestroy(); stopAlarmSound() }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("בקרה",      Icons.Default.Home),
    TabItem("היסטוריה",  Icons.Default.List),
    TabItem("חיישנים",   Icons.Default.Settings),
)

@Composable
private fun AlarmApp(
    vm: AlarmViewModel,
    playAlarm: () -> Unit,
    stopAlarm: () -> Unit
) {
    val status     by vm.status.collectAsState()
    val mw1Running by vm.mw1Running.collectAsState()
    val mw2Running by vm.mw2Running.collectAsState()
    val mw1 = status?.mw1 ?: 0
    val mw2 = status?.mw2 ?: 0
    var selectedTab by remember { mutableIntStateOf(0) }
    var wasAlarm by remember { mutableStateOf(false) }

    // Local audio alert — bypasses silent/DND via USAGE_ALARM
    LaunchedEffect(status?.m19) {
        val isAlarm = status?.m19 == 1
        when {
            isAlarm && !wasAlarm -> playAlarm()
            !isAlarm && wasAlarm -> stopAlarm()
        }
        wasAlarm = isAlarm
    }

    // Tick sound every second while timer is counting
    val timerValue = if (mw2Running) mw2 else if (mw1Running) mw1 else -1
    LaunchedEffect(timerValue) {
        if (timerValue > 0) {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            kotlinx.coroutines.delay(150)
            tone.release()
        }
    }

    // Request DND access once so the notification channel can bypass it
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            context.startActivity(
                android.content.Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

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
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {}
    }
}
