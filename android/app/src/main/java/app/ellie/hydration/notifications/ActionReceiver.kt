package app.ellie.hydration.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.ellie.hydration.data.AppDatabase
import app.ellie.hydration.data.IntakeEntry
import app.ellie.hydration.data.MessageRepo
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.logic.Hydration
import app.ellie.hydration.schedule.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Handles notification actions. Not exported: only our own PendingIntents reach it. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(ACTION_LOG_QUICK, ACTION_LOG_INPUT, ACTION_DISMISSED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_LOG_QUICK -> log(context, intent.getIntExtra(EXTRA_ML, 0).takeIf { it in 1..Hydration.MAX_SINGLE_ENTRY_ML })
                    ACTION_LOG_INPUT -> {
                        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_AMOUNT)?.toString()
                        log(context, Hydration.parseAmountMl(typed))
                    }
                    ACTION_DISMISSED -> onDismissed(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG_QUICK = "app.ellie.hydration.LOG_QUICK"
        const val ACTION_LOG_INPUT = "app.ellie.hydration.LOG_INPUT"
        const val ACTION_DISMISSED = "app.ellie.hydration.DISMISSED"
        const val EXTRA_ML = "ml"

        /** Shared by the notification and the in-app buttons. */
        suspend fun logIntake(context: Context, ml: Int) {
            val prefs = Prefs(context)
            val day = Hydration.dayKey(LocalDate.now())
            val dao = AppDatabase.get(context).intake()
            dao.insert(IntakeEntry(atMillis = System.currentTimeMillis(), dayKey = day, ml = ml))
            prefs.dismissCount = 0
            val total = dao.totalFor(day)
            val target = prefs.targetMl
            val repo = MessageRepo(context)
            val goalMet = total >= target
            var text = repo.pick(if (goalMet) "goal_met" else "kudos", prefs.nickname)
            // Sometimes add a sourced health fact.
            if (!goalMet && Math.random() < 0.35) repo.facts().randomOrNull()?.let { text = "$text\n${it.text}" }
            Notifier.postKudos(context, text, total, target, goalMet)
        }
    }

    private suspend fun log(context: Context, ml: Int?) {
        if (ml == null) {
            val prefs = Prefs(context)
            val total = AppDatabase.get(context).intake().totalFor(Hydration.dayKey(LocalDate.now()))
            Notifier.postReminder(
                context, Hydration.Mood.NEUTRAL,
                "Ellie didn't quite understand 🐘 Try a number like 250, or \"1 glass\".",
                total, prefs.targetMl, silent = true,
            )
            return
        }
        logIntake(context, ml)
    }

    /**
     * He swiped the reminder away without logging. Bring it back quietly so it stays
     * in the shade, but back off if he dismisses repeatedly (no harassment loops),
     * and never during sleep hours.
     */
    private suspend fun onDismissed(context: Context) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (!Hydration.isAwake(LocalTime.now(), prefs.wake, prefs.sleep)) return
        val day = Hydration.dayKey(LocalDate.now())
        val total = AppDatabase.get(context).intake().totalFor(day)
        if (total >= prefs.targetMl) return

        val recent = now - prefs.lastDismissAt < 5 * 60_000L
        prefs.dismissCount = if (recent) prefs.dismissCount + 1 else 1
        prefs.lastDismissAt = now

        if (prefs.dismissCount >= 3) {
            // He's busy: re-post once in 15 minutes instead of immediately.
            Scheduler.repostLater(context, delayMinutes = 15)
        } else {
            val expected = Hydration.expectedByNow(prefs.targetMl, LocalTime.now(), prefs.wake, prefs.sleep)
            val mood = Hydration.mood(total, expected).let { if (it == Hydration.Mood.HAPPY) Hydration.Mood.NEUTRAL else it }
            val text = MessageRepo(context).pick(if (mood == Hydration.Mood.THIRSTY) "behind" else "reminder", prefs.nickname)
            Notifier.postReminder(context, mood, text, total, prefs.targetMl, silent = true)
        }
    }
}
