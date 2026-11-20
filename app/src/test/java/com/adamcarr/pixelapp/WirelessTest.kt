package com.adamcarr.pixelapp

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private fun battery(wireless: Boolean, pct: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_PLUGGED, if (wireless) BatteryManager.BATTERY_PLUGGED_WIRELESS else 0)
        .putExtra(BatteryManager.EXTRA_LEVEL, pct)
        .putExtra(BatteryManager.EXTRA_SCALE, 100)

    private fun screen(on: Boolean, locked: Boolean) {
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(on)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    }

    private fun dndOn() = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY

    @Before
    fun setUp() {
        Prefs.get(ctx).edit().clear().commit()
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        Prefs.setEnabled(ctx, Prefs.WIRELESS, true)
        // Fresh "last state" each test.
        Wireless.onBattery(ctx, battery(false, 50))
        Wireless.deactivate(ctx, "reset")
    }

    @Test
    fun fullBatteryChargerStopKeepsDndUntilUnlock() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 97))
        assertTrue(Wireless.isActive(ctx)); assertTrue(dndOn())
        // Charger stops because the battery is full.
        Wireless.onBattery(ctx, battery(false, 100))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(60))
        assertTrue("should stay on overnight", Wireless.isActive(ctx)); assertTrue(dndOn())
        // Charger tops up again later: still on.
        Wireless.onBattery(ctx, battery(true, 99))
        Wireless.onBattery(ctx, battery(false, 100))
        assertTrue(Wireless.isActive(ctx))
        // Morning: phone unlocked off the charger.
        screen(on = true, locked = false)
        @Suppress("DEPRECATION") ctx.sendStickyBroadcast(battery(false, 100))
        Wireless.checkInUse(ctx)
        assertFalse(Wireless.isActive(ctx)); assertFalse(dndOn())
        Wireless.recentActivity(ctx).forEach { println("LOG: ${it.second}") }
    }

    @Test
    fun shortDropoutIgnoredLongOneRestores() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 60))
        Wireless.onBattery(ctx, battery(false, 60))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        Wireless.onBattery(ctx, battery(true, 60))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue("brief dropout ignored", Wireless.isActive(ctx))
        Wireless.onBattery(ctx, battery(false, 61))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertFalse("restored after 30s", Wireless.isActive(ctx)); assertFalse(dndOn())
    }

    @Test
    fun pickedUpAndInUseRestoresImmediately() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 100))
        screen(on = true, locked = false)
        Wireless.onBattery(ctx, battery(false, 100))
        assertFalse(Wireless.isActive(ctx)); assertFalse(dndOn())
    }

    @Test
    fun repeatedBatteryUpdatesDontRelog() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 99))
        repeat(5) { Wireless.onBattery(ctx, battery(false, 100)) }
        val held = Wireless.recentActivity(ctx).count { it.second.contains("battery full") }
        assertEquals(1, held)
    }

    private fun grantWriteSettings() {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        shadowOf(ops).setMode(23 /* OP_WRITE_SETTINGS */, android.os.Process.myUid(), ctx.packageName, android.app.AppOpsManager.MODE_ALLOWED)
        println("canWrite=" + android.provider.Settings.System.canWrite(ctx))
    }

    private fun timeout() = android.provider.Settings.System.getInt(
        ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
    )

    @Test
    fun screenOffTenSecondsAfterPlacingThenNormalTimeoutBack() {
        grantWriteSettings()
        android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        screen(on = true, locked = true)
        Wireless.onBattery(ctx, battery(true, 40))
        assertEquals(10_000, timeout())
        // Screen goes off: normal timeout restored, still charging with DND on.
        Wireless.endQuickOff(ctx)
        assertEquals(60_000, timeout())
        assertTrue(Wireless.isActive(ctx))
        // Keep screen on started while the 10s timeout is in force saves the REAL timeout.
        Wireless.deactivate(ctx, "reset")
        Wireless.onBattery(ctx, battery(false, 40))
        Wireless.onBattery(ctx, battery(true, 40))
        assertEquals(10_000, timeout())
        assertTrue(KeepScreenOn.turnOn(ctx))
        KeepScreenOn.turnOff(ctx, announce = false)
        assertEquals(60_000, timeout())
    }

    @Test
    fun screenOffOptionCanBeTurnedOff() {
        grantWriteSettings()
        android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        Prefs.setEnabled(ctx, Prefs.WIRELESS_SCREEN_OFF, false)
        Wireless.onBattery(ctx, battery(true, 40))
        assertEquals(60_000, timeout())
        assertTrue(Wireless.isActive(ctx))
    }

    @Test
    fun unlockNoticedByPollingEvenWithoutBroadcast() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 99))
        Wireless.onBattery(ctx, battery(false, 100))
        @Suppress("DEPRECATION") ctx.sendStickyBroadcast(battery(false, 100))
        // Screen lights up for the alarm but stays locked: still on.
        screen(on = true, locked = true)
        Wireless.checkInUse(ctx)
        assertTrue(Wireless.isActive(ctx))
        // Unlocked: the once-a-second check turns it off.
        screen(on = true, locked = false)
        Wireless.checkInUse(ctx)
        assertFalse(Wireless.isActive(ctx)); assertFalse(dndOn())
    }

    @Test
    fun usingPhoneWhileStillChargingKeepsDnd() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 50))
        @Suppress("DEPRECATION") ctx.sendStickyBroadcast(battery(true, 50))
        screen(on = true, locked = false)
        Wireless.checkInUse(ctx)
        assertTrue(Wireless.isActive(ctx))
    }

    @Test
    fun alarmTimeWhileChargerStoppedTurnsOff() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 99))
        Wireless.onBattery(ctx, battery(false, 100))
        @Suppress("DEPRECATION") ctx.sendStickyBroadcast(battery(false, 100))
        Wireless.onAlarmTime(ctx)
        assertFalse(Wireless.isActive(ctx)); assertFalse(dndOn())
        Wireless.recentActivity(ctx).forEach { println("LOG: ${it.second}") }
    }

    @Test
    fun alarmTimeWhileChargingThenLiftedLockedTurnsOff() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 100))
        @Suppress("DEPRECATION") ctx.sendStickyBroadcast(battery(true, 100))
        Wireless.onAlarmTime(ctx)
        assertTrue("still on the charger", Wireless.isActive(ctx))
        screen(on = true, locked = true) // alarm screen showing over the lock screen
        Wireless.onBattery(ctx, battery(false, 100))
        assertFalse(Wireless.isActive(ctx)); assertFalse(dndOn())
        Wireless.recentActivity(ctx).forEach { println("LOG: ${it.second}") }
    }

    @Test
    fun lowBatteryWhileHeldTurnsOff() {
        screen(on = false, locked = true)
        Wireless.onBattery(ctx, battery(true, 99))
        Wireless.onBattery(ctx, battery(false, 99))
        assertTrue(Wireless.isActive(ctx))
        Wireless.onBattery(ctx, battery(false, 89))
        assertFalse(Wireless.isActive(ctx))
    }

    @Test
    fun silentIsLocked() {
        Prefs.get(ctx).edit().putInt("vol_SILENT_1", 20).commit()
        assertEquals(0, Volume.level(ctx, VolumeProfile.SILENT, 1))
        assertEquals(7, Volume.level(ctx, VolumeProfile.SILENT, 0))
    }
}
