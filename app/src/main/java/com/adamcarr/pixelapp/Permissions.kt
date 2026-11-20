package com.adamcarr.pixelapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings

data class PermItem(
    val title: String,
    val why: String,
    val granted: Boolean,
    val intent: Intent,
    val extraLabel: String? = null,
    val extraIntent: Intent? = null,
)

object Permissions {
    private fun appInfo(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun notifications(ctx: Context) = PermItem(
        "Notifications",
        "Shows the small status notification that keeps the app running in the background.",
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    )

    fun dnd(ctx: Context) = PermItem(
        "Do Not Disturb access",
        "Lets Camera quiet and Wireless charging turn Do Not Disturb on and off, and lets Volume profiles change volumes while it's on.",
        Dnd.hasAccess(ctx),
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    )

    fun writeSettings(ctx: Context) = PermItem(
        "Modify system settings",
        "Lets Keep screen on change the screen timeout, and Volume profiles change your ringtone and notification sound.",
        Settings.System.canWrite(ctx),
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    fun usageAccess(ctx: Context) = PermItem(
        "Usage access",
        "Lets Camera quiet see when the camera app is in front. If the switch is greyed out, open App info, tap ⋮ (top right) and choose Allow restricted settings, then try again.",
        CameraWatcher.hasAccess(ctx),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        "App info",
        appInfo(ctx),
    )

    fun battery(ctx: Context) = PermItem(
        "Unrestricted battery use",
        "Stops Android pausing the app, so the camera, wireless charging and screen-off are always noticed.",
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        ),
    )

    fun nfc(ctx: Context) = PermItem(
        "NFC turned on",
        "Needed to read the Car tag.",
        NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true,
        Intent(Settings.ACTION_NFC_SETTINGS),
    )

    fun all(ctx: Context): List<PermItem> =
        listOf(notifications(ctx), dnd(ctx), writeSettings(ctx), usageAccess(ctx), battery(ctx), nfc(ctx))

    fun missingCount(ctx: Context): Int = all(ctx).count { !it.granted }
}
