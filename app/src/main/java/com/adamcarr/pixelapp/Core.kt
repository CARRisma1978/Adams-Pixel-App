package com.adamcarr.pixelapp

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit

const val TAG = "AdamsPixelApp"

/** All settings and saved state live in one SharedPreferences file. */
object Prefs {
    const val CAMERA = "feature_camera"
    const val CAR = "feature_car"
    const val KEEP_ON = "feature_keep_on"
    const val WIRELESS = "feature_wireless"
    const val VOLUME = "feature_volume"

    const val WIRELESS_WIFI = "wireless_wifi_off"
    const val WIRELESS_SCREEN_OFF = "wireless_screen_off" // on unless turned off
    const val CAMERA_PACKAGE = "camera_package"
    const val DEFAULT_CAMERA_PACKAGE = "com.google.android.GoogleCamera"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabled(ctx: Context, key: String): Boolean = get(ctx).getBoolean(key, false)

    fun setEnabled(ctx: Context, key: String, value: Boolean) = get(ctx).edit { putBoolean(key, value) }

    fun cameraPackage(ctx: Context): String =
        get(ctx).getString(CAMERA_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CAMERA_PACKAGE
}

/** Turning a feature on or off from the app, plus any clean-up it needs. */
object Features {
    fun set(ctx: Context, key: String, on: Boolean) {
        Prefs.setEnabled(ctx, key, on)
        when (key) {
            Prefs.CAMERA -> if (!on) Dnd.remove(ctx, Dnd.CAMERA)
            Prefs.WIRELESS -> if (!on) Wireless.deactivate(ctx, "Feature turned off")
            Prefs.KEEP_ON -> {
                if (!on) KeepScreenOn.turnOff(ctx, announce = false)
                KeepScreenOn.refreshTile(ctx)
            }
            Prefs.VOLUME -> VolumeShortcut.update(ctx)
        }
        ServiceController.sync(ctx)
    }
}

/**
 * Do Not Disturb, shared between Camera quiet and Wireless charging.
 * The first feature to ask for it saves your current setting; when the last one lets go,
 * it's put back (unless you changed it yourself in the meantime).
 */
object Dnd {
    const val CAMERA = "camera"
    const val CHARGING = "charging"
    private const val KEY_REASONS = "dnd_reasons"
    private const val KEY_SAVED = "dnd_saved_filter"

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)

    fun hasAccess(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

    @Synchronized
    fun add(ctx: Context, reason: String) {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "No Do Not Disturb access")
            return
        }
        val p = Prefs.get(ctx)
        val reasons = p.getStringSet(KEY_REASONS, null).orEmpty().toMutableSet()
        if (!reasons.add(reason)) return
        if (reasons.size == 1) {
            val current = nm.currentInterruptionFilter
            p.edit { putInt(KEY_SAVED, current) }
            if (current == NotificationManager.INTERRUPTION_FILTER_ALL) {
                setFilter(nm, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }
        p.edit { putStringSet(KEY_REASONS, reasons) }
    }

    @Synchronized
    fun remove(ctx: Context, reason: String) {
        val p = Prefs.get(ctx)
        val reasons = p.getStringSet(KEY_REASONS, null).orEmpty().toMutableSet()
        if (!reasons.remove(reason)) return
        p.edit { putStringSet(KEY_REASONS, reasons) }
        if (reasons.isEmpty()) {
            val nm = nm(ctx)
            val saved = p.getInt(KEY_SAVED, NotificationManager.INTERRUPTION_FILTER_ALL)
            if (saved == NotificationManager.INTERRUPTION_FILTER_ALL &&
                nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ) {
                setFilter(nm, NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }

    private fun setFilter(nm: NotificationManager, filter: Int) {
        try {
            nm.setInterruptionFilter(filter)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't change Do Not Disturb", e)
        }
    }
}

fun Context.toast(message: String) {
    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}

/** Opens a settings screen, falling back to this app's info page if it doesn't exist. */
fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e2: Exception) {
            toast("Couldn't open that settings screen")
        }
    }
}
