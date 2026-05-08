package com.alarmsinai.data.model

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    val ok: Boolean,
    val connected: Boolean,
    val m175: Int,
    val m19: Int,
    val mw1: Int,
    val sensors: Map<String, Int>
)

data class ArmRequest(@SerializedName("zone") val zone: Int)
data class TokenRequest(@SerializedName("token") val token: String)
data class GenericResponse(val ok: Boolean, val error: String? = null)

data class HistoryEvent(
    val type: String,       // "alarm" | "arm" | "disarm"
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

val SENSOR_NAMES = mapOf(
    "182" to "דלת כניסה",
    "201" to "חלון מטבח",
    "202" to "דלת מטבח",
    "203" to "ויטרינה סלון",
    "204" to "הגנת צופר",
    "205" to "חלון הורים מזרחי ימין",
    "206" to "חלון הורים מזרחי שמאלי",
    "207" to "חלון הורים צפוני חזית",
    "208" to "סלון",
    "209" to "פרגולה",
    "210" to "חלון רחצה הורים",
    "211" to "חדר הורים",
    "212" to "ממ\"ד",
    "213" to "רחבת חדרים קומה א'",
    "214" to "דלת מרפסת קומה א'",
    "215" to "חדר נוף חלון מערבי",
    "216" to "חדר נוף ויטרינה",
    "217" to "חדר נוף חלון מזרחי",
    "218" to "חלון חדר כביסה",
    "219" to "מרפסת קומה א'",
    "222" to "חלון סלון"
)
