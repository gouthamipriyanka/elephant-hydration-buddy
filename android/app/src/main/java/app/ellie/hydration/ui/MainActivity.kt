package app.ellie.hydration.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ellie.hydration.BuildConfig
import app.ellie.hydration.R
import app.ellie.hydration.data.AppDatabase
import app.ellie.hydration.data.MessageRepo
import app.ellie.hydration.data.Prefs
import app.ellie.hydration.geo.HomeGeofence
import app.ellie.hydration.logic.Hydration
import app.ellie.hydration.net.Api
import app.ellie.hydration.net.BackendConfig
import app.ellie.hydration.notifications.ActionReceiver
import app.ellie.hydration.notifications.Notifier
import app.ellie.hydration.push.PushSetup
import app.ellie.hydration.schedule.Scheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private enum class Screen { WELCOME, SETUP, HOME, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(if (prefs.onboarded) Screen.HOME else Screen.WELCOME) }
                    when (screen) {
                        Screen.WELCOME -> Welcome { screen = Screen.SETUP }
                        Screen.SETUP -> Setup { screen = Screen.HOME }
                        Screen.HOME -> Home { screen = Screen.SETTINGS }
                        Screen.SETTINGS -> SettingsScreen(
                            onBack = { screen = Screen.HOME },
                            onWiped = { screen = Screen.WELCOME },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun Welcome(onNext: () -> Unit) = Page {
    Image(painterResource(R.drawable.elephant_happy), contentDescription = "Ellie the elephant", Modifier.size(200.dp))
    Text("Hi love 💙", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(
        "Meet Ellie! She'll gently remind you to drink water, cheer when you do, " +
            "and nudge you to grab your bottle when you head out.\n\nI'm always here for you.",
        textAlign = TextAlign.Center,
    )
    Text("— ${BuildConfig.FROM_NAME}", fontWeight = FontWeight.SemiBold)
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Let's set Ellie up 🐘") }
}

/** Everything is opt-in. Nothing reads WHOOP data or location until he chooses to here. */
@Composable
private fun Setup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(prefs.deviceToken != null) }
    var bottle by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val bgPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Location allowed. Tap \"I'm at home now\"." else "No problem, bottle reminders stay off."
    }
    val finePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Build.VERSION.SDK_INT >= 29) bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    Page {
        Text("Setup", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What Ellie uses, and only if you allow it:", fontWeight = FontWeight.SemiBold)
                Text("• Notifications: for reminders and kudos.")
                if (BackendConfig.enabled) Text("• WHOOP (optional): your workouts and weight, to set a healthy daily goal. Read-only; you can disconnect any time.")
                Text("• Location (optional): only to notice you leaving home. It never leaves your phone.")
                Text("• Your water log stays encrypted on this phone.")
            }
        }

        Text("1. Allow notifications", fontWeight = FontWeight.SemiBold)
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }, modifier = Modifier.fillMaxWidth()) { Text("Allow notifications") }

        if (BackendConfig.enabled) {
        Text("2. Pair with the pairing code (optional, enables WHOOP)", fontWeight = FontWeight.SemiBold)
        if (!paired) {
            OutlinedTextField(code, { code = it.take(64) }, label = { Text("Pairing code") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    status = try {
                        prefs.deviceToken = Api(null).pair(code)
                        paired = true
                        "Paired 💙"
                    } catch (e: Exception) { "Pairing failed. Check the code and try again." }
                }
            }, enabled = code.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("Pair") }
        } else {
            Text("Paired ✅")
            WhoopConnect(prefs) { status = it }
        }
        }

        Text((if (BackendConfig.enabled) "3" else "2") + ". Bottle reminder when leaving home (optional)", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(bottle, { on ->
                bottle = on
                if (on && !HomeGeofence.hasPermissions(ctx)) finePerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            })
            Spacer(Modifier.size(8.dp))
            Text(if (bottle) "On" else "Off")
        }
        if (bottle) {
            OutlinedButton(onClick = {
                HomeGeofence.setHomeHere(ctx) { ok -> status = if (ok) "Home saved 🏠" else "Couldn't get location. Check permissions." }
            }, modifier = Modifier.fillMaxWidth()) { Text("I'm at home now: save as home") }
        }

        Text((if (BackendConfig.enabled) "4" else "3") + ". Keep Ellie reliable", fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { openBatterySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Battery settings: set Ellie to 'Unrestricted'")
        }

        if (status.isNotEmpty()) Text(status, textAlign = TextAlign.Center)

        Button(onClick = {
            prefs.bottleRemindersEnabled = bottle && prefs.home != null
            prefs.onboarded = true
            Scheduler.ensureScheduled(ctx)
            if (prefs.deviceToken != null) {
                PushSetup.enable(ctx)
                Scheduler.syncNow(ctx)
            }
            onDone()
        }, modifier = Modifier.fillMaxWidth()) { Text("Done 🐘💧") }
    }
}

@Composable
private fun WhoopConnect(prefs: Prefs, onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(prefs.whoopConnected) }
    if (connected) { Text("WHOOP connected ✅"); return }
    Button(onClick = {
        scope.launch {
            try {
                val url = Uri.parse(Api(prefs.deviceToken).whoopConnectUrl())
                // Only ever open WHOOP's real login page over HTTPS.
                if (url.scheme != "https" || url.host != BuildConfig.WHOOP_AUTH_HOST) {
                    onStatus("Unexpected login address. Not opening it."); return@launch
                }
                CustomTabsIntent.Builder().build().launchUrl(ctx, url)
            } catch (e: Exception) { onStatus("Couldn't start WHOOP login. Try again later.") }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Connect WHOOP (optional)") }
    OutlinedButton(onClick = {
        scope.launch {
            connected = runCatching { Api(prefs.deviceToken).whoopConnected() }.getOrDefault(false)
            prefs.whoopConnected = connected
            onStatus(if (connected) "WHOOP connected 💪" else "Not connected yet.")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("I've finished WHOOP login") }
}

@Composable
private fun Home(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val day = remember { Hydration.dayKey(LocalDate.now()) }
    val dao = remember { AppDatabase.get(ctx).intake() }
    val total by remember(day) { dao.totalFlow(day) }.collectAsState(initial = 0)
    val target = prefs.targetMl
    val expected = Hydration.expectedByNow(target, LocalTime.now(), prefs.wake, prefs.sleep)
    val mood = if (total >= target) Hydration.Mood.HAPPY else Hydration.mood(total, expected)
    val fact = remember { MessageRepo(ctx).facts().randomOrNull() }
    var custom by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    fun log(ml: Int) = scope.launch { ActionReceiver.logIntake(ctx, ml) }

    Page {
        val art = when (mood) {
            Hydration.Mood.HAPPY -> R.drawable.elephant_happy
            Hydration.Mood.NEUTRAL -> R.drawable.elephant_neutral
            Hydration.Mood.THIRSTY -> R.drawable.elephant_thirsty
        }
        Image(painterResource(art), contentDescription = "Ellie is ${mood.name.lowercase()}", Modifier.size(220.dp))
        Text(
            when (mood) {
                Hydration.Mood.HAPPY -> "Ellie is happy! 🎉"
                Hydration.Mood.NEUTRAL -> "Ellie could use a sip 💧"
                Hydration.Mood.THIRSTY -> "Ellie is thirsty 🥺"
            },
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
        )
        Text("$total / $target ml today", fontSize = 18.sp)
        if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Text("⚠️ Notifications are off, so Ellie can't remind you. Turn them on in Settings → Apps → Ellie → Notifications.",
                textAlign = TextAlign.Center)
        }
        LinearProgressIndicator(progress = { (total.toFloat() / target).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp))
        if (BackendConfig.enabled && prefs.targetSource.startsWith("fallback")) {
            Text(
                if (prefs.targetSource == "fallback_whoop_error") "WHOOP needs reconnecting (Settings). Using a standard goal."
                else "Using a standard goal. Connect WHOOP for a personalised one.",
                fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { log(250) }) { Text("+250 ml") }
            Button(onClick = { log(500) }) { Text("+500 ml") }
        }
        OutlinedTextField(custom, { custom = it.take(30) }, label = { Text("Other amount (ml)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val ml = Hydration.parseAmountMl(custom)
            if (ml == null) hint = "Try a number like 300, or \"1 glass\"." else { log(ml); custom = ""; hint = "" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Log it") }
        if (hint.isNotEmpty()) Text(hint)
        TextButton(onClick = { scope.launch { dao.deleteLastFor(day) } }) { Text("Undo last entry") }

        fact?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(it.text)
                    Text("Source: ${it.source}", fontSize = 11.sp)
                }
            }
        }
        Text("Goals are general wellness guidance, not medical advice.", fontSize = 11.sp, textAlign = TextAlign.Center)
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onWiped: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var nick by remember { mutableStateOf(prefs.nickname) }
    var wake by remember { mutableStateOf(fmt(prefs.wakeMinutes)) }
    var sleep by remember { mutableStateOf(fmt(prefs.sleepMinutes)) }
    var weight by remember { mutableStateOf(if (prefs.manualWeightKg > 0) prefs.manualWeightKg.toString() else "") }
    var bottle by remember { mutableStateOf(prefs.bottleRemindersEnabled) }
    var msg by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Page {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(nick, { nick = it.take(30) }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(wake, { wake = it.take(5) }, label = { Text("Wake time (HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sleep, { sleep = it.take(5) }, label = { Text("Sleep time (HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(weight, { weight = it.take(6) }, label = { Text("Weight kg (only if no WHOOP)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(bottle, { bottle = it })
            Spacer(Modifier.size(8.dp))
            Text("Bottle reminder when leaving home")
        }
        Button(onClick = {
            val w = parseTime(wake); val s = parseTime(sleep)
            val kg = weight.toFloatOrNull()
            msg = when {
                w == null || s == null || s <= w -> "Times must be HH:MM and sleep after wake."
                weight.isNotBlank() && (kg == null || kg !in 30f..250f) -> "Weight must be between 30 and 250 kg."
                else -> {
                    prefs.nickname = nick.trim(); prefs.wakeMinutes = w!!; prefs.sleepMinutes = s!!
                    prefs.manualWeightKg = kg ?: 0f
                    prefs.bottleRemindersEnabled = bottle && prefs.home != null
                    if (prefs.bottleRemindersEnabled) HomeGeofence.registerIfPossible(ctx) else HomeGeofence.unregister(ctx)
                    Scheduler.syncNow(ctx)
                    if (bottle && prefs.home == null) "Saved. To enable bottle reminders, set home in setup." else "Saved 💙"
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        OutlinedButton(onClick = { openBatterySettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Battery settings") }
        OutlinedButton(onClick = {
            Notifier.postReminder(ctx, Hydration.Mood.THIRSTY, MessageRepo(ctx).pick("behind", prefs.nickname), 0, prefs.targetMl)
        }, modifier = Modifier.fillMaxWidth()) { Text("Send a test reminder") }

        if (!confirmDelete) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete all my data…") }
        } else {
            Text("This removes your water log, settings, WHOOP connection and server data. Are you sure?", textAlign = TextAlign.Center)
            Button(onClick = {
                scope.launch {
                    val serverOk = prefs.deviceToken?.let { runCatching { Api(it).deleteEverything() }.isSuccess } ?: true
                    AppDatabase.get(ctx).intake().deleteAll()
                    HomeGeofence.unregister(ctx)
                    Scheduler.cancelAll(ctx)
                    Notifier.cancelAll(ctx)
                    PushSetup.disable(ctx)
                    prefs.wipe()
                    if (!serverOk) msg = "Phone data deleted. The server couldn't be reached; ask to delete it there too."
                    onWiped()
                }
            }) { Text("Yes, delete everything") }
        }
        if (msg.isNotEmpty()) Text(msg, textAlign = TextAlign.Center)
        Text("Health facts come from public pages by the CDC, Mayo Clinic, NHS and Michigan State University Extension.",
            fontSize = 11.sp, textAlign = TextAlign.Center)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private fun parseTime(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

private fun openBatterySettings(ctx: Context) {
    // Opens the list screen; no special permission (Play policy restricts the direct-exemption intent).
    runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
