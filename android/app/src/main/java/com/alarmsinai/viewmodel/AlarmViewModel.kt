package com.alarmsinai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alarmsinai.data.AlarmRepository
import com.alarmsinai.data.model.HistoryEvent
import com.alarmsinai.data.model.SENSOR_BYPASS_MAP
import com.alarmsinai.data.model.SENSOR_NAMES
import com.alarmsinai.data.model.StatusResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    val repository = AlarmRepository(application)

    // ── Status ────────────────────────────────────────────────────────────────
    private val _status = MutableStateFlow<StatusResponse?>(null)
    val status: StateFlow<StatusResponse?> = _status.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    // ── History ───────────────────────────────────────────────────────────────
    private val _history = MutableStateFlow<List<HistoryEvent>>(emptyList())
    val history: StateFlow<List<HistoryEvent>> = _history.asStateFlow()

    // ── Disabled sensors ──────────────────────────────────────────────────────
    private val _disabledSensors = MutableStateFlow<Set<String>>(emptySet())
    val disabledSensors: StateFlow<Set<String>> = _disabledSensors.asStateFlow()

    // ── Command feedback ──────────────────────────────────────────────────────
    private val _commandError = MutableStateFlow<String?>(null)
    val commandError: StateFlow<String?> = _commandError.asStateFlow()

    private var pollJob: Job? = null
    private var prevM175: Int? = null
    private var prevM19: Int? = null

    init {
        _history.value = repository.loadHistory()
        _disabledSensors.value = repository.loadDisabledSensors()
        startPolling()
    }

    // ── Polling ───────────────────────────────────────────────────────────────
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                try {
                    val s = repository.fetchStatus()
                    _status.value = s
                    _connectionError.value = false
                    detectChanges(s)
                } catch (_: Exception) {
                    _connectionError.value = true
                }
                delay(3_000)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel() }

    private fun detectChanges(s: StatusResponse) {
        if (prevM19 == 0 && s.m19 == 1)
            addHistoryEvent("alarm", "אזעקה!", "פריצה — מערכת האזעקה הופעלה!")
        prevM175 = s.m175
        prevM19  = s.m19
        // Sync disabled-sensors display set from PLC bypass state
        val bypassed = s.bypasses.filter { it.value == 1 }.keys.toSet()
        _disabledSensors.value = bypassed
    }

    private fun addHistoryEvent(type: String, title: String, body: String) {
        val event = HistoryEvent(type, title, body)
        val updated = listOf(event) + _history.value
        _history.value = updated
        repository.saveHistory(updated)
    }

    // ── Commands ──────────────────────────────────────────────────────────────
    fun toggleZone(zone: Int) {
        viewModelScope.launch {
            try {
                val armed = _status.value?.m175 == 1
                if (armed) repository.disarm() else repository.arm(zone)
            } catch (e: Exception) {
                _commandError.value = "שגיאה: ${e.message}"
            }
        }
    }

    fun clearCommandError() { _commandError.value = null }

    // ── History management ────────────────────────────────────────────────────
    fun clearHistory() {
        _history.value = emptyList()
        repository.clearHistory()
    }

    // ── Sensor management ────────────────────────────────────────────────────
    fun toggleSensor(addr: String) {
        val bypassCoil = SENSOR_BYPASS_MAP[addr] ?: return
        val currentlyBypassed = addr in _disabledSensors.value
        val newValue = !currentlyBypassed
        // Optimistic update
        val updated = _disabledSensors.value.toMutableSet()
        if (newValue) updated.add(addr) else updated.remove(addr)
        _disabledSensors.value = updated
        viewModelScope.launch {
            try {
                repository.writeCoil(bypassCoil, newValue)
            } catch (e: Exception) {
                // Revert optimistic update on failure
                val reverted = _disabledSensors.value.toMutableSet()
                if (!newValue) reverted.add(addr) else reverted.remove(addr)
                _disabledSensors.value = reverted
                _commandError.value = "שגיאה: ${e.message}"
            }
        }
    }

    // ── Computed helpers ──────────────────────────────────────────────────────
    fun visibleSensors(): List<Pair<String, String>> =
        SENSOR_NAMES.entries
            .filter { it.key !in _disabledSensors.value }
            .map { it.key to it.value }

    fun registerFcmToken(token: String) {
        viewModelScope.launch {
            try { repository.registerToken(token) } catch (_: Exception) {}
        }
    }
}
