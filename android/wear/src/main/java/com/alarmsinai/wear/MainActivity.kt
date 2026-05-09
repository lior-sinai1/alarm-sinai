package com.alarmsinai.wear

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    @POST("disarm") suspend fun disarm(): GenericResponse
}

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val url = prefs.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL
        val api = Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WearApiService::class.java)

        setContent { WearApp(api, scope) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_URL = "http://192.168.1.92:3000"
    }
}

@Composable
fun WearApp(api: WearApiService, scope: CoroutineScope) {
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                status = withContext(Dispatchers.IO) { api.status() }
                error = false
            } catch (e: Exception) {
                error = true
            }
            delay(3000)
        }
    }

    val isArmed = status?.m175 == 1
    val isAlarm = status?.m19 == 1

    val bgColor = when {
        error        -> Color(0xFF424242)
        isAlarm      -> Color(0xFFB71C1C)
        isArmed      -> Color(0xFF1B5E20)
        else         -> Color(0xFF212121)
    }

    val statusText = when {
        status == null -> "..."
        error          -> "אין חיבור"
        isAlarm        -> "אזעקה!"
        isArmed        -> "דרוך"
        else           -> "מנוטרל"
    }

    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = if (isAlarm) 26.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        if (!loading && status != null && !error) {
                            loading = true
                            scope.launch {
                                try {
                                    if (isArmed || isAlarm) api.disarm()
                                    else api.arm(ArmRequest(4))
                                    delay(500)
                                    status = withContext(Dispatchers.IO) { api.status() }
                                } catch (e: Exception) {
                                    error = true
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                    enabled = !loading && status != null && !error,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isArmed || isAlarm) Color(0xFFE53935) else Color(0xFF43A047)
                    ),
                    modifier = Modifier.size(90.dp, 44.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            indicatorColor = Color.White
                        )
                    } else {
                        Text(
                            text = if (isArmed || isAlarm) "נטרל" else "דרוך",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
