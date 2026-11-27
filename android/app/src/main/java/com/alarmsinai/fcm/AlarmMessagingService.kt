package com.alarmsinai.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.alarmsinai.MainActivity
import com.alarmsinai.R
import com.alarmsinai.data.AlarmRepository
import com.alarmsinai.data.CameraRepository
import com.alarmsinai.service.AlarmSoundService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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

        createChannels(this)

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

        when (type) {
            "alarm" -> {
                val notif = NotificationCompat.Builder(this, CHANNEL_ALARM)
                    .setSmallIcon(R.drawable.ic_alarm)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(tapIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(fullScreenIntent, true)
                    .setNumber(1)
                    .build()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(1001, notif)
                startForegroundService(Intent(this, AlarmSoundService::class.java))
            }
            "intruder" -> showIntruder(title, body, message.data)
            "disarm" -> startService(Intent(this, AlarmSoundService::class.java).apply {
                action = AlarmSoundService.ACTION_STOP
            })
        }
    }

    /**
     * Camera alert. The push carries no image (nothing leaves the house through
     * Firebase) — the snapshot is fetched from the home server here.
     */
    private fun showIntruder(title: String, body: String, data: Map<String, String>) {
        val eventId = data["eventId"]
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_CAMERAS)
            putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
        }
        val tap = PendingIntent.getActivity(
            this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            this, 3, Intent(this, SirenStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_INTRUDER)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "עצור סירנה", stop)

        if (eventId != null) {
            val picture = withTimeoutOrNullBlocking(6_000) {
                val repo = CameraRepository(applicationContext)
                val bytes = repo.bytes(repo.eventSnapshotUrl(eventId))
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (picture != null) builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?))
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_INTRUDER, builder.build())
    }

    // onMessageReceived runs on a background thread and may block for a few seconds.
    private fun <T> withTimeoutOrNullBlocking(ms: Long, block: suspend () -> T?): T? =
        try { runBlocking { withTimeoutOrNull(ms) { block() } } } catch (_: Exception) { null }

    companion object {
        const val CHANNEL_ALARM  = "alarm_channel"
        const val CHANNEL_STATUS = "status_channel"
        const val CHANNEL_INTRUDER = "intruder_channel"
        const val NOTIF_INTRUDER = 1002

        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
                val alarmSound = Uri.parse(
                    "android.resource://${context.packageName}/raw/alarm_sound"
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
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }

            if (nm.getNotificationChannel(CHANNEL_INTRUDER) == null) {
                val ch = NotificationChannel(
                    CHANNEL_INTRUDER, "מצלמות", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "זיהוי אדם במצלמות"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
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
    }
}
