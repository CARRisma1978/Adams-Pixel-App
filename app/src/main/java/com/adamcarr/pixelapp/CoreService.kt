package com.adamcarr.pixelapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Small always-on service (like Tasker's). Android only delivers "screen off" and
 * "charger changed" events to apps that are running, so this keeps us listening.
 */
class CoreService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    KeepScreenOn.turnOff(context, announce = false)
                    Wireless.endQuickOff(context)
                    stopPolling()
                }
                Intent.ACTION_SCREEN_ON -> startPolling()
                Intent.ACTION_USER_PRESENT -> Wireless.checkInUse(context)
                Intent.ACTION_BATTERY_CHANGED -> Wireless.onBattery(context, intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        CameraWatcher.reset(this)
    }

    // About once a second, only while the screen is on: which app is in front (Camera quiet),
    // and whether the phone has been unlocked off the charger (Wireless charging).
    private val handler = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            CameraWatcher.poll(this@CoreService)
            Wireless.checkInUse(this@CoreService)
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun startPolling() {
        handler.removeCallbacks(pollTask)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (interactive && (Prefs.enabled(this, Prefs.CAMERA) || Prefs.enabled(this, Prefs.WIRELESS))) {
            handler.post(pollTask)
        } else {
            CameraWatcher.poll(this) // lets it switch Do Not Disturb back off if the feature was turned off
        }
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollTask)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-check the charger whenever a setting changes.
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            Wireless.onBattery(this, it)
        }
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        CameraWatcher.reset(this)
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already gone", e)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Running your routines")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "status"
        const val NOTIFICATION_ID = 1

        fun createChannel(ctx: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Background status", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the app running so it can react to the camera, charging and screen off"
                setShowBadge(false)
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

object ServiceController {
    /** Runs the service only while a feature that needs it is turned on. */
    fun sync(ctx: Context) {
        val needed = Prefs.enabled(ctx, Prefs.WIRELESS) ||
            Prefs.enabled(ctx, Prefs.KEEP_ON) ||
            Prefs.enabled(ctx, Prefs.CAMERA)
        val intent = Intent(ctx, CoreService::class.java)
        try {
            if (needed) ctx.startForegroundService(intent) else ctx.stopService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start or stop the service", e)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // After a reboot, make sure the screen timeout isn't left at 24 hours.
        KeepScreenOn.turnOff(context, announce = false)
        Wireless.endQuickOff(context)
        ServiceController.sync(context)
        VolumeShortcut.update(context)
    }
}
