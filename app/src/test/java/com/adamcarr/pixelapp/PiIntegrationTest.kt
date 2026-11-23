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

    private fun keyDevice(id: String, keyFile: String) =
        PiDevice(id, "Key Pi", "127.0.0.1", 2222, "pi", "", null, useKey = true).also { PiStore.save(ctx, it) }

    @Test
    fun signInWithEd25519KeyFile() {
        assumeTrue(serverUp())
        val text = File("/tmp/sshtest/keys/id_ed25519").readText()
        val key = SshKeys.load(text, null)
        println("PUBLIC: ${key.publicKey}")
        assertEquals("ed25519", key.type)
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAA"))
        val c = PiConnection(ctx, keyDevice("k1", "id_ed25519"), false, password = "", privateKey = text, passphrase = null)
        c.connect()
        assertTrue(c.run("echo hello").contains("hello"))
        c.disconnect()
    }

    @Test
    fun protectedKeyNeedsPassphrase() {
        assumeTrue(serverUp())
        val text = File("/tmp/sshtest/keys/id_prot").readText()
        try { SshKeys.load(text, null); fail() } catch (e: IllegalArgumentException) { println("MSG: ${e.message}") }
        try { SshKeys.load(text, "wrong"); fail() } catch (e: IllegalArgumentException) { println("MSG: ${e.message}") }
        assertEquals("ed25519", SshKeys.load(text, "secret123").type)
        val c = PiConnection(ctx, keyDevice("k2", "id_prot"), false, password = "", privateKey = text, passphrase = "secret123")
        c.connect(); c.disconnect()
    }

    @Test
    fun keyNotOnThePiGivesClearMessage() {
        assumeTrue(serverUp())
        val generated = SshKeys.generate("pixel9pro-test")
        assertTrue(generated.publicKey.startsWith("ecdsa-sha2-nistp256 "))
        // The new key round-trips through load().
        assertEquals(generated.publicKey.split(" ")[1], SshKeys.load(generated.privateKey, null).publicKey.split(" ")[1])
        try {
            PiConnection(ctx, keyDevice("k3", ""), false, password = "", privateKey = generated.privateKey, passphrase = null).connect()
            fail()
        } catch (e: PiAuthException) {
            println("MSG: ${e.message}")
            assertTrue(e.message!!.contains("didn't accept this key"))
        }
    }

    @Test
    fun publicKeyFileIsRejected() {
        try { SshKeys.load(File("/tmp/sshtest/keys/id_ed25519.pub").readText(), null); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("public key")) }
    }

    @Test
    fun androidStatsParse() {
        assumeTrue(File("/tmp/toybin/ps").exists())
        // Pretend to be Android just for this test.
        File("/system").mkdirs(); File("/data").mkdirs()
        val marker = File("/system/build.prop").apply { writeText("") }
        try { androidStatsParseInner() } finally { marker.delete(); File("/system").delete() }
    }

    private fun androidStatsParseInner() {
        fun runCmd(): String {
            val p = ProcessBuilder("sh", "-c", StatsParser.COMMAND).apply {
                environment()["PATH"] = "/tmp/toybin:/usr/bin:/bin"
            }.start()
            return p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }
        val parser = StatsParser()
        parser.parse(runCmd(), System.currentTimeMillis())
        Thread.sleep(1000)
        val s = parser.parse(runCmd(), System.currentTimeMillis())
        println("ANDROID PARSED: $s")
        assertTrue(s.android)
        assertTrue(s.osName.contains("KonstaKANG"))
        assertEquals("lineage-car", s.hostname)
        assertTrue(s.diskTotal > 1_000_000_000L)
        assertTrue(s.top.isNotEmpty())
        assertTrue(s.memTotal > 0)
    }

    /** Runs the Android power script against fake svc/su/reboot/getprop commands. */
    private fun runAndroidPower(reboot: Boolean, svcWorks: Boolean, suWorks: Boolean): String {
        val bin = File("/tmp/fakeandroid/bin").apply { deleteRecursively(); mkdirs() }
        val props = File("/tmp/fakeandroid/props").apply { deleteRecursively(); mkdirs() }
        val log = File("/tmp/fakeandroid/log.txt").apply { writeText("") }
        fun script(name: String, body: String) = File(bin, name).apply {
            writeText("#!/bin/sh\n$body\n"); setExecutable(true)
        }
        // getprop/setprop read and write files; svc and reboot only "work" when allowed.
        script("getprop", "cat /tmp/fakeandroid/props/${'$'}1 2>/dev/null")
        script("setprop", "if [ -n \"${'$'}IN_SU\" ] && [ ${if (suWorks) "1" else "0"} = 1 ]; then echo \"${'$'}2\" > /tmp/fakeandroid/props/${'$'}1; echo \"setprop ${'$'}* (su)\" >> ${log.path}; else echo 'Failed to set property: permission denied'; exit 1; fi")
        script("svc", "echo \"svc ${'$'}* su=${'$'}IN_SU\" >> ${log.path}; " +
            "if [ ${if (svcWorks) "1" else "0"} = 1 ] || { [ -n \"${'$'}IN_SU\" ] && [ ${if (suWorks) "1" else "0"} = 1 ]; }; " +
            "then echo 1${'$'}2 > /tmp/fakeandroid/props/sys.shutdown.requested; else echo 'Error: java.lang.SecurityException'; fi")
        script("reboot", "echo \"reboot ${'$'}* su=${'$'}IN_SU\" >> ${log.path}; if [ -n \"${'$'}IN_SU\" ] && [ ${if (suWorks) "1" else "0"} = 1 ]; then echo x > /tmp/fakeandroid/props/sys.powerctl; else echo 'reboot: permission denied'; exit 1; fi")
        script("su", "[ \"${'$'}1\" = -c ] && IN_SU=1 sh -c \"${'$'}2\"")
        File("/system").mkdirs()
        val marker = File("/system/build.prop").apply { writeText("") }
        try {
            val p = ProcessBuilder("sh", "-c", PiConnection.androidPowerScript(reboot)).apply {
                environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
                redirectErrorStream(true)
            }.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out + "\n--- calls ---\n" + log.readText()
        } finally {
            marker.delete(); File("/system").delete()
        }
    }

    @Test
    fun androidPowerFallsBackToSuAndStopsOnceStarted() {
        val out = runAndroidPower(reboot = false, svcWorks = false, suWorks = true)
        println("SU CASE:\n$out")
        assertTrue(out.contains("POWER_STARTED"))
        assertTrue(out.contains("svc power shutdown su=1"))
        assertTrue("nothing tried after it worked", !out.contains("reboot -p"))
    }

    @Test
    fun androidPowerPlainSvcWorks() {
        val out = runAndroidPower(reboot = true, svcWorks = true, suWorks = false)
        assertTrue(out.contains("POWER_STARTED"))
        assertEquals(1, Regex("svc power reboot").findAll(out.substringAfter("--- calls ---")).count())
    }

    @Test
    fun androidPowerReportsWhyWhenNothingWorks() {
        val out = runAndroidPower(reboot = true, svcWorks = false, suWorks = false)
        println("FAIL CASE:\n$out")
        assertTrue(out.contains("POWER_FAILED"))
        assertTrue(out.contains("user: uid="))
        assertTrue(out.contains("SecurityException"))
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
