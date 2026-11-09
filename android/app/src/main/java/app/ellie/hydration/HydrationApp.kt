package app.ellie.hydration

import android.app.Application
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.notifications.Notifier
import app.ellie.hydration.push.PushSetup
import app.ellie.hydration.schedule.Scheduler

class HydrationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        PushSetup.initIfConfigured(this)
        if (Prefs(this).onboarded) Scheduler.ensureScheduled(this)
    }
}
