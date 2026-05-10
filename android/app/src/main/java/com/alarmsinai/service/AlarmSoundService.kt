package com.alarmsinai.service

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alarmsinai.R
import com.alarmsinai.fcm.AlarmMessagingService
import kotlinx.coroutines.*

class AlarmSoundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, AlarmMessagingService.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("אזעקה!")
            .setContentText("פריצה — מערכת האזעקה הופעלה!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)

        scope.launch {
            while (isActive) {
                val hi = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                try {
                    hi.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
                    delay(650)
                } finally { hi.release() }
                val lo = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                try {
                    lo.startTone(ToneGenerator.TONE_CDMA_LOW_L, 600)
                    delay(650)
                } finally { lo.release() }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_STOP = "com.alarmsinai.STOP_ALARM"
        const val NOTIF_ID = 9999
    }
}
