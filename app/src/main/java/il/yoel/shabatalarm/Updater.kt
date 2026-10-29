// Written: 2026-10-29 23:55 (Asia/Jerusalem)
package il.yoel.shabatalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Long, val versionName: String, val notes: String)

/**
 * Checks GitHub Releases for a newer build (version.json published next to the APK).
 */
object Updater {
    private const val BASE = "https://github.com/yoeldru/shabat-alarm-clock/releases/latest/download/"
    const val VERSION_URL = BASE + "version.json"
    const val APK_URL = BASE + "shabat-alarm-clock.apk"
    private const val PREFS = "updater"
    private const val CHANNEL_ID = "updates"
    private const val NOTIF_ID = 77

    fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Blocking network call — run off the main thread */
    fun fetch(): UpdateInfo? = try {
        val c = URL(VERSION_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        c.useCaches = false
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(text)
        UpdateInfo(o.getLong("versionCode"), o.optString("versionName"), o.optString("notes"))
    } catch (_: Exception) {
        null
    }

    /** Remembers the latest version seen, so the red dot can show without network */
    fun remember(ctx: Context, info: UpdateInfo) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("code", info.versionCode)
            .putString("name", info.versionName)
            .putString("notes", info.notes)
            .apply()
    }

    fun known(ctx: Context): UpdateInfo? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = p.getLong("code", 0L)
        if (code == 0L) return null
        return UpdateInfo(code, p.getString("name", "") ?: "", p.getString("notes", "") ?: "")
    }

    fun pendingUpdate(ctx: Context): UpdateInfo? =
        known(ctx)?.takeIf { it.versionCode > currentVersionCode(ctx) }

    /** Downloads the new APK, reporting progress 0..100. Blocking. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val c = URL(APK_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        c.disconnect()
        return out
    }

    // ---------- background check + notification (gives the red dot on the app icon) ----------

    fun scheduleBackgroundCheck(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notifyUpdate(ctx: Context, info: UpdateInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "עדכוני אפליקציה", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setShowBadge(true) }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UPDATE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("עדכון זמין — גרסה ${info.versionName}")
            .setContentText(info.notes.lineSequence().firstOrNull() ?: "לחץ לעדכון")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes))
            .setNumber(1)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    fun clearNotification(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val info = Updater.fetch() ?: return Result.success()
        val prevKnown = Updater.known(applicationContext)?.versionCode ?: 0L
        Updater.remember(applicationContext, info)
        // Notify once per new version
        if (info.versionCode > Updater.currentVersionCode(applicationContext) && info.versionCode > prevKnown) {
            Updater.notifyUpdate(applicationContext, info)
        }
        return Result.success()
    }
}
