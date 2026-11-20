package com.adamcarr.pixelapp

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
class UiTest {
    @get:Rule
    val compose = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        Prefs.get(ctx).edit().clear().commit()
    }

    @Test
    fun dragCameraRowDownTwoPlaces() {
        compose.setContent { AppTheme { HomeScreen(0) {} } }
        assertEquals(listOf("camera", "car", "keep_on", "wireless", "volume", "pi"), MenuOrder.load(ctx))
        val rowHeight = 72f * 3f // two-line list row, 72dp at xxhdpi
        // Long-press, then drag down about two and a bit rows, in small steps like a finger.
        compose.onNodeWithText("Do Not Disturb while the camera is open").performTouchInput {
            down(center)
            advanceEventTime(800)
            repeat(25) { moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight * 2.3f / 25)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
        val order = MenuOrder.load(ctx)
        println("ORDER AFTER DRAG DOWN: $order")
        assertEquals(listOf("car", "keep_on", "camera", "wireless", "volume", "pi"), order)

        // And drag Pi monitor (last) up to the top.
        compose.onNodeWithText("Add your Raspberry Pi").performTouchInput {
            down(center)
            advanceEventTime(800)
            repeat(40) { moveBy(androidx.compose.ui.geometry.Offset(0f, -rowHeight * 6f / 40)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
        val order2 = MenuOrder.load(ctx)
        println("ORDER AFTER DRAG UP: $order2")
        assertEquals("pi", order2.first())
    }

    @Test
    fun tapStillOpensAndSwitchStillToggles() {
        var opened: Screen? = null
        compose.setContent { AppTheme { HomeScreen(0) { opened = it } } }
        compose.onNodeWithText("Car tag").performClick()
        compose.waitForIdle()
        assertEquals(Screen.CAR, opened)
        // A plain long press without moving shouldn't change the order.
        compose.onNodeWithText("Car tag").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("camera", MenuOrder.load(ctx).first())
    }

    @Test
    fun screensRender() {
        PiStore.save(ctx, PiDevice("a", "RetroPie Pi 5", "192.168.1.50", 22, "pi", "x", null))
        compose.setContent {
            AppTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VolumeSettings(0)
                    PiListScreen(0, {}, {})
                    PiEditScreen("a") {}
                    StatsCards(
                        PiStats(
                            model = "Raspberry Pi 5 Model B Rev 1.0", hostname = "retropie",
                            uptimeSec = 200_000, load = "0.52  0.40  0.33", cpuTotal = 23f,
                            cpuCores = listOf(10f, 20f, 30f, 40f), freqMhz = 2400, tempC = 62.5f,
                            fanRpm = 3210, throttled = 0x50000, memTotal = 16L shl 30, memUsed = 3L shl 30,
                            diskTotal = 256L shl 30, diskUsed = 40L shl 30, netRxRate = 12_345.0, netTxRate = 678.0,
                            top = listOf(Triple("45.0", "3.2", "box64")),
                        ),
                        lastUpdate = 0L, live = true,
                    )
                }
            }
        }
        assertEquals(3, compose.onAllNodesWithTextCount("Use now"))
        // Silent has no sound pickers: 2 profiles x 2 sound rows = 4 "Choose" buttons.
        assertEquals(4, compose.onAllNodesWithTextCount("Choose"))
        assertTrue(compose.onAllNodesWithTextCount("RetroPie Pi 5") >= 1)
        compose.onNodeWithText("Temperature  62.5 °C").assertExists()
        compose.onNodeWithText("3,210 rpm").assertExists()
        compose.onNodeWithText("Throttling happened since boot.").assertExists()
        compose.onNodeWithText("Under-voltage happened since boot.").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
