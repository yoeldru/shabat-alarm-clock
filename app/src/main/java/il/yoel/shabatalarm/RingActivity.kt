// Written: 2026-10-29 21:45, updated 2026-10-29 23:40 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen ringing screen, shown above the lock screen */
class RingActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var countdown: TextView

    private val finishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (AlarmService.current == null) { finish(); return }
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val left = ((AlarmService.ringEndsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdown.text = "ייפסק לבד בעוד $left שניות"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_ring)

        val alarm = AlarmService.current
        if (alarm == null) { finish(); return }

        clock = findViewById(R.id.ringClock)
        countdown = findViewById(R.id.ringCountdown)
        findViewById<TextView>(R.id.ringLabel).text =
            if (alarm.label.isNotBlank()) alarm.label else "שעון מעורר"

        val snooze = findViewById<Button>(R.id.btnSnooze)
        val stop = findViewById<Button>(R.id.btnStop)
        val shabbatNote = findViewById<TextView>(R.id.shabbatNote)

        // Updated 2026-11-14 21:15: "stop" depends on the per-alarm allowDismiss option
        if (alarm.canSnooze()) {
            snooze.text = "נודניק (${alarm.snoozeMinutes} דקות)${alarm.snoozesLeftText()}"
            snooze.setOnClickListener {
                AlarmService.send(this, AlarmService.ACTION_SNOOZE); finish()
            }
        } else {
            snooze.visibility = View.GONE
        }

        if (alarm.canDismiss()) {
            shabbatNote.visibility = View.GONE
            stop.setOnClickListener {
                AlarmService.send(this, AlarmService.ACTION_STOP); finish()
            }
        } else {
            stop.visibility = View.GONE
            shabbatNote.visibility = View.VISIBLE
            shabbatNote.text = if (alarm.shabbat) "מצב שבת\nהצלצול ייפסק מעצמו"
            else "לא ניתן לכבות\nהצלצול ייפסק מעצמו"
            // Back button does nothing when the alarm can't be turned off
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            })
        }

        ContextCompat.registerReceiver(
            this, finishedReceiver, IntentFilter(AlarmService.ACTION_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(finishedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
