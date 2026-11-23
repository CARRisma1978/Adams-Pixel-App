package com.adamcarr.pixelapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File("/tmp/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() {
        Prefs.get(ctx).edit().clear().putBoolean(Prefs.CAMERA, true).putBoolean(Prefs.VOLUME, true).commit()
        PiStore.save(ctx, PiDevice("a", "RetroPie Pi 5", "192.168.1.50", 22, "pi", "x", null))
        compose.setContent { AppTheme { HomeScreen(0) {} } }
        save("home")
    }

    @Test fun piStats() {
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    StatsCards(
                        PiStats(
                            model = "Raspberry Pi 5 Model B Rev 1.0", hostname = "retropie",
                            uptimeSec = 200_000, load = "0.52  0.40  0.33", cpuTotal = 23f,
                            cpuCores = listOf(10f, 20f, 30f, 40f), freqMhz = 2400, tempC = 62.5f,
                            fanRpm = 3210, throttled = 0, memTotal = 16L shl 30, memUsed = 3L shl 30,
                            swapTotal = 512L shl 20, swapUsed = 0,
                            diskTotal = 256L shl 30, diskUsed = 40L shl 30, netRxRate = 12_345.0, netTxRate = 678.0,
                            top = listOf(Triple("45.0", "3.2", "box64"), Triple("12.1", "1.0", "wineserver")),
                        ),
                        lastUpdate = 1_762_617_000_000L, live = true,
                    )
                }
            }
        }
        save("pistats")
    }

    @Test fun volumeAndPi() {
        Prefs.get(ctx).edit().clear().putBoolean(Prefs.VOLUME, true).commit()
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    PiListScreen(0, {}, {})
                    VolumeSettings(0)
                }
            }
        }
        save("volume_pi")
    }

    @Test fun piKeyEdit() {
        PiStore.save(ctx, PiDevice("car", "Car Pi 4", "192.168.1.50", 22, "root", "", null, useKey = true,
            encPrivateKey = "x", publicKey = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBExample pixel9pro-car"))
        compose.setContent {
            AppTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { PiEditScreen("car") {} } }
        }
        save("pi_key_edit")
    }

    @Test fun shortcutIcons() {
        val out = Bitmap.createBitmap(4 * 216, 216, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.BLACK)
        VolumeProfile.entries.forEachIndexed { i, p ->
            val d = ctx.getDrawable(p.shortcutIcon)!!
            d.setBounds(i * 216 + 20, 20, i * 216 + 196, 196); d.draw(c)
        }
        val app = ctx.getDrawable(R.mipmap.ic_launcher)!!
        app.setBounds(3 * 216 + 20, 20, 3 * 216 + 196, 196); app.draw(c)
        File(dir, "shortcuts.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
