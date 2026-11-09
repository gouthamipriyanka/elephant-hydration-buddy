package app.ellie.hydration.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.ellie.hydration.data.MessageRepo
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.logic.Hydration
import app.ellie.hydration.notifications.Notifier
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.LocalTime

/**
 * "Leaving home -> carry your bottle". Evaluated entirely on-device by Google Play
 * services; the home coordinates are stored encrypted and never uploaded by this app.
 */
object HomeGeofence {
    private const val ID = "home"
    private const val RADIUS_M = 200f

    fun hasPermissions(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, GeofenceReceiver::class.java).setPackage(ctx.packageName)
        // Geofencing requires a MUTABLE PendingIntent (Play services fills in the event).
        // Mitigated by an explicit component + a non-exported receiver.
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** Uses the phone's current position as "home". Call while he is at home. */
    @SuppressLint("MissingPermission")
    fun setHomeHere(ctx: Context, onDone: (Boolean) -> Unit) {
        if (!hasPermissions(ctx)) return onDone(false)
        LocationServices.getFusedLocationProviderClient(ctx)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener onDone(false)
                Prefs(ctx).home = loc.latitude to loc.longitude
                registerIfPossible(ctx)
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }

    @SuppressLint("MissingPermission")
    fun registerIfPossible(ctx: Context) {
        val home = Prefs(ctx).home ?: return
        if (!hasPermissions(ctx)) return
        val fence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(home.first, home.second, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(60_000)
            .build()
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        LocationServices.getGeofencingClient(ctx).addGeofences(req, pendingIntent(ctx))
    }

    fun unregister(ctx: Context) {
        LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(ID))
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val prefs = Prefs(context)
        if (!prefs.onboarded || !prefs.bottleRemindersEnabled) return
        if (!Hydration.isAwake(LocalTime.now(), prefs.wake, prefs.sleep)) return
        // Cooldown: GPS jitter at the boundary can fire repeated exits.
        val now = System.currentTimeMillis()
        if (now - prefs.lastBottleAlertAt < 30 * 60_000L) return
        prefs.lastBottleAlertAt = now
        Notifier.postBottle(context, MessageRepo(context).pick("bottle", prefs.nickname))
    }
}
