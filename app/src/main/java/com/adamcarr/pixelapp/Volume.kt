package com.adamcarr.pixelapp

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

/** Default levels come from your Tasker tasks: alarm, media, ringtone, notifications, system. */
enum class VolumeProfile(
    val label: String,
    val shortcutIcon: Int,
    val defaults: IntArray,
    val hasSounds: Boolean,
    val locked: Boolean, // levels fixed at the defaults and can't be changed
) {
    NORMAL("Normal", R.drawable.shortcut_vol_up, intArrayOf(7, 25, 7, 7, 7), true, false),
    QUIET("Quiet", R.drawable.shortcut_vol_down, intArrayOf(7, 1, 2, 2, 2), true, false),
    SILENT("Silent", R.drawable.shortcut_vol_off, intArrayOf(7, 0, 0, 0, 0), false, true),
}

object Volume {
    // Ringtone goes before notifications so coming out of Silent works cleanly.
    val STREAMS = intArrayOf(
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
    )
    val STREAM_NAMES = listOf("Alarm", "Media", "Ringtone", "Notifications", "System")
    val SOUND_TYPES = intArrayOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
    private const val KEY_CURRENT = "volume_current"

    private fun am(ctx: Context): AudioManager = ctx.getSystemService(AudioManager::class.java)

    private fun levelKey(p: VolumeProfile, i: Int) = "vol_${p.name}_$i"
    private fun soundKey(p: VolumeProfile, type: Int) = "sound_${p.name}_$type"

    fun level(ctx: Context, p: VolumeProfile, i: Int): Int =
        if (p.locked) p.defaults[i] else Prefs.get(ctx).getInt(levelKey(p, i), p.defaults[i])

    fun setLevel(ctx: Context, p: VolumeProfile, i: Int, value: Int) =
        Prefs.get(ctx).edit { putInt(levelKey(p, i), value) }

    fun maxLevel(ctx: Context, i: Int): Int = am(ctx).getStreamMaxVolume(STREAMS[i])

    fun sound(ctx: Context, p: VolumeProfile, type: Int): Uri? =
        Prefs.get(ctx).getString(soundKey(p, type), null)?.let(Uri::parse)

    fun setSound(ctx: Context, p: VolumeProfile, type: Int, uri: Uri?) = Prefs.get(ctx).edit {
        if (uri == null) remove(soundKey(p, type)) else putString(soundKey(p, type), uri.toString())
    }

    fun soundTitle(ctx: Context, uri: Uri?): String {
        if (uri == null) return "No change"
        return try {
            RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Custom sound"
        } catch (e: Exception) {
            "Custom sound"
        }
    }

    fun current(ctx: Context): VolumeProfile? {
        val name = Prefs.get(ctx).getString(KEY_CURRENT, null) ?: return null
        return VolumeProfile.entries.firstOrNull { it.name == name }
    }

    fun apply(ctx: Context, profile: VolumeProfile) {
        val am = am(ctx)
        STREAMS.forEachIndexed { i, stream ->
            val v = level(ctx, profile, i).coerceIn(0, am.getStreamMaxVolume(stream))
            try {
                am.setStreamVolume(stream, v, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "Volume change refused for stream $stream", e)
            }
        }
        if (profile.hasSounds && Settings.System.canWrite(ctx)) {
            for (type in SOUND_TYPES) {
                val uri = sound(ctx, profile, type) ?: continue
                try {
                    RingtoneManager.setActualDefaultRingtoneUri(ctx, type, uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't set sound", e)
                }
            }
        }
        Prefs.get(ctx).edit { putString(KEY_CURRENT, profile.name) }
        VolumeShortcut.update(ctx)
        ctx.toast(profile.label)
    }
}

/**
 * Home-screen shortcut. The launcher draws it exactly like an app icon, and the app
 * changes its icon and label whenever you switch profile.
 */
object VolumeShortcut {
    private const val ID = "volume_profile"

    private fun build(ctx: Context): ShortcutInfo {
        val enabled = Prefs.enabled(ctx, Prefs.VOLUME)
        val current = Volume.current(ctx)
        val label = when {
            !enabled -> "Volume off"
            current != null -> current.label
            else -> "Volume"
        }
        val intent = Intent(ctx, VolumePopupActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ShortcutInfo.Builder(ctx, ID)
            .setShortLabel(label)
            .setLongLabel("Volume: $label")
            .setIcon(Icon.createWithResource(ctx, (current ?: VolumeProfile.NORMAL).shortcutIcon))
            .setIntent(intent)
            .build()
    }

    fun canPin(ctx: Context): Boolean =
        ctx.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    fun requestPin(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) {
            ctx.toast("Your launcher doesn't support adding shortcuts")
            return
        }
        sm.requestPinShortcut(build(ctx), null)
    }

    fun update(ctx: Context) {
        try {
            ctx.getSystemService(ShortcutManager::class.java)?.updateShortcuts(listOf(build(ctx)))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the volume shortcut", e)
        }
    }
}
