package app.ellie.hydration.push

import android.content.Context
import app.ellie.hydration.BuildConfig
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.schedule.Scheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push is optional. Without Firebase config the app still works: reminders are
 * local and the target refreshes periodically. Push only makes WHOOP workouts
 * update the target sooner.
 */
object PushSetup {
    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank()

    fun initIfConfigured(ctx: Context) {
        if (!configured || FirebaseApp.getApps(ctx).isNotEmpty()) return
        val opts = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(ctx, opts)
    }

    /** Called only after he has opted in during onboarding. */
    fun enable(ctx: Context) {
        if (!configured) return
        initIfConfigured(ctx)
        val fm = FirebaseMessaging.getInstance()
        fm.isAutoInitEnabled = true
        fm.token.addOnSuccessListener { token ->
            Prefs(ctx).pendingFcmToken = token
            Scheduler.syncNow(ctx)
        }
    }

    fun disable(ctx: Context) {
        if (!configured || FirebaseApp.getApps(ctx).isEmpty()) return
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        FirebaseMessaging.getInstance().deleteToken()
    }
}

class SyncMessagingService : FirebaseMessagingService() {
    /** Payload is data-only {"type":"sync"}: it carries no text, so nothing from push is ever displayed. */
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sync" && Prefs(this).onboarded) Scheduler.syncNow(this)
    }

    override fun onNewToken(token: String) {
        val prefs = Prefs(this)
        if (!prefs.onboarded) return
        prefs.pendingFcmToken = token
        Scheduler.syncNow(this)
    }
}
