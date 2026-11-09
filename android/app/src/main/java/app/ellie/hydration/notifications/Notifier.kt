package app.ellie.hydration.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.ellie.hydration.R
import app.ellie.hydration.logic.Hydration.Mood
import app.ellie.hydration.ui.MainActivity

object Notifier {
    const val CH_REMINDER = "reminders"
    const val CH_KUDOS = "kudos"
    const val CH_BOTTLE = "bottle"
    const val ID_WATER = 1001
    const val ID_BOTTLE = 1002
    const val KEY_AMOUNT = "amount_ml"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_KUDOS, ctx.getString(R.string.channel_kudos), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BOTTLE, ctx.getString(R.string.channel_bottle), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun elephant(ctx: Context, @DrawableRes res: Int, size: Int = 512): Bitmap =
        ContextCompat.getDrawable(ctx, res)!!.toBitmap(size, size)

    private fun art(mood: Mood) = when (mood) {
        Mood.THIRSTY -> R.drawable.elephant_thirsty
        Mood.NEUTRAL -> R.drawable.elephant_neutral
        Mood.HAPPY -> R.drawable.elephant_happy
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Explicit-component intent to our own non-exported receiver. */
    private fun actionIntent(ctx: Context, action: String, req: Int, ml: Int = 0, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).setPackage(ctx.packageName)
        if (ml > 0) i.putExtra(ActionReceiver.EXTRA_ML, ml)
        // RemoteInput REQUIRES a mutable PendingIntent so the system can attach the typed text.
        // Risk is contained: explicit component + package, receiver not exported.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, req, i, flags)
    }

    /** Lock-screen version hides the personal text. */
    private fun publicVersion(ctx: Context, channel: String) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(ctx.getString(R.string.notif_public))
            .build()

    /**
     * The reminder. "Ongoing" + re-post-on-dismiss (see ActionReceiver) so it keeps
     * coming back until he logs water. Android 14+ lets users swipe away ongoing
     * notifications, hence the re-post.
     */
    fun postReminder(ctx: Context, mood: Mood, text: String, consumed: Int, target: Int, silent: Boolean = false) {
        if (!canPost(ctx)) return
        val pic = elephant(ctx, art(mood))
        val title = if (mood == Mood.THIRSTY) "Ellie is thirsty 🥺🐘" else "Water time 💧🐘"

        val remoteInput = RemoteInput.Builder(KEY_AMOUNT).setLabel(ctx.getString(R.string.input_hint)).build()
        val logAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_drop, ctx.getString(R.string.action_log),
            actionIntent(ctx, ActionReceiver.ACTION_LOG_INPUT, 10, mutable = true),
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()

        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(pic)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?).setSummaryText(text))
            .setProgress(target, consumed.coerceAtMost(target), false)
            .setSubText("$consumed / $target ml")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CH_REMINDER))
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(actionIntent(ctx, ActionReceiver.ACTION_DISMISSED, 13))
            .addAction(logAction)
            .addAction(R.drawable.ic_stat_drop, ctx.getString(R.string.action_250),
                actionIntent(ctx, ActionReceiver.ACTION_LOG_QUICK, 11, ml = 250))
            .addAction(R.drawable.ic_stat_drop, ctx.getString(R.string.action_500),
                actionIntent(ctx, ActionReceiver.ACTION_LOG_QUICK, 12, ml = 500))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_WATER, n)
    }

    /** Happy elephant. Replaces the reminder (same id) so the nag disappears. */
    fun postKudos(ctx: Context, text: String, consumed: Int, target: Int, goalMet: Boolean) {
        if (!canPost(ctx)) return
        val pic = elephant(ctx, R.drawable.elephant_happy)
        val title = if (goalMet) "Goal reached! 🏆🐘" else "Kudos! 🎉🐘"
        val n = NotificationCompat.Builder(ctx, CH_KUDOS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(pic)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?).setSummaryText(text))
            .setProgress(target, consumed.coerceAtMost(target), false)
            .setSubText("$consumed / $target ml")
            .setAutoCancel(true)
            .setTimeoutAfter(15 * 60_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CH_KUDOS))
            .setContentIntent(openApp(ctx))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_WATER, n)
    }

    fun postBottle(ctx: Context, text: String) {
        if (!canPost(ctx)) return
        val pic = elephant(ctx, R.drawable.elephant_bottle)
        val n = NotificationCompat.Builder(ctx, CH_BOTTLE)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle("Got your water bottle? 🧴🐘")
            .setContentText(text)
            .setLargeIcon(pic)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(pic).bigLargeIcon(null as Bitmap?).setSummaryText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CH_BOTTLE))
            .setContentIntent(openApp(ctx))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_BOTTLE, n)
    }

    fun cancelAll(ctx: Context) = NotificationManagerCompat.from(ctx).cancelAll()
}
