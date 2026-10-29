// Written: 2026-10-29 21:50, updated 2026-10-29 23:58 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.Manifest
import androidx.core.content.FileProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var permBanner: View
    private lateinit var permText: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AlarmService.createChannel(this)

        list = findViewById(R.id.alarmList)
        empty = findViewById(R.id.emptyText)
        permBanner = findViewById(R.id.permBanner)
        permText = findViewById(R.id.permText)

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, EditAlarmActivity::class.java))
        }
        findViewById<Button>(R.id.permFix).setOnClickListener { fixNextPermission() }
        Updater.scheduleBackgroundCheck(this)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) forceUpdateDialog = true

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) forceUpdateDialog = true
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
        AlarmScheduler.scheduleAll(this)
        render()
        refreshPermissions()
    }

    // ---------- list ----------

    private fun render() {
        list.removeAllViews()
        val alarms = AlarmStore.all(this)
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (a in alarms) {
            val v = inflater.inflate(R.layout.item_alarm, list, false)
            v.findViewById<TextView>(R.id.itemTime).text = a.timeText()
            v.findViewById<TextView>(R.id.itemDays).text = a.daysText()

            val tags = mutableListOf<String>()
            if (a.label.isNotBlank()) tags.add(a.label)
            if (a.shabbat) tags.add("🕯️ מצב שבת")
            tags.add("${a.ringSeconds} שנ'")
            when (a.mode) {
                RingMode.VIBRATE_ONLY -> tags.add("רטט בלבד")
                RingMode.SOUND_VIBRATE -> tags.add("צליל+רטט")
                else -> tags.add("צליל")
            }
            if (!a.shabbat) tags.add(
                if (!a.snoozeEnabled) "בלי נודניק"
                else "נודניק ${a.snoozeMinutes} ד' × " + (if (a.snoozeMax == 0) "∞" else a.snoozeMax.toString())
            )
            if (a.snoozeAt > System.currentTimeMillis()) tags.add("⏸ בנודניק")
            v.findViewById<TextView>(R.id.itemInfo).text = tags.joinToString(" · ")

            val sw = v.findViewById<MaterialSwitch>(R.id.itemSwitch)
            sw.isChecked = a.enabled
            v.alpha = if (a.enabled) 1f else 0.55f
            sw.setOnCheckedChangeListener { _, on ->
                a.enabled = on
                if (!on) a.snoozeAt = 0L
                AlarmStore.save(this, a)
                AlarmScheduler.schedule(this, a)
                v.alpha = if (on) 1f else 0.55f
                if (on) toastNext(a)
            }
            v.setOnClickListener {
                startActivity(Intent(this, EditAlarmActivity::class.java).putExtra("id", a.id))
            }
            v.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("למחוק את השעון של ${a.timeText()}?")
                    .setPositiveButton("מחיקה") { _, _ ->
                        AlarmScheduler.cancel(this, a.id)
                        AlarmStore.delete(this, a.id)
                        render()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
                true
            }
            list.addView(v)
        }
    }

    private fun toastNext(a: Alarm) {
        val mins = (a.nextTrigger() - System.currentTimeMillis()) / 60000
        Toast.makeText(this, "יצלצל בעוד ${mins / 60} שעות ו-${mins % 60} דקות", Toast.LENGTH_SHORT).show()
    }

    // ---------- permissions ----------

    private fun missingPermissions(): List<String> {
        val m = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) m.add("notif")
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) m.add("fullscreen")
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            m.add("battery")
        return m
    }

    private fun refreshPermissions() {
        val m = missingPermissions()
        if (m.isEmpty()) { permBanner.visibility = View.GONE; return }
        permBanner.visibility = View.VISIBLE
        permText.text = when (m.first()) {
            "notif" -> "צריך לאשר התראות — בלי זה מסך הצלצול לא יופיע."
            "fullscreen" -> "צריך לאשר 'התראות במסך מלא' — כדי שמסך הצלצול יופיע גם כשהטלפון נעול."
            else -> "מומלץ לבטל את חיסכון הסוללה לאפליקציה — כדי שסמסונג לא תרדים אותה."
        }
    }

    private fun fixNextPermission() {
        when (missingPermissions().firstOrNull()) {
            "notif" -> {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
            "battery" -> {
                try {
                    @Suppress("BatteryLife")
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val hasUpdate = Updater.pendingUpdate(this) != null
        menu.add(0, 5, 0, if (hasUpdate) "עדכון זמין" else "בדיקת עדכונים")
            .setIcon(if (hasUpdate) R.drawable.ic_update_dot else R.drawable.ic_update)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 1, 0, "שיתוף האפליקציה").setIcon(R.drawable.ic_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 4, 1, "הגדרות ברירת מחדל")
        menu.add(0, 2, 2, "בדיקת צלצול (10 שניות)")
        menu.add(0, 3, 3, "הגדרות 'נא לא להפריע'")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> shareApp()
            5 -> manualUpdateCheck()
            2 -> testRing()
            4 -> startActivity(
                Intent(this, EditAlarmActivity::class.java).putExtra(EditAlarmActivity.EXTRA_DEFAULTS, true)
            )
            3 -> AlertDialog.Builder(this)
                .setTitle("נא לא להפריע")
                .setMessage(
                    "בסמסונג, אם 'נא לא להפריע' פעיל, ודא שבהגדרות שלו מסומן " +
                        "'שעונים מעוררים' תחת החריגים — אחרת גם השעון הזה עלול להיחסם.\n\n" +
                        "מצב שקט ורטט רגילים לא משפיעים — השעון מצלצל בכל מקרה."
                )
                .setPositiveButton("פתח הגדרות") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .setNegativeButton("סגור", null)
                .show()
        }
        return true
    }

    private fun shareApp() {
        val text = "⏰ שעון מעורר לשבת\n" +
            "שעון מעורר לאנדרואיד שמצלצל גם על שקט וגם במסך כבוי: " +
            "קביעת אורך הצלצול בשניות, ימים חוזרים, נודניק, רטט, עוצמה עולה בהדרגה " +
            "ומצב שבת — צלצול שאי אפשר לכבות והוא נפסק מעצמו.\n\n" +
            "להורדה (בהתקנה יש לאשר 'התקנה ממקורות לא ידועים'):\n" + APK_URL
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "שיתוף האפליקציה"))
    }

    private fun testRing() {
        // Test with the default settings, shortened to 10 seconds
        val test = AlarmDefaults.get(this).copy(
            id = TEST_ID, label = "בדיקת צלצול", ringSeconds = 10, days = 0,
            enabled = false, shabbat = false, gradual = false
        )
        AlarmStore.save(this, test)
        startService(
            Intent(this, AlarmService::class.java)
                .setAction(AlarmService.ACTION_START)
                .putExtra(AlarmReceiver.EXTRA_ID, TEST_ID)
        )
        // Remove the temporary test alarm from the list right away
        list.postDelayed({ AlarmStore.delete(this, TEST_ID); render() }, 1500)
    }

    // ---------- updates ----------

    private var forceUpdateDialog = false

    /** Quietly checks on every open; pops a dialog once per app session if a newer build exists */
    private fun checkForUpdate() {
        showUpdateIfPending()
        thread {
            val info = Updater.fetch() ?: return@thread
            Updater.remember(this, info)
            runOnUiThread { if (!isFinishing) showUpdateIfPending() }
        }
    }

    private fun showUpdateIfPending() {
        invalidateOptionsMenu() // red dot on / off
        val info = Updater.pendingUpdate(this)
        if (info == null) { Updater.clearNotification(this); return }
        if (updateDialogShown && !forceUpdateDialog) return
        updateDialogShown = true
        forceUpdateDialog = false
        showUpdateDialog(info)
    }

    private fun manualUpdateCheck() {
        Updater.pendingUpdate(this)?.let { showUpdateDialog(it); return }
        Toast.makeText(this, "בודק עדכונים…", Toast.LENGTH_SHORT).show()
        thread {
            val info = Updater.fetch()
            runOnUiThread {
                if (info == null) {
                    Toast.makeText(this, "אין חיבור — נסה שוב מאוחר יותר", Toast.LENGTH_LONG).show()
                } else {
                    Updater.remember(this, info)
                    invalidateOptionsMenu()
                    if (info.versionCode > Updater.currentVersionCode(this)) showUpdateDialog(info)
                    else Toast.makeText(this, "יש לך את הגרסה האחרונה (${Updater.currentVersionName(this)})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.ifBlank { "שיפורים ותיקונים." }
        AlertDialog.Builder(this)
            .setTitle("🔴 עדכון זמין — גרסה ${info.versionName}")
            .setMessage("הגרסה שלך: ${Updater.currentVersionName(this)}\n\nמה חדש:\n$notes\n\nהשעונים וההגדרות שלך יישמרו.")
            .setPositiveButton("עדכון עכשיו") { _, _ -> startUpdate() }
            .setNegativeButton("לא עכשיו", null)
            .show()
    }

    private fun startUpdate() {
        // Android requires a one-time approval to install updates from this app
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "אשר 'התרה ממקור זה', חזור, ולחץ שוב על עדכון", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        val bar = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(this).apply {
            setPadding(60, 40, 60, 10)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("מוריד עדכון…")
            .setView(box)
            .setCancelable(false)
            .show()
        thread {
            try {
                val file = Updater.download(this) { pct ->
                    runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
                }
                runOnUiThread {
                    dlg.dismiss()
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "ההורדה נכשלה — בדוק חיבור ונסה שוב", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update"
        private var updateDialogShown = false
        const val APK_URL =
            "https://github.com/yoeldru/shabat-alarm-clock/releases/latest/download/shabat-alarm-clock.apk"
        private const val TEST_ID = 999999
    }
}
