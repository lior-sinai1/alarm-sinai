package com.alarmsinai.data

import android.content.Context
import android.content.SharedPreferences
import com.alarmsinai.data.model.ArmRequest
import com.alarmsinai.data.model.HistoryEvent
import com.alarmsinai.data.model.StatusResponse
import com.alarmsinai.data.model.TokenRequest
import com.alarmsinai.data.model.WriteCoilRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AlarmRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Server URL (editable via settings) ───────────────────────────────────
    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("server_url", v).apply()

    private fun buildApi(): ApiService {
        val url = serverUrl.trimEnd('/') + "/"
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            })
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    suspend fun fetchStatus(): StatusResponse = buildApi().getStatus()

    suspend fun arm(zone: Int) = buildApi().arm(ArmRequest(zone))

    suspend fun disarm() = buildApi().disarm()

    suspend fun registerToken(token: String) =
        buildApi().registerToken(TokenRequest(token))

    suspend fun writeCoil(coil: Int, value: Boolean) =
        buildApi().writeCoil(WriteCoilRequest(coil, value))

    // ── History persistence ───────────────────────────────────────────────────
    fun loadHistory(): MutableList<HistoryEvent> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<HistoryEvent>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveHistory(events: List<HistoryEvent>) {
        prefs.edit().putString(KEY_HISTORY, gson.toJson(events)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // ── Disabled sensors persistence ──────────────────────────────────────────
    fun loadDisabledSensors(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_SENSORS, emptySet()) ?: emptySet()

    fun saveDisabledSensors(disabled: Set<String>) {
        prefs.edit().putStringSet(KEY_DISABLED_SENSORS, disabled).apply()
    }

    companion object {
        const val DEFAULT_URL = "https://demystify-unplug-sassy.ngrok-free.dev"
        private const val KEY_HISTORY = "history"
        private const val KEY_DISABLED_SENSORS = "disabled_sensors"
    }
}
