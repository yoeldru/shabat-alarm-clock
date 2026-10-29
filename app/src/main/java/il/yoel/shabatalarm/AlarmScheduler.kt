// Written: 2026-10-29 21:35 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Uses AlarmManager.setAlarmClock — the only API Android guarantees to fire on time,
 * even in Doze / battery saver. It also shows the alarm icon in the status bar.
 */
object AlarmScheduler {
    private const val SNOOZE_OFFSET = 100000

    private fun operation(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ID, id)
            putExtra(AlarmReceiver.EXTRA_SNOOZE, snooze)
        }
        val code = if (snooze) id + SNOOZE_OFFSET else id
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun set(ctx: Context, at: Long, op: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(ctx)), op)
    }

    /** Schedule (or cancel) one alarm according to its current state */
    fun schedule(ctx: Context, alarm: Alarm) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val main = operation(ctx, alarm.id, false)
        val snooze = operation(ctx, alarm.id, true)
        am.cancel(main)
        am.cancel(snooze)
        if (alarm.enabled) set(ctx, alarm.nextTrigger(), main)
        if (alarm.snoozeAt > System.currentTimeMillis()) set(ctx, alarm.snoozeAt, snooze)
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(operation(ctx, id, false))
        am.cancel(operation(ctx, id, true))
    }

    fun scheduleAll(ctx: Context) {
        AlarmStore.all(ctx).forEach { schedule(ctx, it) }
    }
}
