package com.alarmsinai.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.alarmsinai.MainActivity
import com.alarmsinai.R
import com.alarmsinai.data.AlarmRepository
import com.alarmsinai.viewmodel.AlarmViewModel
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            try { AlarmRepository(applicationContext).registerToken(token) }
            catch (_: Exception) {}
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: return
        val body  = message.data["body"]  ?: ""
        val type  = message.data["type"]  ?: ""

        ensureChannels()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fullScreenIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = if (type == "alarm") CHANNEL_ALARM else CHANNEL_STATUS
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .apply {
                if (type == "alarm") setFullScreenIntent(fullScreenIntent, true)
            }
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            val alarmSound = Uri.parse(
                "android.resource://${packageName}/raw/alarm_sound"
            )
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val ch = NotificationChannel(
                CHANNEL_ALARM, "אזעקה", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "התראות אזעקה"
                setSound(alarmSound, audioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            nm.createNotificationChannel(ch)
        }

        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            val ch = NotificationChannel(
                CHANNEL_STATUS, "סטטוס מערכת", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "דריכה / נטרול" }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ALARM  = "alarm_channel"
        const val CHANNEL_STATUS = "status_channel"
    }
}
