// Written: 2026-10-29 21:55 (Asia/Jerusalem)
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
import android.widget.TimePicker
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

class EditAlarmActivity : AppCompatActivity() {

    private lateinit var alarm: Alarm
    private var isNew = true

    private lateinit var timePicker: TimePicker
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
                alarm.soundUri = null
                alarm.soundName = "צליל ברירת מחדל"
            } else {
                alarm.soundUri = uri.toString()
                alarm.soundName = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "צליל"
            }
            soundName.text = "🔔 " + alarm.soundName
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            alarm.soundUri = uri.toString()
            alarm.soundName = fileName(uri)
            soundName.text = "🎵 " + alarm.soundName
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        val id = intent.getIntExtra("id", -1)
        val existing = if (id >= 0) AlarmStore.get(this, id) else null
        isNew = existing == null
        alarm = existing ?: Alarm(id = AlarmStore.newId(this))
        title = if (isNew) "שעון חדש" else "עריכת שעון"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindViews()
        fillFromAlarm()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun bindViews() {
        timePicker = findViewById(R.id.timePicker)
        timePicker.setIs24HourView(true)
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
        btnPreview = findViewById(R.id.btnPreview)

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
        btnPreview.setOnClickListener { if (previewing) endPreview() else startPreview() }

        modeGroup.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        gradualSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        shabbatSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        snoozeSwitch.setOnCheckedChangeListener { _, _ -> updateEnabledState() }
        volumeSlider.addOnChangeListener { _, v, _ -> volumeTitle.text = "עוצמה: ${v.toInt()}%" }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        val del = findViewById<Button>(R.id.btnDelete)
        del.visibility = if (isNew) View.GONE else View.VISIBLE
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

    private fun setDays(mask: Int) {
        chips.forEachIndexed { i, c -> c.isChecked = mask and (1 shl i) != 0 }
    }

    private fun fillFromAlarm() {
        timePicker.hour = alarm.hour
        timePicker.minute = alarm.minute
        labelInput.setText(alarm.label)
        setDays(alarm.days)
        secondsInput.setText(alarm.ringSeconds.toString())
        soundName.text = (if (alarm.soundUri?.startsWith("content://media") == false) "🎵 " else "🔔 ") + alarm.soundName
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
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val soundOn = modeGroup.checkedRadioButtonId != R.id.modeVibrate
        volumeSlider.isEnabled = soundOn
        gradualSwitch.isEnabled = soundOn
        gradualLayout.visibility = if (soundOn && gradualSwitch.isChecked) View.VISIBLE else View.GONE
        val shabbat = shabbatSwitch.isChecked
        snoozeSwitch.isEnabled = !shabbat
        snoozeLayout.visibility = if (!shabbat && snoozeSwitch.isChecked) View.VISIBLE else View.GONE
    }

    private fun readInt(e: TextInputEditText, def: Int, min: Int, max: Int): Int =
        (e.text?.toString()?.trim()?.toIntOrNull() ?: def).coerceIn(min, max)

    /** Copies UI values into [alarm] */
    private fun collect() {
        alarm.hour = timePicker.hour
        alarm.minute = timePicker.minute
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
    }

    private fun save() {
        endPreview()
        collect()
        alarm.enabled = true
        alarm.snoozeAt = 0L
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
}
