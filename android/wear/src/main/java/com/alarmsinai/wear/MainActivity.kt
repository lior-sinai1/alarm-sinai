package com.alarmsinai.wear

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class StatusResponse(val ok: Boolean, val connected: Boolean, val m175: Int, val m19: Int)
data class ArmRequest(val zone: Int)
data class GenericResponse(val ok: Boolean)

interface WearApiService {
    @GET("status") suspend fun status(): StatusResponse
    @POST("arm") suspend fun arm(@Body body: ArmRequest): GenericResponse
    @POST("disarm") suspend fun disarm(@Body body: Map<String, String>): GenericResponse
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val url = prefs.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL
        val api = Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WearApiService::class.java)

        setContent { WearApp(api) }
    }

    companion object {
        const val DEFAULT_URL = "http://192.168.1.92:3000"
    }
}

@Composable
fun WearApp(api: WearApiService) {
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var stateChangedAt by remember { mutableStateOf(0L) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val prevStateKey = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val s = withContext(Dispatchers.IO) { api.status() }
                val newKey = "${s.m175}_${s.m19}"
                if (newKey != prevStateKey.value) {
                    stateChangedAt = System.currentTimeMillis()
                    prevStateKey.value = newKey
                }
                status = s
                error = false
            } catch (e: Exception) {
                error = true
            }
            delay(3000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (stateChangedAt > 0) {
                elapsedSeconds = (System.currentTimeMillis() - stateChangedAt) / 1000
            }
            delay(1000)
        }
    }

    val isArmed = status?.m175 == 1
    val isAlarm = status?.m19 == 1

    val bgColor = when {
        error   -> Color(0xFF1A1A1A)
        isAlarm -> Color(0xFF1A0000)
        isArmed -> Color(0xFF001A00)
        else    -> Color(0xFF121212)
    }

    val statusText = when {
        status == null -> "..."
        error          -> "אין חיבור"
        isAlarm        -> "אזעקה!"
        isArmed        -> "דרוך"
        else           -> "מנוטרל"
    }

    val timerText: String? = if (status != null && !error && (isArmed || isAlarm)) {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        "%02d:%02d".format(m, s)
    } else null

    val statusBoxColor = when {
        error   -> Color(0xFF424242)
        isAlarm -> Color(0xFFB71C1C)
        isArmed -> Color(0xFF1B5E20)
        else    -> Color(0xFF2C2C2C)
    }

    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusBoxColor)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = statusText,
                            fontSize = if (isAlarm) 24.sp else 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        if (timerText != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = timerText,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Chip(
                        onClick = {
                            if (!loading && status != null && !error && !isArmed && !isAlarm) {
                                coroutineScope.launch {
                                    loading = true
                                    try {
                                        withContext(Dispatchers.IO) { api.arm(ArmRequest(4)) }
                                        delay(500)
                                        val s = withContext(Dispatchers.IO) { api.status() }
                                        status = s
                                        error = false
                                    } catch (e: Exception) {
                                        error = true
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        },
                        enabled = !loading && status != null && !error && !isArmed && !isAlarm,
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color(0xFF2E7D32),
                            disabledBackgroundColor = Color(0xFF1B3A1C)
                        ),
                        modifier = Modifier.weight(1f).height(40.dp),
                        label = {
                            Text(
                                "דרוך",
                                color = if (!isArmed && !isAlarm && status != null && !error)
                                    Color.White else Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )

                    Chip(
                        onClick = {
                            if (!loading && status != null && !error && (isArmed || isAlarm)) {
                                coroutineScope.launch {
                                    loading = true
                                    try {
                                        withContext(Dispatchers.IO) { api.disarm(emptyMap()) }
                                        delay(500)
                                        val s = withContext(Dispatchers.IO) { api.status() }
                                        status = s
                                        error = false
                                    } catch (e: Exception) {
                                        error = true
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        },
                        enabled = !loading && status != null && !error && (isArmed || isAlarm),
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color(0xFFC62828),
                            disabledBackgroundColor = Color(0xFF3A1A1A)
                        ),
                        modifier = Modifier.weight(1f).height(40.dp),
                        label = {
                            Text(
                                "נטרל",
                                color = if (isArmed || isAlarm) Color.White else Color.White.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )
                }

                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        indicatorColor = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
