package com.alarmsinai.data.model

// Mirrors the JSON of server.js /cameras/* (cameras.js).

data class CamerasOverview(
    val ok: Boolean = false,
    val frigateOk: Boolean = false,
    val frigateVersion: String? = null,
    val faceSupported: Boolean = false,
    val cameras: List<CameraInfo> = emptyList(),
    val detection: Detection = Detection(),
    val response: String = "push",          // push | siren | both
    val detectionActive: Boolean = false,
    val sirenOn: Boolean = false,
    val authorizedCount: Int = 0
)

data class CameraInfo(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val username: String = "admin",
    val rtspMain: String? = null,
    val rtspSub: String? = null,
    val rotation: Int = 0,                  // 0 / 90 / 180 / 270
    val tilt: Double = 0.0,                 // -45..45, fine correction on top of rotation
    val faceRecognition: Boolean = false,
    val alertType: String = "person",       // stranger | person | none
    val analysis: String = "smart",         // smart | motion
    val retentionDays: Int = 14,
    val zone: CameraZone? = null,
    val hasPassword: Boolean = false
)

/** Points are relative (0..1) to the camera's native, un-rotated image. */
data class CameraZone(
    val type: String,                       // polygon | line
    val points: List<List<Double>>
)

data class Detection(
    val manual: Boolean = false,
    val armLinked: Boolean = true,
    val schedule: Schedule = Schedule()
)

data class Schedule(
    val enabled: Boolean = false,
    val ranges: List<ScheduleRange> = emptyList()
)

/** days: 0 = Sunday .. 6 = Saturday; start/end "HH:MM" (end < start runs overnight). */
data class ScheduleRange(
    val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6),
    val start: String = "22:00",
    val end: String = "06:00"
)

data class CameraSaveResponse(
    val ok: Boolean = false,
    val frigateError: String? = null,
    val camera: CameraInfo? = null,
    val error: String? = null
)

data class CameraSettingsResponse(
    val ok: Boolean = false,
    val detection: Detection? = null,
    val response: String? = null,
    val detectionActive: Boolean = false,
    val error: String? = null
)

data class ScanResponse(val ok: Boolean = false, val devices: List<ScanDevice> = emptyList())

data class ScanDevice(
    val ip: String = "",
    val onvif: Boolean = false,
    val rtsp: Boolean = false,
    val name: String? = null,
    val addedAs: String? = null
)

data class FacesResponse(val ok: Boolean = false, val faces: List<Face> = emptyList(), val error: String? = null)

data class Face(val name: String = "", val images: Int = 0)

data class EventsResponse(val ok: Boolean = false, val events: List<CameraEvent> = emptyList())

data class CameraEvent(
    val id: String = "",
    val cameraId: String = "",
    val cameraName: String = "",
    val label: String = "",
    val subLabel: String? = null,
    val zones: List<String> = emptyList(),
    val startTime: Double = 0.0,            // unix seconds
    val endTime: Double? = null,
    val hasClip: Boolean = false,
    val hasSnapshot: Boolean = false
)

val LABEL_NAMES = mapOf("person" to "אדם", "car" to "רכב", "dog" to "כלב", "cat" to "חתול")
