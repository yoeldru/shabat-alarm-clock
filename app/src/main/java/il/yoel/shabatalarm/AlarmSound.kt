// Written: 2026-10-29 21:40, updated 2026-10-29 23:40 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Plays an alarm on the ALARM audio stream (USAGE_ALARM) — that stream is not
 * muted by silent / vibrate mode, exactly like the built-in clock.
 */
class AlarmSound(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var savedVolume = -1
    private var rampRunnable: Runnable? = null

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(alarm: Alarm, withVibration: Boolean = true) {
        stop()
        if (alarm.mode != RingMode.VIBRATE_ONLY) startSound(alarm)
        if (withVibration && alarm.mode != RingMode.SOUND) startVibration()
    }

    private fun startSound(alarm: Alarm) {
        // Set the alarm stream volume to the chosen level (restored on stop)
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = (max * alarm.volume / 100f).toInt().coerceIn(1, max)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (_: Exception) {
            savedVolume = -1
        }

        val candidates = listOfNotNull(
            alarm.soundUri?.let { Uri.parse(it) },
            beepUri(ctx),
            RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI
        )
        for (uri in candidates) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(alarmAttrs)
                mp.setDataSource(ctx, uri)
                mp.isLooping = true
                mp.prepare()
                val startVol = if (alarm.gradual) 0.05f else 1f
                mp.setVolume(startVol, startVol)
                mp.start()
                player = mp
                if (alarm.gradual) startRamp(alarm.gradualSeconds)
                return
            } catch (_: Exception) {
                // try next candidate
            }
        }
    }

    private fun startRamp(seconds: Int) {
        val totalMs = seconds.coerceAtLeast(1) * 1000L
        val stepMs = 250L
        val begin = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val mp = player ?: return
                val p = ((System.currentTimeMillis() - begin).toFloat() / totalMs).coerceIn(0f, 1f)
                val v = 0.05f + 0.95f * p * p // quadratic: sounds more natural
                try { mp.setVolume(v, v) } catch (_: Exception) {}
                if (p < 1f) handler.postDelayed(this, stepMs)
            }
        }
        rampRunnable = r
        handler.post(r)
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, alarmAttrs)
        }
    }

    companion object {
        /** The built-in beep-beep sound bundled with the app */
        fun beepUri(ctx: Context): Uri =
            Uri.parse("android.resource://${ctx.packageName}/${R.raw.beep}")
    }

    fun stop() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) {}
            savedVolume = -1
        }
    }
}
