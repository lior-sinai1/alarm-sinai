package com.alarmsinai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alarmsinai.data.CameraRepository
import com.alarmsinai.data.model.CameraEvent
import com.alarmsinai.data.model.CameraInfo
import com.alarmsinai.data.model.CamerasOverview
import com.alarmsinai.data.model.Detection
import com.alarmsinai.data.model.Face
import com.alarmsinai.data.model.ScanDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CamerasViewModel(application: Application) : AndroidViewModel(application) {

    val repo = CameraRepository(application)

    private val _overview = MutableStateFlow<CamerasOverview?>(null)
    val overview: StateFlow<CamerasOverview?> = _overview.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Short messages for the user (saved, Frigate restarting, failures). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _route = MutableStateFlow<CameraRepository.Route?>(null)
    val route: StateFlow<CameraRepository.Route?> = _route.asStateFlow()

    private val _events = MutableStateFlow<List<CameraEvent>>(emptyList())
    val events: StateFlow<List<CameraEvent>> = _events.asStateFlow()
    private val _eventsLoading = MutableStateFlow(false)
    val eventsLoading: StateFlow<Boolean> = _eventsLoading.asStateFlow()
    private val _eventsEnd = MutableStateFlow(false)
    val eventsEnd: StateFlow<Boolean> = _eventsEnd.asStateFlow()
    private var eventsCamera: String? = null

    private val _faces = MutableStateFlow<List<Face>>(emptyList())
    val faces: StateFlow<List<Face>> = _faces.asStateFlow()

    private val _scan = MutableStateFlow<List<ScanDevice>?>(null)
    val scan: StateFlow<List<ScanDevice>?> = _scan.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Event opened from an "intruder" notification. */
    private val _openEvent = MutableStateFlow<String?>(null)
    val openEvent: StateFlow<String?> = _openEvent.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshOnce()
                delay(10_000)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    fun refresh() { viewModelScope.launch { refreshOnce() } }

    private suspend fun refreshOnce() {
        try {
            _overview.value = repo.overview()
            _error.value = null
        } catch (e: Exception) {
            _error.value = CameraRepository.errorText(e)
        }
        _route.value = repo.route
    }

    fun clearMessage() { _message.value = null }
    fun showEvent(id: String?) { _openEvent.value = id }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try { block() } catch (e: Exception) { _message.value = CameraRepository.errorText(e) }
            finally { _busy.value = false }
        }
    }

    // ── Global settings ─────────────────────────────────────────────────────────
    fun saveGlobal(detection: Detection, response: String) = launchBusy {
        val res = repo.saveSettings(mapOf("detection" to detection, "response" to response))
        _overview.value = _overview.value?.copy(
            detection = res.detection ?: detection,
            response = res.response ?: response,
            detectionActive = res.detectionActive
        )
        _message.value = "ההגדרות נשמרו"
    }

    fun stopSiren() = launchBusy {
        repo.stopSiren()
        _overview.value = _overview.value?.copy(sirenOn = false)
        _message.value = "הסירנה הופסקה"
    }

    // ── Cameras ─────────────────────────────────────────────────────────────────
    /** id == null → new camera. onDone(true) closes the editor. */
    fun saveCamera(id: String?, body: Map<String, Any?>, onDone: (Boolean) -> Unit) = launchBusy {
        try {
            val res = if (id == null) repo.addCamera(body) else repo.updateCamera(id, body)
            _message.value = res.frigateError?.let { "נשמר, אבל Frigate החזיר שגיאה: $it" }
                ?: if (id == null) "המצלמה נוספה. Frigate מופעל מחדש (כדקה)" else "נשמר"
            refreshOnce()
            onDone(true)
        } catch (e: Exception) {
            _message.value = CameraRepository.errorText(e)
            onDone(false)
        }
    }

    /** Display-only settings (rotation / tilt) — saved without restarting Frigate. */
    fun saveView(cam: CameraInfo, rotation: Int, tilt: Double) = launchBusy {
        repo.updateCamera(cam.id, mapOf("rotation" to rotation, "tilt" to tilt))
        refreshOnce()
    }

    fun deleteCamera(id: String, onDone: () -> Unit) = launchBusy {
        repo.deleteCamera(id)
        _message.value = "המצלמה הוסרה"
        refreshOnce()
        onDone()
    }

    fun scan() {
        viewModelScope.launch {
            _scanning.value = true
            _scan.value = null
            try { _scan.value = repo.scan().devices }
            catch (e: Exception) { _message.value = CameraRepository.errorText(e); _scan.value = emptyList() }
            finally { _scanning.value = false }
        }
    }

    fun clearScan() { _scan.value = null }

    // ── Recordings ──────────────────────────────────────────────────────────────
    fun loadEvents(camera: String?, more: Boolean = false) {
        if (_eventsLoading.value) return
        viewModelScope.launch {
            _eventsLoading.value = true
            try {
                if (!more || camera != eventsCamera) { _events.value = emptyList(); _eventsEnd.value = false }
                eventsCamera = camera
                val before = if (more) _events.value.lastOrNull()?.startTime else null
                val page = repo.events(camera, before).events
                _events.value = _events.value + page.filter { e -> _events.value.none { it.id == e.id } }
                _eventsEnd.value = page.size < 50
            } catch (e: Exception) {
                _message.value = CameraRepository.errorText(e)
            } finally {
                _eventsLoading.value = false
            }
        }
    }

    // ── Authorised faces ────────────────────────────────────────────────────────
    fun loadFaces() {
        viewModelScope.launch {
            try { _faces.value = repo.faces().faces } catch (_: Exception) { _faces.value = emptyList() }
        }
    }

    fun addFace(name: String, bytes: ByteArray, mime: String) = launchBusy {
        _faces.value = repo.addFace(name, bytes, mime).faces
        _message.value = "התמונה נוספה ל-$name"
        refreshOnce()
    }

    fun deleteFace(name: String) = launchBusy {
        _faces.value = repo.deleteFace(name).faces
        _message.value = "$name הוסר"
        refreshOnce()
    }

    // ── Addresses ───────────────────────────────────────────────────────────────
    fun saveAddresses(lan: String, tailscale: String) {
        repo.lanUrl = lan
        repo.tailscaleUrl = tailscale
        viewModelScope.launch {
            repo.baseUrl(force = true)
            _route.value = repo.route
            refreshOnce()
            _message.value = "החיבור: ${repo.route?.label ?: "-"}"
        }
    }

    override fun onCleared() { stopPolling() }
}
