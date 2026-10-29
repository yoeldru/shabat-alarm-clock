// Written: 2026-10-29 21:55, updated 2026-10-29 23:45 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/**
 * Edits one alarm, or — when started with EXTRA_DEFAULTS — the global default settings
 * that every new alarm starts from.
 */
class EditAlarmActivity : AppCompatActivity() {

    private lateinit var alarm: Alarm
    private var isNew = true
    private var defaultsMode = false

    private lateinit var timeText: TextView
    private lateinit var labelInput: TextInputEditText
    private lateinit var secondsInput: TextInputEditText
    private lateinit var soundName: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var volumeSlider: Slider
    private lateinit var volumeTitle: TextView
    private lateinit var gradualSwitch: MaterialSwitch
    private lateinit var gradualLayout: TextInputLayout
    private lateinit var gradualInput: TextInputEditText
    private lateinit var shabbatSwitch: MaterialSwitch
    private lateinit var snoozeSwitch: MaterialSwitch
    private lateinit var snoozeLayout: TextInputLayout
    private lateinit var snoozeInput: TextInputEditText
    private lateinit var snoozeMaxLayout: TextInputLayout
    private lateinit var snoozeMaxInput: TextInputEditText
    private lateinit var btnPreview: Button
    private val chips = mutableListOf<Chip>()

    private val preview by lazy { AlarmSound(this) }
    private var previewing = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopPreview = Runnable { endPreview() }

    private val pickRingtone =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri == null) {
                setBeep()
            } else {
                alarm.soundUri = uri.toString()
                alarm.soundName = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "צליל"
                showSoundName()
            }
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            alarm.soundUri = uri.toString()
            alarm.soundName = fileName(uri)
            showSoundName()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        defaultsMode = intent.getBooleanExtra(EXTRA_DEFAULTS, false)
        if (defaultsMode) {
            alarm = AlarmDefaults.get(this)
            isNew = false
            title = "הגדרות ברירת מחדל"
        } else {
            val id = intent.getIntExtra("id", -1)
            val existing = if (id >= 0) AlarmStore.get(this, id) else null
            isNew = existing == null
            alarm = existing ?: AlarmDefaults.newAlarm(this)
            title = if (isNew) "שעון חדש" else "עריכת שעון"
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindViews()
        fillFromAlarm()

        // Like Google Clock: a new alarm opens straight into the dial
        if (isNew && savedInstanceState == null) timeText.post { openTimePicker() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun bindViews() {
        timeText = findViewById(R.id.timeText)
        labelInput = findViewById(R.id.labelInput)
        secondsInput = findViewById(R.id.secondsInput)
        soundName = findViewById(R.id.soundName)
        modeGroup = findViewById(R.id.modeGroup)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeTitle = findViewById(R.id.volumeTitle)
        gradualSwitch = findViewById(R.id.gradualSwitch)
        gradualLayout = findViewById(R.id.gradualLayout)
        gradualInput = findViewById(R.id.gradualInput)
        shabbatSwitch = findViewById(R.id.shabbatSwitch)
        snoozeSwitch = findViewById(R.id.snoozeSwitch)
        snoozeLayout = findViewById(R.id.snoozeLayout)
        snoozeInput = findViewById(R.id.snoozeInput)
        snoozeMaxLayout = findViewById(R.id.snoozeMaxLayout)
        snoozeMaxInput = findViewById(R.id.snoozeMaxInput)
        btnPreview = findViewById(R.id.btnPreview)

        findViewById<View>(R.id.alarmOnlySection).visibility = if (defaultsMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.defaultsNote).visibility = if (defaultsMode) View.VISIBLE else View.GONE

        timeText.setOnClickListener { openTimePicker() }

        val group = findViewById<ChipGroup>(R.id.daysGroup)
        listOf("א'", "ב'", "ג'", "ד'", "ה'", "ו'", "ש'").forEach { name ->
            val c = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = name
                isCheckable = true
                isCheckedIconVisible = false
                textSize = 16f
            }
            chips.add(c)
            group.addView(c)
        }

        findViewById<Button>(R.id.btnWeekdays).setOnClickListener { setDays(0b0011111) }
        findViewById<Button>(R.id.btnEveryday).setOnClickListener { setDays(0b1111111) }
        findViewById<Button>(R.id.btnShabbatDays).setOnClickListener { setDays(0b1000000) }

        findViewById<Button>(R.id.btnRingtone).setOnClickListener {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "בחירת צליל")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                alarm.soundUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
            }
            pickRingtone.launch(i)
        }
        findViewById<Button>(R.id.btnFile).setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.btnBeep).setOnClickListener { setBeep() }
        btnPreview.setOnClickListener { if (previewing) endPreview() else startPreview() }

        modeGroup.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        gradualSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        shabbatSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        snoozeSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        volumeSlider.addOnChangeListener { _, v, _ -> volumeTitle.text = "עוצמה: ${v.toInt()}%" }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        val del = findViewById<Button>(R.id.btnDelete)
        del.visibility = if (isNew || defaultsMode) View.GONE else View.VISIBLE
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("למחוק את השעון?")
                .setPositiveButton("מחיקה") { _, _ ->
                    AlarmScheduler.cancel(this, alarm.id)
                    AlarmStore.delete(this, alarm.id)
                    finish()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    /** Google-Clock style dial time picker */
    private fun openTimePicker() {
        if (supportFragmentManager.findFragmentByTag("time") != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(alarm.hour)
            .setMinute(alarm.minute)
            .setTitleText("בחירת שעה")
            .setPositiveButtonText("אישור")
            .setNegativeButtonText("ביטול")
            .build()
        picker.addOnPositiveButtonClickListener {
            alarm.hour = picker.hour
            alarm.minute = picker.minute
            timeText.text = alarm.timeText()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun setBeep() {
        alarm.soundUri = null
        alarm.soundName = Alarm.BEEP_NAME
        showSoundName()
    }

    private fun showSoundName() {
        val icon = when {
            alarm.soundUri == null -> "📢 "
            alarm.soundUri!!.startsWith("content://com.android") ||
                alarm.soundUri!!.contains("document") -> "🎵 "
            else -> "🔔 "
        }
        soundName.text = icon + alarm.soundName
    }

    private fun setDays(mask: Int) {
        chips.forEachIndexed { i, c -> c.isChecked = mask and (1 shl i) != 0 }
    }

    private fun fillFromAlarm() {
        timeText.text = alarm.timeText()
        labelInput.setText(alarm.label)
        setDays(alarm.days)
        secondsInput.setText(alarm.ringSeconds.toString())
        showSoundName()
        modeGroup.check(
            when (alarm.mode) {
                RingMode.SOUND -> R.id.modeSound
                RingMode.VIBRATE_ONLY -> R.id.modeVibrate
                else -> R.id.modeBoth
            }
        )
        volumeSlider.value = (alarm.volume.coerceIn(5, 100) / 5 * 5).toFloat()
        volumeTitle.text = "עוצמה: ${volumeSlider.value.toInt()}%"
        gradualSwitch.isChecked = alarm.gradual
        gradualInput.setText(alarm.gradualSeconds.toString())
        shabbatSwitch.isChecked = alarm.shabbat
        snoozeSwitch.isChecked = alarm.snoozeEnabled
        snoozeInput.setText(alarm.snoozeMinutes.toString())
        snoozeMaxInput.setText(alarm.snoozeMax.toString())
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val soundOn = modeGroup.checkedRadioButtonId != R.id.modeVibrate
        volumeSlider.isEnabled = soundOn
        gradualSwitch.isEnabled = soundOn
        gradualLayout.visibility = if (soundOn && gradualSwitch.isChecked) View.VISIBLE else View.GONE
        val shabbat = shabbatSwitch.isChecked
        snoozeSwitch.isEnabled = !shabbat
        val showSnooze = !shabbat && snoozeSwitch.isChecked
        snoozeLayout.visibility = if (showSnooze) View.VISIBLE else View.GONE
        snoozeMaxLayout.visibility = if (showSnooze) View.VISIBLE else View.GONE
    }

    private fun readInt(e: TextInputEditText, def: Int, min: Int, max: Int): Int =
        (e.text?.toString()?.trim()?.toIntOrNull() ?: def).coerceIn(min, max)

    /** Copies UI values into [alarm] (time is updated directly by the picker) */
    private fun collect() {
        alarm.label = labelInput.text?.toString()?.trim() ?: ""
        var mask = 0
        chips.forEachIndexed { i, c -> if (c.isChecked) mask = mask or (1 shl i) }
        alarm.days = mask
        alarm.ringSeconds = readInt(secondsInput, 60, 1, 3600)
        alarm.mode = when (modeGroup.checkedRadioButtonId) {
            R.id.modeSound -> RingMode.SOUND
            R.id.modeVibrate -> RingMode.VIBRATE_ONLY
            else -> RingMode.SOUND_VIBRATE
        }
        alarm.volume = volumeSlider.value.toInt()
        alarm.gradual = gradualSwitch.isChecked
        alarm.gradualSeconds = readInt(gradualInput, 20, 1, 3600)
        alarm.shabbat = shabbatSwitch.isChecked
        alarm.snoozeEnabled = snoozeSwitch.isChecked && !alarm.shabbat
        alarm.snoozeMinutes = readInt(snoozeInput, 5, 1, 60)
        alarm.snoozeMax = readInt(snoozeMaxInput, 3, 0, 99)
    }

    private fun save() {
        endPreview()
        collect()
        if (defaultsMode) {
            AlarmDefaults.save(this, alarm)
            Toast.makeText(this, "ברירת המחדל נשמרה — תחול על שעונים חדשים", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        alarm.enabled = true
        alarm.snoozeAt = 0L
        alarm.snoozeCount = 0
        AlarmStore.save(this, alarm)
        AlarmScheduler.schedule(this, alarm)
        val mins = (alarm.nextTrigger() - System.currentTimeMillis()) / 60000
        Toast.makeText(this, "יצלצל בעוד ${mins / 60} שעות ו-${mins % 60} דקות", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun startPreview() {
        collect()
        val test = alarm.copy(gradual = false)
        preview.start(test)
        previewing = true
        btnPreview.text = "■ עצירה"
        handler.postDelayed(stopPreview, 6000)
    }

    private fun endPreview() {
        handler.removeCallbacks(stopPreview)
        if (previewing) preview.stop()
        previewing = false
        if (::btnPreview.isInitialized) btnPreview.text = "▶ השמעה לבדיקה"
    }

    override fun onPause() {
        endPreview()
        super.onPause()
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {}
        return "קובץ שמע"
    }

    companion object {
        const val EXTRA_DEFAULTS = "defaults"
    }
}
