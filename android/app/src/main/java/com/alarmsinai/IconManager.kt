package com.alarmsinai

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconManager {

    enum class State { IDLE, ARMED, ALARM }

    private val aliases = mapOf(
        State.IDLE  to "com.alarmsinai.MainActivityIdle",
        State.ARMED to "com.alarmsinai.MainActivityArmed",
        State.ALARM to "com.alarmsinai.MainActivityAlarm"
    )

    fun update(context: Context, state: State) {
        val pm = context.packageManager
        aliases.forEach { (s, name) ->
            val enabled = if (s == state)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(
                ComponentName(context, name),
                enabled,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
