package lol.mony.harmony.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import lol.mony.harmony.MainActivity
import lol.mony.harmony.R

/**
 * Keeps a call alive while the app is not in front, as a phone call is.
 *
 * Android requires a foreground service with a declared type for the microphone, camera and
 * screen capture; without it the system mutes or stops the capture as soon as the app leaves
 * the foreground. The notification is the visible contract with the person: it says a call is
 * running and gives them one tap to return to it.
 *
 * The service is started when a call joins and stopped when it leaves, so it never lingers.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "In a call"
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(label)
            .setContentText("Tap to return to the call")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.call_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.call_channel_description)
            setShowBadge(false)
            enableVibration(false)
        })
    }

    companion object {
        const val CHANNEL_ID = "harmony-call"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
