package com.adamcarr.pixelapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket

/** Talks to a real OpenSSH server on 127.0.0.1:2222 (user pi / raspberry) set up for testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PiIntegrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val log = File("/tmp/sshtest/power.log")

    private fun serverUp() = try { Socket("127.0.0.1", 2222).close(); true } catch (e: Exception) { false }

    private fun device(id: String = "test") =
        PiDevice(id, "Test Pi", "127.0.0.1", 2222, "pi", "unused", null).also { PiStore.save(ctx, it) }

    @Test
    fun statsHostKeyAndAuth() {
        assumeTrue(serverUp())
        val d = device()

        // First connect: trust on first use, key remembered.
        val c = PiConnection(ctx, d, trustNewKey = false, password = "raspberry")
        c.connect()
        val key = PiStore.get(ctx, d.id)?.hostKey
        assertNotNull("host key should be remembered", key)

        val parser = StatsParser()
        parser.parse(c.run(StatsParser.COMMAND), System.currentTimeMillis())
        Thread.sleep(1200)
        val out = c.run(StatsParser.COMMAND)
        val s = parser.parse(out, System.currentTimeMillis())
        println("RAW OUTPUT:\n$out")
        println("PARSED: $s")
        assertTrue(s.memTotal > 0)
        assertTrue(s.cpuCores.isNotEmpty())
        assertTrue(s.diskTotal > 0)
        assertTrue(s.uptimeSec > 0)
        assertEquals(0x50000, s.throttled)
        assertNotNull(s.netRxRate)
        assertTrue(s.top.isNotEmpty())
        assertTrue(s.hostname.isNotEmpty())
        c.disconnect()

        // Same key again: fine.
        PiConnection(ctx, d, false, "raspberry").apply { connect(); disconnect() }

        // Key changes: refused before the password is sent.
        // A well-formed but different key...
        PiStore.setHostKey(ctx, d.id, "AAAAC3NzaC1lZDI1NTE5AAAAIEJvZ3VzQm9ndXNCb2d1c0JvZ3VzQm9ndXNCb2d1cw==")
        try {
            PiConnection(ctx, d, false, "raspberry").connect()
            fail("should have refused a changed host key")
        } catch (e: HostKeyChangedException) {
        }
        // ...and a corrupted one are both refused.
        PiStore.setHostKey(ctx, d.id, "not-base64!!")
        try {
            PiConnection(ctx, d, false, "raspberry").connect()
            fail("should have refused a changed host key")
        } catch (e: HostKeyChangedException) {
        }
        // Trusting the new key works and stores it.
        PiConnection(ctx, d, true, "raspberry").apply { connect(); disconnect() }
        assertEquals(key, PiStore.get(ctx, d.id)?.hostKey)

        // Wrong password gives a friendly message.
        try {
            PiConnection(ctx, d, false, "wrong").connect()
            fail("wrong password should fail")
        } catch (e: PiAuthException) {
            println("Wrong password message: ${e.message}")
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test
    fun shutdownClosesEmulationStationFirst() {
        assumeTrue(serverUp())
        log.writeText("")
        // Start a fake EmulationStation under RetroPie's wrapper, as user pi.
        ProcessBuilder("su", "pi", "-c", "nohup /tmp/sshtest/es-wrapper.sh >/dev/null 2>&1 &").start().waitFor()
        Thread.sleep(800)
        val c = PiConnection(ctx, device("p2"), false, "raspberry")
        c.connect()
        val start = System.currentTimeMillis()
        c.power(reboot = false)
        c.disconnect()
        val text = log.readText()
        println("POWER LOG (${System.currentTimeMillis() - start} ms):\n$text")
        assertTrue(text.contains("ES got TERM"))
        assertTrue(text.contains("WRAPPER poweroff"))
        assertTrue(text.contains("SUDO pw=raspberry cmd=systemctl poweroff"))
        assertTrue(text.indexOf("ES got TERM") < text.indexOf("SUDO"))
    }

    @Test
    fun rebootWithoutEmulationStation() {
        assumeTrue(serverUp())
        log.writeText("")
        val c = PiConnection(ctx, device("p3"), false, "raspberry")
        c.connect()
        c.power(reboot = true)
        c.disconnect()
        val text = log.readText()
        println("REBOOT LOG:\n$text")
        assertEquals("SUDO pw=raspberry cmd=systemctl reboot", text.trim())
    }

    @Test
    fun throttleDecoding() {
        assertEquals(1, throttleMessages(0).size)
        val m = throttleMessages(0x50005).map { it.first }
        assertTrue(m.any { it.startsWith("Under-voltage now") })
        assertTrue(m.any { it.startsWith("Throttled now") })
        assertTrue(m.none { it.contains("since boot") && it.contains("Under-voltage") })
    }
}
