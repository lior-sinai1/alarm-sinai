package com.alarmsinai.fcm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.alarmsinai.data.CameraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "עצור סירנה" button on the camera notification → POST /cameras/siren/stop (clears M20). */
class SirenStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try { CameraRepository(app).stopSiren(); true } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (ok) "הסירנה הופסקה" else "לא הצלחתי לעצור את הסירנה", Toast.LENGTH_LONG).show()
            }
            if (ok) (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(AlarmMessagingService.NOTIF_INTRUDER)
            pending.finish()
        }
    }
}
