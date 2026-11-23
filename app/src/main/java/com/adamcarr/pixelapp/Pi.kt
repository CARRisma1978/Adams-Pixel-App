package com.adamcarr.pixelapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ---------------------------------------------------------------- Saved devices

data class PiDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val encPassword: String,
    val hostKey: String?, // remembered on first connect
    val useKey: Boolean = false, // sign in with an SSH key instead of a password
    val encPrivateKey: String = "",
    val encPassphrase: String = "",
    val publicKey: String = "", // shown so it can be added to the Pi
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("pw", encPassword).put("hostKey", hostKey ?: "")
        .put("useKey", useKey).put("key", encPrivateKey).put("keyPass", encPassphrase)
        .put("pubKey", publicKey)

    companion object {
        fun fromJson(o: JSONObject) = PiDevice(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            encPassword = o.optString("pw"),
            hostKey = o.optString("hostKey").ifEmpty { null },
            useKey = o.optBoolean("useKey", false),
            encPrivateKey = o.optString("key"),
            encPassphrase = o.optString("keyPass"),
            publicKey = o.optString("pubKey"),
        )

        fun newId(): String = UUID.randomUUID().toString()
    }
}

object PiStore {
    private const val KEY = "pi_devices"
    private const val KEY_REFRESH = "pi_refresh_seconds"
    const val DEFAULT_REFRESH = 2
    val REFRESH_CHOICES = listOf(1, 2, 5, 10, 30)

    fun refreshSeconds(ctx: Context): Int = Prefs.get(ctx).getInt(KEY_REFRESH, DEFAULT_REFRESH)

    fun setRefreshSeconds(ctx: Context, seconds: Int) = Prefs.get(ctx).edit { putInt(KEY_REFRESH, seconds) }

    fun all(ctx: Context): List<PiDevice> {
        val raw = Prefs.get(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PiDevice.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(ctx: Context, id: String?): PiDevice? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, device: PiDevice) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == device.id }
        if (i >= 0) list[i] = device else list.add(device)
        write(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) = write(ctx, all(ctx).filter { it.id != id })

    @Synchronized
    fun setHostKey(ctx: Context, id: String, key: String?) {
        get(ctx, id)?.let { save(ctx, it.copy(hostKey = key)) }
    }

    private fun write(ctx: Context, list: List<PiDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.get(ctx).edit { putString(KEY, arr.toString()) }
    }
}

/** Passwords are encrypted with a key held in the phone's secure hardware. */
object Secret {
    private const val ALIAS = "pi_passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
        String(c.doFinal(unb64(ct)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

// ---------------------------------------------------------------- SSH keys

/** A private key in OpenSSH/PEM text form, plus the one-line public key to put on the Pi. */
data class SshKey(val privateKey: String, val publicKey: String, val type: String)

object SshKeys {
    /**
     * Creates a new key for this phone. ECDSA (P-256): every SSH server accepts it, and
     * JSch can save it in a standard format (it can't write Ed25519 private keys).
     */
    fun generate(comment: String): SshKey {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8.name()).trim()
        kp.dispose()
        return SshKey(prv, pub, "ecdsa")
    }

    /**
     * Reads a private key file. Throws with a friendly message if it isn't one.
     * [passphrase] is only needed if the key is protected by one.
     */
    fun load(text: String, passphrase: String?): SshKey {
        if (!text.contains("PRIVATE KEY")) {
            throw IllegalArgumentException(
                if (text.trimStart().startsWith("ssh-")) "That's a public key. Choose the private key file instead."
                else "That file isn't an SSH private key."
            )
        }
        val kp = try {
            KeyPair.load(JSch(), text.toByteArray(Charsets.UTF_8), null)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read that key file (${e.message ?: "unknown format"}).")
        }
        if (kp.isEncrypted) {
            if (passphrase.isNullOrEmpty()) throw IllegalArgumentException("This key is protected. Enter its passphrase.")
            if (!kp.decrypt(passphrase)) throw IllegalArgumentException("Wrong passphrase for this key.")
        }
        val blob = kp.publicKeyBlob
        val type = kp.keyTypeString ?: "key"
        val pub = if (blob != null) "$type ${Base64.encodeToString(blob, Base64.NO_WRAP)} phone" else ""
        kp.dispose()
        return SshKey(text, pub, type.removePrefix("ssh-"))
    }
}

// ---------------------------------------------------------------- Stats

data class PiStats(
    val android: Boolean = false, // e.g. LineageOS rather than Raspberry Pi OS
    val osName: String = "",
    val model: String = "",
    val hostname: String = "",
    val uptimeSec: Long = 0,
    val load: String = "",
    val cpuTotal: Float = 0f,
    val cpuCores: List<Float> = emptyList(),
    val freqMhz: Int? = null,
    val tempC: Float? = null,
    val fanRpm: Int? = null,
    val throttled: Int? = null,
    val memTotal: Long = 0,
    val memUsed: Long = 0,
    val swapTotal: Long = 0,
    val swapUsed: Long = 0,
    val diskTotal: Long = 0,
    val diskUsed: Long = 0,
    val netRxRate: Double? = null, // bytes per second
    val netTxRate: Double? = null,
    val top: List<Triple<String, String, String>> = emptyList(), // cpu %, mem %, name
)

/** Raw counters kept between polls so we can work out CPU % and network speed. */
class StatsParser {
    private var prevCpu: Map<String, LongArray> = emptyMap()
    private var prevNet: Pair<Long, Long>? = null
    private var prevTime = 0L

    fun parse(output: String, now: Long): PiStats {
        val sections = mutableMapOf<String, MutableList<String>>()
        var current = ""
        output.lineSequence().forEach { line ->
            if (line.startsWith("@")) {
                current = line.substring(1).trim()
                sections[current] = mutableListOf()
            } else if (line.isNotBlank()) {
                sections.getOrPut(current) { mutableListOf() }.add(line.trim())
            }
        }
        fun sec(name: String) = sections[name].orEmpty()

        // CPU: "cpu  user nice system idle iowait irq softirq steal ..."
        val cpuNow = sec("stat").associate { line ->
            val parts = line.split(Regex("\\s+"))
            parts[0] to parts.drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
        }
        fun usage(name: String): Float {
            val a = prevCpu[name] ?: return 0f
            val b = cpuNow[name] ?: return 0f
            if (a.size < 4 || b.size < 4) return 0f
            val idle = { x: LongArray -> x[3] + (x.getOrNull(4) ?: 0L) }
            val total = { x: LongArray -> x.sum() }
            val dt = total(b) - total(a)
            val di = idle(b) - idle(a)
            return if (dt <= 0) 0f else ((dt - di).toFloat() / dt * 100f).coerceIn(0f, 100f)
        }
        val cores = cpuNow.keys.filter { it != "cpu" }.sortedBy { it.removePrefix("cpu").toIntOrNull() ?: 0 }
        val cpuTotal = usage("cpu")
        val cpuCores = cores.map { usage(it) }

        val mem = sec("mem").associate { line ->
            val p = line.split(Regex("\\s+"))
            p[0].removeSuffix(":") to ((p.getOrNull(1)?.toLongOrNull() ?: 0L) * 1024L)
        }
        val memTotal = mem["MemTotal"] ?: 0L
        val swapTotal = mem["SwapTotal"] ?: 0L

        val disk = sec("disk").firstOrNull()?.split(Regex("\\s+"))
        val diskTotal = (disk?.getOrNull(1)?.toLongOrNull() ?: 0L) * 1024L
        val diskUsed = (disk?.getOrNull(2)?.toLongOrNull() ?: 0L) * 1024L

        var rx = 0L
        var tx = 0L
        sec("net").forEach { line ->
            val name = line.substringBefore(":").trim()
            if (name == "lo") return@forEach
            val p = line.substringAfter(":").trim().split(Regex("\\s+"))
            rx += p.getOrNull(0)?.toLongOrNull() ?: 0L
            tx += p.getOrNull(8)?.toLongOrNull() ?: 0L
        }
        val seconds = (now - prevTime) / 1000.0
        val prev = prevNet
        val rxRate = if (prev != null && seconds > 0) ((rx - prev.first) / seconds).coerceAtLeast(0.0) else null
        val txRate = if (prev != null && seconds > 0) ((tx - prev.second) / seconds).coerceAtLeast(0.0) else null

        prevCpu = cpuNow
        prevNet = rx to tx
        prevTime = now

        val throttled = sec("throttled").firstOrNull()
            ?.substringAfter("=", "")?.removePrefix("0x")?.toIntOrNull(16)

        val os = sec("os")
        return PiStats(
            android = os.firstOrNull() == "android",
            osName = os.getOrNull(1).orEmpty(),
            model = sec("model").firstOrNull().orEmpty(),
            hostname = sec("host").firstOrNull().orEmpty(),
            uptimeSec = sec("uptime").firstOrNull()?.split(" ")?.firstOrNull()?.toDoubleOrNull()?.toLong() ?: 0L,
            load = sec("load").firstOrNull()?.split(" ")?.take(3)?.joinToString("  ").orEmpty(),
            cpuTotal = cpuTotal,
            cpuCores = cpuCores,
            freqMhz = sec("freq").firstOrNull()?.toLongOrNull()?.let { (it / 1000).toInt() },
            tempC = sec("temp").firstOrNull()?.toFloatOrNull()?.let { it / 1000f },
            fanRpm = sec("fan").firstOrNull()?.toIntOrNull(),
            throttled = throttled,
            memTotal = memTotal,
            memUsed = (memTotal - (mem["MemAvailable"] ?: 0L)).coerceAtLeast(0L),
            swapTotal = swapTotal,
            swapUsed = (swapTotal - (mem["SwapFree"] ?: 0L)).coerceAtLeast(0L),
            diskTotal = diskTotal,
            diskUsed = diskUsed,
            netRxRate = rxRate,
            netTxRate = txRate,
            top = sec("top").mapNotNull { line ->
                val p = line.split(Regex("\\s+"), limit = 3)
                if (p.size == 3) Triple(p[0], p[1], p[2]) else null
            },
        )
    }

    companion object {
        /** One command that prints every figure, split into "@section" blocks. */
        // Works on Raspberry Pi OS and on Android (LineageOS), whose commands come from toybox.
        const val COMMAND = """
echo @os; if [ -f /system/build.prop ]; then echo android; getprop ro.lineage.version 2>/dev/null || getprop ro.build.version.release; fi
echo @stat; grep '^cpu' /proc/stat
echo @mem; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo
echo @load; cat /proc/loadavg
echo @uptime; cat /proc/uptime
echo @temp; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null
echo @freq; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null
echo @throttled; vcgencmd get_throttled 2>/dev/null
echo @fan; cat /sys/devices/platform/cooling_fan/hwmon/*/fan1_input 2>/dev/null
echo @disk; if [ -f /system/build.prop ]; then df -kP /data; else df -kP /; fi | tail -n 1
echo @net; tail -n +3 /proc/net/dev
echo @model; cat /proc/device-tree/model 2>/dev/null | tr -d '\0'; echo
echo @host; h=""; [ -f /system/build.prop ] && h=$(getprop net.hostname 2>/dev/null); [ -n "${'$'}h" ] && echo "${'$'}h" || hostname
echo @top; if [ -f /system/build.prop ]; then ps -A -o %CPU,%MEM,NAME -k -%CPU | sed -n '2,6p'; else ps -eo pcpu,pmem,comm --sort=-pcpu --no-headers | head -n 5; fi
"""
    }
}

// ---------------------------------------------------------------- SSH connection

class HostKeyChangedException : Exception("The Pi's identity has changed")
class PiAuthException(message: String) : Exception(message)

/**
 * One SSH session to a Pi. Remembers the Pi's host key the first time, and refuses to
 * connect (before sending the password) if it ever changes, unless you choose to trust it.
 */
class PiConnection(
    private val ctx: Context,
    private val device: PiDevice,
    private val trustNewKey: Boolean,
    // With a key, the password is optional (only used for sudo on Raspberry Pi OS).
    private val password: String = when {
        device.useKey && device.encPassword.isEmpty() -> ""
        else -> Secret.decrypt(device.encPassword)
            ?: throw PiAuthException("Password couldn't be read. Edit the Pi's details and enter it again.")
    },
    private val privateKey: String? = if (!device.useKey) null else Secret.decrypt(device.encPrivateKey)
        ?: throw PiAuthException("No SSH key saved for this Pi. Edit its details and import or create one."),
    private val passphrase: String? = device.encPassphrase.takeIf { it.isNotEmpty() }?.let { Secret.decrypt(it) },
) {
    private var session: Session? = null

    private inner class Repo : HostKeyRepository {
        var lastCheck = HostKeyRepository.OK
        override fun check(host: String?, key: ByteArray?): Int {
            val storedText = PiStore.get(ctx, device.id)?.hostKey
            // A stored key that can't be read counts as "changed", never as "first time".
            val stored = storedText?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
            }
            lastCheck = when {
                stored == null -> HostKeyRepository.NOT_INCLUDED
                key != null && stored.contentEquals(key) -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
            return lastCheck
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {
            PiStore.setHostKey(ctx, device.id, hostkey?.key) // HostKey.key is already base64
        }

        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "pi-monitor"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private inner class Prompts(private val repo: Repo) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = this@PiConnection.password
        override fun promptPassword(message: String?) = false // don't retry a rejected password
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?): Boolean =
            repo.lastCheck == HostKeyRepository.NOT_INCLUDED || trustNewKey
        override fun showMessage(message: String?) {}
        private var kbdTries = 0
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? {
            if (kbdTries++ > 0) return null // answered once already; give up rather than loop
            return Array(prompt?.size ?: 0) { this@PiConnection.password }
        }
    }

    fun connect() {
        val jsch = JSch()
        val repo = Repo()
        jsch.hostKeyRepository = repo
        if (privateKey != null) {
            try {
                jsch.addIdentity("phone-key", privateKey.toByteArray(Charsets.UTF_8), null, passphrase?.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                throw PiAuthException("This Pi's SSH key couldn't be used (${e.message}). Edit its details and import it again.")
            }
        }
        val s = jsch.getSession(device.user, device.host, device.port)
        s.userInfo = Prompts(repo)
        s.setConfig("StrictHostKeyChecking", "ask")
        if (privateKey != null) {
            s.setConfig("PreferredAuthentications", "publickey")
        } else {
            s.setPassword(password)
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        }
        s.timeout = 8_000
        try {
            s.connect(8_000)
        } catch (e: JSchChangedHostKeyException) {
            throw HostKeyChangedException()
        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = e.message.orEmpty()
            throw when {
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ||
                    msg.contains("authentication failures", true) ->
                    PiAuthException(
                        if (privateKey != null) "The Pi didn't accept this key for user '${device.user}'. Check its public key has been added to the Pi."
                        else "Username or password was rejected."
                    )
                msg.contains("timeout", true) || msg.contains("timed out", true) ->
                    PiAuthException("No reply from ${device.host}. Is the phone on the same Wi-Fi as the Pi?")
                msg.contains("refused", true) ->
                    PiAuthException("Connection refused. Is SSH turned on? (sudo raspi-config > Interface Options > SSH)")
                msg.contains("UnknownHost", true) || msg.contains("unknown host", true) ->
                    PiAuthException("Couldn't find ${device.host}. Check the IP address.")
                else -> PiAuthException(msg.ifEmpty { "Couldn't connect." })
            }
        }
        session = s
    }

    /** Runs a command and returns its output. [stdin] is sent first (used for the sudo password). */
    fun run(command: String, stdin: String? = null, timeoutMs: Long = 10_000): String {
        val s = session ?: throw IllegalStateException("Not connected")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val out = ByteArrayOutputStream()
        val input = ch.inputStream
        val writer = ch.outputStream
        ch.connect(5_000)
        if (stdin != null) {
            writer.write(stdin.toByteArray())
            writer.flush()
        }
        val buf = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (input.available() > 0) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (ch.isClosed && input.available() <= 0) break
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(50)
        }
        ch.disconnect()
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Reboot or shut down. If EmulationStation is running, it's asked to quit first using
     * RetroPie's own flag file, so game lists and favourites are saved; RetroPie then
     * reboots or powers off itself. If it hasn't gone within 15 seconds (e.g. a game is
     * running), the Pi is told directly. On Android (LineageOS) it asks Android to
     * reboot or shut down cleanly instead.
     */
    fun power(reboot: Boolean) {
        val flag = if (reboot) "/tmp/es-sysrestart" else "/tmp/es-shutdown"
        val action = if (reboot) "reboot" else "poweroff"
        val androidAction = if (reboot) "reboot" else "shutdown"
        val rebootFlag = if (reboot) "" else "-p"
        val script = """
if [ -f /system/build.prop ]; then
  # Android (e.g. LineageOS): ask Android to shut down cleanly, as root.
  if [ "$(id -u)" = 0 ]; then svc power $androidAction || reboot $rebootFlag
  else su -c "svc power $androidAction || reboot $rebootFlag"; fi
  exit 0
fi
if pgrep -f '[e]mulationstation/emulationstation' >/dev/null; then
  touch $flag
  pkill -TERM -f '[e]mulationstation/emulationstation'
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    pgrep -f '[e]mulationstation/emulationstation' >/dev/null || break
    sleep 1
  done
  sleep 3
fi
sudo -S -p '' systemctl $action
"""
        try {
            run(script, stdin = password + "\n", timeoutMs = 30_000)
        } catch (e: Exception) {
            // The connection usually drops as the Pi goes down; that's expected.
        }
    }

    fun disconnect() {
        try {
            session?.disconnect()
        } catch (e: Exception) {
        }
        session = null
    }
}
