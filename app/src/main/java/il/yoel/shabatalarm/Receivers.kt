// Written: 2026-10-29 21:35 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager at alarm time — starts the ringing service */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(EXTRA_ID, intent.getIntExtra(EXTRA_ID, -1))
            putExtra(EXTRA_SNOOZE, intent.getBooleanExtra(EXTRA_SNOOZE, false))
        }
        ContextCompat.startForegroundService(context, svc)
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_SNOOZE = "is_snooze"
    }
}

/** Re-schedules all alarms after reboot, app update, or clock/timezone change */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(context)
    }
}
