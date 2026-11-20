package com.adamcarr.pixelapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.edit

// ---------------------------------------------------------------- Camera quiet

/**
 * Works out which app is in front using Usage Access (allowed under Advanced Protection,
 * unlike Accessibility). CoreService calls poll() about once a second while the screen is on.
 */
object CameraWatcher {
    private var lastQuery = 0L
    private var foreground: String? = null
    private var inCamera = false

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Forget everything and drop any Do Not Disturb left over from before. */
    fun reset(ctx: Context) {
        lastQuery = 0L
        foreground = null
        inCamera = false
        Dnd.remove(ctx, Dnd.CAMERA)
    }

    fun poll(ctx: Context) {
        if (!Prefs.enabled(ctx, Prefs.CAMERA) || !hasAccess(ctx)) {
            setInCamera(ctx, false)
            return
        }
        val now = System.currentTimeMillis()
        // First run looks back 10 minutes; after that, a small overlap so nothing is missed.
        val from = if (lastQuery == 0L) now - 10 * 60_000L else lastQuery - 2_000L
        try {
            val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foreground = e.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == foreground) foreground = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read usage events", e)
            return
        }
        lastQuery = now
        setInCamera(ctx, foreground == Prefs.cameraPackage(ctx))
    }

    private fun setInCamera(ctx: Context, value: Boolean) {
        if (value == inCamera) return
        inCamera = value
        if (value) Dnd.add(ctx, Dnd.CAMERA) else Dnd.remove(ctx, Dnd.CAMERA)
    }
}

// ---------------------------------------------------------------- Car tag

object Car {
    const val TAG_URI = "adamspixel://car"

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun toggleBluetooth(ctx: Context) {
        if (!Prefs.enabled(ctx, Prefs.CAR)) {
            ctx.toast("Car tag is turned off in the app")
            return
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            ctx.toast("This phone has no Bluetooth")
            return
        }
        val wasOn = adapter.isEnabled
        val ok = try {
            if (wasOn) adapter.disable() else adapter.enable()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth toggle refused", e)
            false
        }
        ctx.toast(
            when {
                !ok -> "Couldn't change Bluetooth"
                wasOn -> "Bluetooth off"
                else -> "Bluetooth on"
            }
        )
    }
}

/** Opened by Android when the Car tag is tapped. No screen; it just toggles and closes. */
class CarTagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) Car.toggleBluetooth(this)
        finish()
    }
}

// ---------------------------------------------------------------- Keep screen on

object KeepScreenOn {
    private const val KEY_SAVED = "keep_on_saved_timeout"
    private const val LONG_TIMEOUT = 86_399_000 // 23:59:59, same as the Tasker task

    fun isActive(ctx: Context): Boolean = Prefs.get(ctx).getInt(KEY_SAVED, -1) >= 0

    fun canWrite(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun turnOn(ctx: Context): Boolean {
        if (!canWrite(ctx) || isActive(ctx)) return false
        Wireless.endQuickOff(ctx) // so we save your real timeout, not the 10-second one
        val cr = ctx.contentResolver
        var current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        if (current >= LONG_TIMEOUT) current = 60_000
        Prefs.get(ctx).edit { putInt(KEY_SAVED, current) }
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, LONG_TIMEOUT)
        } catch (e: Exception) {
            Prefs.get(ctx).edit { remove(KEY_SAVED) }
            ctx.toast("Couldn't change the screen timeout")
            return false
        }
        ctx.toast("Screen will stay on until you tap the tile again or lock the phone")
        refreshTile(ctx)
        return true
    }

    fun turnOff(ctx: Context, announce: Boolean) {
        val saved = Prefs.get(ctx).getInt(KEY_SAVED, -1)
        if (saved < 0) return
        if (canWrite(ctx)) {
            try {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't restore the screen timeout", e)
            }
        }
        Prefs.get(ctx).edit { remove(KEY_SAVED) }
        if (announce) ctx.toast("Screen timeout back to ${describe(saved)}")
        refreshTile(ctx)
    }

    private fun describe(ms: Int): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s seconds"
            s == 60 -> "1 minute"
            else -> "${s / 60} minutes"
        }
    }

    fun refreshTile(ctx: Context) {
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, KeepScreenTileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't refresh tile", e)
        }
    }
}

class KeepScreenTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        when {
            !Prefs.enabled(this, Prefs.KEEP_ON) -> toast("Turn on Keep screen on in the app first")
            !KeepScreenOn.canWrite(this) -> openApp()
            KeepScreenOn.isActive(this) -> KeepScreenOn.turnOff(this, announce = true)
            else -> {
                KeepScreenOn.turnOn(this)
                ServiceController.sync(this)
            }
        }
        update()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val enabled = Prefs.enabled(this, Prefs.KEEP_ON)
        val active = enabled && KeepScreenOn.isActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !enabled -> "Off in app"
            active -> "On"
            else -> "Normal timeout"
        }
        tile.updateTile()
    }
}

// ---------------------------------------------------------------- Wireless charging

class WirelessAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Wireless.onAlarmTime(context)
}

object Wireless {
    private const val KEY_ACTIVE = "wireless_active"
    private const val KEY_WIFI_OFF_BY_US = "wireless_wifi_off_by_us"
    private const val KEY_LOG = "wireless_log"
    private const val FULL_PERCENT = 95
    private const val LOW_PERCENT = 90
    private const val DROPOUT_GRACE_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingOff: Runnable? = null
    private var lastOnWireless: Boolean? = null

    fun isActive(ctx: Context): Boolean = Prefs.get(ctx).getBoolean(KEY_ACTIVE, false)

    /**
     * Many wireless chargers stop sending power once the battery is full, which looks to
     * Android exactly like being taken off the charger. So when power stops:
     *  - phone unlocked and in use: you've picked it up, so restore straight away;
     *  - battery full and phone locked: assume it's still on the pad, stay on until you
     *    unlock it or the charger starts again;
     *  - otherwise: wait 30 seconds in case it's just a brief dropout.
     */
    fun onBattery(ctx: Context, intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (plugged < 0) return
        val onWireless = (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0
        val changed = lastOnWireless != onWireless
        lastOnWireless = onWireless
        val pct = percent(intent)

        if (!Prefs.enabled(ctx, Prefs.WIRELESS)) {
            cancelPending()
            if (isActive(ctx)) deactivate(ctx, "Feature turned off")
            return
        }
        if (onWireless) {
            if (pendingOff != null) {
                cancelPending()
                log(ctx, "Charger back within 30 seconds")
            } else if (changed && isActive(ctx)) {
                log(ctx, "Charging again at $pct%")
            }
            if (!isActive(ctx)) activate(ctx, pct)
            return
        }
        if (!isActive(ctx)) return

        // Safety net: a phone on a working pad never drops this low.
        if (pct < LOW_PERCENT) {
            deactivate(ctx, "Battery down to $pct% and not charging, so it's off the charger")
            return
        }
        if (!changed) return

        when {
            plugged != 0 -> deactivate(ctx, "Plugged into a cable instead")
            inUse(ctx) -> deactivate(ctx, "Taken off the charger")
            afterAlarm(ctx) -> deactivate(ctx, "Taken off the charger after your alarm")
            pct >= FULL_PERCENT -> {
                log(ctx, "Charger stopped at $pct% (battery full). Staying on until you unlock the phone, your alarm goes off, or it charges again")
                scheduleAlarmEnd(ctx)
            }
            else -> {
                log(ctx, "Charger stopped at $pct%. Waiting 30 seconds")
                cancelPending()
                val r = Runnable {
                    pendingOff = null
                    if (lastOnWireless == false && isActive(ctx)) deactivate(ctx, "Charger didn't come back")
                }
                pendingOff = r
                handler.postDelayed(r, DROPOUT_GRACE_MS)
            }
        }
    }

    /**
     * Unlocked and in use while not charging means you've picked it up. Called on the
     * unlock broadcast and also every second while the screen is on, because the unlock
     * broadcast alone proved unreliable.
     */
    fun checkInUse(ctx: Context) {
        if (!isActive(ctx) || !inUse(ctx) || onCharger(ctx)) return
        cancelPending()
        deactivate(ctx, "Unlocked off the charger")
    }

    /** True if the phone is getting power from the wireless charger right now. */
    private fun onCharger(ctx: Context): Boolean {
        val battery = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return lastOnWireless != false
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0
    }

    // ---- Alarm time: once your alarm has gone off, you're getting up.

    private const val KEY_AFTER_ALARM = "wireless_after_alarm"
    private const val ALARM_REQUEST = 7

    private fun afterAlarm(ctx: Context): Boolean = Prefs.get(ctx).getBoolean(KEY_AFTER_ALARM, false)

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, ALARM_REQUEST, Intent(ctx, WirelessAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Called while on the charger: wake up at your next alarm (if it's within 16 hours). */
    private fun scheduleAlarmEnd(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val next = am.nextAlarmClock ?: return
        val wait = next.triggerTime - System.currentTimeMillis()
        if (wait <= 0 || wait > 16 * 3_600_000L) return
        try {
            // 30 seconds after it starts ringing.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerTime + 30_000L, alarmIntent(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't schedule alarm check", e)
        }
    }

    private fun cancelAlarmEnd(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
        } catch (e: Exception) {
        }
    }

    fun onAlarmTime(ctx: Context) {
        if (!isActive(ctx)) return
        if (onCharger(ctx)) {
            // Still charging: turn off as soon as it's lifted.
            Prefs.get(ctx).edit { putBoolean(KEY_AFTER_ALARM, true) }
            log(ctx, "Alarm time. Do Not Disturb will turn off when you lift the phone")
        } else {
            deactivate(ctx, "Alarm time and the charger has stopped")
        }
    }

    private fun inUse(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isInteractive &&
            !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun percent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) 0 else level * 100 / scale
    }

    private fun cancelPending() {
        pendingOff?.let { handler.removeCallbacks(it) }
        pendingOff = null
    }

    private fun activate(ctx: Context, pct: Int) {
        val p = Prefs.get(ctx)
        p.edit { putBoolean(KEY_ACTIVE, true) }
        Dnd.add(ctx, Dnd.CHARGING)
        var wifiNote = ""
        if (Prefs.enabled(ctx, Prefs.WIRELESS_WIFI)) {
            val wifi = wifi(ctx)
            if (wifi.isWifiEnabled && setWifi(wifi, false)) {
                p.edit { putBoolean(KEY_WIFI_OFF_BY_US, true) }
                wifiNote = ", Wi-Fi off"
            }
        }
        Prefs.get(ctx).edit { putBoolean(KEY_AFTER_ALARM, false) }
        scheduleAlarmEnd(ctx)
        val screenNote = if (startQuickOff(ctx)) ", screen off in 10 seconds" else ""
        log(ctx, "On the charger at $pct%. Do Not Disturb on$wifiNote$screenNote")
    }

    fun deactivate(ctx: Context, reason: String = "Feature turned off") {
        cancelPending()
        endQuickOff(ctx)
        cancelAlarmEnd(ctx)
        Prefs.get(ctx).edit { putBoolean(KEY_AFTER_ALARM, false) }
        val p = Prefs.get(ctx)
        if (!p.getBoolean(KEY_ACTIVE, false)) return
        p.edit { putBoolean(KEY_ACTIVE, false) }
        Dnd.remove(ctx, Dnd.CHARGING)
        var wifiNote = ""
        if (p.getBoolean(KEY_WIFI_OFF_BY_US, false)) {
            setWifi(wifi(ctx), true)
            p.edit { putBoolean(KEY_WIFI_OFF_BY_US, false) }
            wifiNote = ", Wi-Fi back on"
        }
        log(ctx, "$reason. Do Not Disturb restored$wifiNote")
    }

    // ---- Screen off 10 seconds after going on the charger.
    // Done by setting a 10-second screen timeout, which is put back as soon as the screen
    // goes off (or you take the phone off the charger), so using the phone later isn't affected.

    private const val KEY_QUICK_OFF_SAVED = "wireless_quick_off_saved"
    private const val QUICK_OFF_MS = 10_000

    fun quickOffEnabled(ctx: Context): Boolean =
        Prefs.get(ctx).getBoolean(Prefs.WIRELESS_SCREEN_OFF, true)

    private fun startQuickOff(ctx: Context): Boolean {
        val p = Prefs.get(ctx)
        if (!quickOffEnabled(ctx) || KeepScreenOn.isActive(ctx) || !Settings.System.canWrite(ctx)) return false
        if (p.getInt(KEY_QUICK_OFF_SAVED, -1) >= 0) return true
        val cr = ctx.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        if (current <= QUICK_OFF_MS) return false
        return try {
            p.edit { putInt(KEY_QUICK_OFF_SAVED, current) }
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, QUICK_OFF_MS)
            true
        } catch (e: Exception) {
            p.edit { remove(KEY_QUICK_OFF_SAVED) }
            false
        }
    }

    /** Puts your normal timeout back (unless you've changed it yourself since). */
    fun endQuickOff(ctx: Context) {
        val p = Prefs.get(ctx)
        val saved = p.getInt(KEY_QUICK_OFF_SAVED, -1)
        if (saved < 0) return
        p.edit { remove(KEY_QUICK_OFF_SAVED) }
        if (!Settings.System.canWrite(ctx)) return
        val cr = ctx.contentResolver
        try {
            if (Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, saved) == QUICK_OFF_MS) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, saved)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't restore the screen timeout", e)
        }
    }

    // ---- Recent activity (last 20 events), shown on the Wireless charging page

    private fun log(ctx: Context, message: String) {
        val p = Prefs.get(ctx)
        val lines = p.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() }
        val updated = (listOf("${System.currentTimeMillis()}\t$message") + lines).take(20)
        p.edit { putString(KEY_LOG, updated.joinToString("\n")) }
    }

    fun recentActivity(ctx: Context): List<Pair<Long, String>> =
        Prefs.get(ctx).getString(KEY_LOG, "").orEmpty().lines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            time to parts.getOrElse(1) { "" }
        }

    private fun wifi(ctx: Context): WifiManager =
        ctx.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private fun setWifi(wifi: WifiManager, on: Boolean): Boolean = try {
        wifi.setWifiEnabled(on)
    } catch (e: Exception) {
        Log.w(TAG, "Wi-Fi toggle refused", e)
        false
    }
}
