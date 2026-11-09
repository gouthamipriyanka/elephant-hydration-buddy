package app.ellie.hydration.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ellie.hydration.data.AppDatabase
import app.ellie.hydration.data.MessageRepo
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.geo.HomeGeofence
import app.ellie.hydration.logic.Hydration
import app.ellie.hydration.net.Api
import app.ellie.hydration.notifications.Notifier
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "ellie-reminder-check"
    private const val SYNC = "ellie-sync"
    private const val REPOST = "ellie-repost"

    /** Runs every 15 min (WorkManager minimum). Works offline: decisions are local. */
    fun ensureScheduled(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(SYNC, ExistingWorkPolicy.REPLACE, req)
    }

    fun repostLater(ctx: Context, delayMinutes: Long) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(ReminderWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(REPOST, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelAllWork()
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.onboarded) return Result.success()

        val today = Hydration.dayKey(LocalDate.now())
        // New day (or stale target): refresh in the background; meanwhile use the cached target.
        if (prefs.targetDay != today || System.currentTimeMillis() - prefs.targetFetchedAt > 3 * 3_600_000L) {
            Scheduler.syncNow(ctx)
        }

        val now = LocalTime.now()
        val awake = Hydration.isAwake(now, prefs.wake, prefs.sleep)
        val dao = AppDatabase.get(ctx).intake()
        val total = dao.totalFor(today)
        val target = prefs.targetMl
        val expected = Hydration.expectedByNow(target, now, prefs.wake, prefs.sleep)
        val force = inputData.getBoolean(KEY_FORCE, false)

        val due = Hydration.shouldRemind(System.currentTimeMillis(), prefs.lastReminderAt, total, target, expected, awake)
        val forcedAndBehind = force && awake && total < target
        if (due || forcedAndBehind) {
            val mood = Hydration.mood(total, expected).let { if (it == Hydration.Mood.HAPPY) Hydration.Mood.NEUTRAL else it }
            val repo = MessageRepo(ctx)
            val text = repo.pick(if (mood == Hydration.Mood.THIRSTY) "behind" else "reminder", prefs.nickname)
            Notifier.postReminder(ctx, mood, text, total, target)
            prefs.lastReminderAt = System.currentTimeMillis()
        }

        // Data minimisation: keep 90 days of intake history only.
        if (now.hour == 3) dao.purgeBefore(System.currentTimeMillis() - 90L * 86_400_000L)
        return Result.success()
    }

    companion object { const val KEY_FORCE = "force" }
}

/** Fetches today's target + message pool from the backend. Best-effort; never blocks reminders. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val token = prefs.deviceToken ?: return Result.success()
        val api = Api(token)
        return try {
            prefs.pendingFcmToken?.let { api.putFcmToken(it); prefs.pendingFcmToken = null }
            val t = api.today(ZoneId.systemDefault().id, prefs.manualWeightKg.takeIf { it > 0 })
            prefs.targetMl = t.targetMl
            prefs.targetSource = t.source
            prefs.targetDay = Hydration.dayKey(LocalDate.now())
            prefs.targetFetchedAt = System.currentTimeMillis()
            MessageRepo(ctx).cacheFromServer(api.messagesJson())
            Result.success()
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

/** Geofences and periodic work don't survive reboot/update: re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.onboarded) return
        Scheduler.ensureScheduled(context)
        if (prefs.bottleRemindersEnabled) HomeGeofence.registerIfPossible(context)
    }
}
