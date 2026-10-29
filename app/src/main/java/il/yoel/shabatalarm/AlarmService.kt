// Written: 2026-10-29 21:40, updated 2026-10-29 23:40 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service that rings the alarm and shows the full-screen ring screen */
class AlarmService : Service() {

    private lateinit var sound: AlarmSound
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStop = Runnable { finishRinging() }

    override fun onCreate() {
        super.onCreate()
        sound = AlarmSound(this)
        createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(
                intent.getIntExtra(AlarmReceiver.EXTRA_ID, -1),
                intent.getBooleanExtra(AlarmReceiver.EXTRA_SNOOZE, false)
            )
            ACTION_STOP -> {
                if (current?.shabbat != true) finishRinging()
            }
            ACTION_SNOOZE -> {
                val a = current
                if (a != null && a.canSnooze()) {
                    val saved = AlarmStore.get(this, a.id) ?: a
                    saved.snoozeCount = a.snoozeCount + 1
                    saved.snoozeAt = System.currentTimeMillis() + saved.snoozeMinutes * 60_000L
                    AlarmStore.save(this, saved)
                    AlarmScheduler.schedule(this, saved)
                    finishRinging()
                }
            }
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(id: Int, isSnooze: Boolean) {
        val alarm = AlarmStore.get(this, id)
        if (alarm == null) {
            if (current == null) stopSelf()
            return
        }

        // Update state + schedule the next occurrence
        if (isSnooze) {
            alarm.snoozeAt = 0L
        } else {
            alarm.snoozeCount = 0 // a fresh ring cycle
            if (alarm.days == 0) alarm.enabled = false // one-time alarm
        }
        AlarmStore.save(this, alarm)
        AlarmScheduler.schedule(this, alarm)

        // If something is already ringing, replace it
        handler.removeCallbacks(autoStop)
        sound.stop()

        current = alarm
        ringEndsAt = System.currentTimeMillis() + alarm.ringSeconds * 1000L

        val notif = buildNotification(alarm)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        val pm = getSystemService(PowerManager::class.java)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShabatAlarm:ring").apply {
            acquire(alarm.ringSeconds * 1000L + 10_000L)
        }

        sound.start(alarm)
        handler.postDelayed(autoStop, alarm.ringSeconds * 1000L)
    }

    private fun finishRinging() {
        handler.removeCallbacks(autoStop)
        sound.stop()
        current = null
        ringEndsAt = 0L
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        sound.stop()
        current = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(alarm: Alarm): Notification {
        val ringIntent = Intent(this, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullScreen = PendingIntent.getActivity(
            this, 1, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (alarm.label.isNotBlank()) alarm.label else "שעון מעורר"
        val text = if (alarm.shabbat) "מצב שבת — ייפסק לבד בעוד ${alarm.ringSeconds} שניות"
        else "${alarm.timeText()} — מצלצל"

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)

        if (!alarm.shabbat) {
            if (alarm.canSnooze()) {
                b.addAction(0, "נודניק (${alarm.snoozeMinutes} ד')${alarm.snoozesLeftText()}", servicePending(ACTION_SNOOZE, 2))
            }
            b.addAction(0, "כיבוי", servicePending(ACTION_STOP, 3))
        }
        return b.build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START = "il.yoel.shabatalarm.START"
        const val ACTION_STOP = "il.yoel.shabatalarm.STOP"
        const val ACTION_SNOOZE = "il.yoel.shabatalarm.SNOOZE"
        const val ACTION_FINISHED = "il.yoel.shabatalarm.FINISHED"
        const val CHANNEL_ID = "alarm_ring"
        private const val NOTIF_ID = 42

        /** The alarm that is ringing right now (null if none) */
        @Volatile var current: Alarm? = null
        @Volatile var ringEndsAt: Long = 0L

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "צלצול שעון מעורר", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the service plays the sound itself
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
        }
    }
}
