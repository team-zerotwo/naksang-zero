package kr.zerotwo.naksang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kr.zerotwo.naksang.MainActivity
import kr.zerotwo.naksang.NaksangApp
import kr.zerotwo.naksang.R
import kr.zerotwo.naksang.session.Phase

/**
 * Keeps detection alive with the screen off. The notification is silent:
 * this build does not play an alarm.
 */
class FallDetectionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("지켜보는 중"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "foreground start rejected", e)
            stopSelf()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "naksang:detect").apply {
            setReferenceCounted(false)
            acquire()
        }
        (application as NaksangApp).container.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        (application as NaksangApp).container.detachService()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun syncNotification(phase: Phase) {
        val text = when (phase) {
            Phase.COUNTDOWN -> "괜찮으세요? 화면을 확인해 주세요"
            Phase.SENT -> "보호자에게 알렸어요"
            else -> "지켜보는 중"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val screen = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "naksang:countdown",
        )
        screen.acquire(15_000)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_naksang)
        .setContentTitle("낙상제로")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openAppIntent())
        .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.channel_watching),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_watching_desc)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "NaksangService"
        private const val CHANNEL = "naksang_watching"
        private const val NOTIFICATION_ID = 41
    }
}
