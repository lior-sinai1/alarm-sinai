package com.alarmsinai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AlarmRed    = Color(0xFFE53935)
val AlarmGreen  = Color(0xFF43A047)
val AlarmOrange = Color(0xFFFB8C00)
val AlarmGray   = Color(0xFF757575)

private val DarkColors = darkColorScheme(
    primary   = AlarmGreen,
    secondary = AlarmOrange,
    error     = AlarmRed,
    background = Color(0xFF121212),
    surface    = Color(0xFF1E1E1E),
)

@Composable
fun AlarmSinaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
