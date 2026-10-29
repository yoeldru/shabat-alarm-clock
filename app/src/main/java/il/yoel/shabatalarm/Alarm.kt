// Written: 2026-10-29 21:35, updated 2026-10-29 23:40 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Ring mode */
object RingMode {
    const val SOUND = 0
    const val SOUND_VIBRATE = 1
    const val VIBRATE_ONLY = 2
}

data class Alarm(
    var id: Int,
    var hour: Int = 7,
    var minute: Int = 0,
    var label: String = "",
    var enabled: Boolean = true,
    /** Bit 0 = Sunday ... bit 6 = Saturday. 0 = one-time alarm */
    var days: Int = 0,
    /** How many seconds the alarm rings before stopping by itself */
    var ringSeconds: Int = 60,
    /** null = built-in beep-beep */
    var soundUri: String? = null,
    var soundName: String = BEEP_NAME,
    var mode: Int = RingMode.SOUND_VIBRATE,
    var volume: Int = 80,
    var gradual: Boolean = false,
    var gradualSeconds: Int = 20,
    var snoozeEnabled: Boolean = true,
    var snoozeMinutes: Int = 5,
    /** Max snoozes per ring, 0 = unlimited */
    var snoozeMax: Int = 3,
    /** Snoozes used so far in the current ring cycle */
    var snoozeCount: Int = 0,
    /** Shabbat mode: cannot be stopped, rings for ringSeconds and stops by itself */
    var shabbat: Boolean = false,
    /** Pending snooze time in millis, 0 = none */
    var snoozeAt: Long = 0L
) {
    fun isDayOn(calendarDay: Int) = days and (1 shl (calendarDay - 1)) != 0

    fun canSnooze() = !shabbat && snoozeEnabled && (snoozeMax == 0 || snoozeCount < snoozeMax)

    fun snoozesLeftText() = if (snoozeMax == 0) "" else " · נשארו ${snoozeMax - snoozeCount}"

    fun timeText() = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

    fun daysText(): String {
        if (days == 0) return "חד-פעמי"
        if (days == 0b1111111) return "כל יום"
        if (days == 0b0011111) return "א'-ה'"
        if (days == 0b0111111) return "א'-ו'"
        val names = listOf("א'", "ב'", "ג'", "ד'", "ה'", "ו'", "ש'")
        return names.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
    }

    /** Next time (millis) this alarm should ring, after [from] */
    fun nextTrigger(from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (c.timeInMillis > from && (days == 0 || isDayOn(c.get(Calendar.DAY_OF_WEEK)))) {
                return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute); put("label", label)
        put("enabled", enabled); put("days", days); put("ringSeconds", ringSeconds)
        put("soundUri", soundUri ?: ""); put("soundName", soundName); put("mode", mode)
        put("volume", volume); put("gradual", gradual); put("gradualSeconds", gradualSeconds)
        put("snoozeEnabled", snoozeEnabled); put("snoozeMinutes", snoozeMinutes)
        put("snoozeMax", snoozeMax); put("snoozeCount", snoozeCount)
        put("shabbat", shabbat); put("snoozeAt", snoozeAt)
    }

    companion object {
        const val BEEP_NAME = "ביפ ביפ (מובנה)"

        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            label = o.optString("label", ""),
            enabled = o.optBoolean("enabled", true),
            days = o.optInt("days", 0),
            ringSeconds = o.optInt("ringSeconds", 60),
            soundUri = o.optString("soundUri", "").ifEmpty { null },
            soundName = if (o.optString("soundUri", "").isEmpty()) BEEP_NAME
                        else o.optString("soundName", "צליל"),
            mode = o.optInt("mode", RingMode.SOUND_VIBRATE),
            volume = o.optInt("volume", 80),
            gradual = o.optBoolean("gradual", false),
            gradualSeconds = o.optInt("gradualSeconds", 20),
            snoozeEnabled = o.optBoolean("snoozeEnabled", true),
            snoozeMinutes = o.optInt("snoozeMinutes", 5),
            snoozeMax = o.optInt("snoozeMax", 3),
            snoozeCount = o.optInt("snoozeCount", 0),
            shabbat = o.optBoolean("shabbat", false),
            snoozeAt = o.optLong("snoozeAt", 0L)
        )
    }
}

object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY = "list"

    fun all(ctx: Context): MutableList<Alarm> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Alarm>()
        for (i in 0 until arr.length()) list.add(Alarm.fromJson(arr.getJSONObject(i)))
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return list
    }

    private fun saveAll(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int): Alarm? = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, alarm: Alarm) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == alarm.id }
        if (i >= 0) list[i] = alarm else list.add(alarm)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: Int) {
        saveAll(ctx, all(ctx).filter { it.id != id })
    }

    fun newId(ctx: Context): Int = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1
}

/** Global defaults — every new alarm starts from these settings */
object AlarmDefaults {
    private const val PREFS = "defaults"
    private const val KEY = "alarm"

    fun get(ctx: Context): Alarm {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (raw == null) Alarm(id = 0) else Alarm.fromJson(JSONObject(raw))
    }

    fun save(ctx: Context, a: Alarm) {
        val copy = a.copy(id = 0, label = "", days = 0, enabled = true, snoozeAt = 0L, snoozeCount = 0)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, copy.toJson().toString()).apply()
    }

    /** A brand-new alarm built from the defaults */
    fun newAlarm(ctx: Context): Alarm =
        get(ctx).copy(id = AlarmStore.newId(ctx), enabled = true, snoozeAt = 0L, snoozeCount = 0)
}
