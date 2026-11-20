package com.adamcarr.pixelapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Device list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiListScreen(tick: Int, onOpen: (String) -> Unit, onEdit: (String?) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember(tick) { PiStore.all(ctx) }
    val refresh = remember(tick) { PiStore.refreshSeconds(ctx) }
    Body(
        "Live stats from your Raspberry Pis over SSH. Nothing needs installing on the Pi, " +
            "but SSH must be on, and the phone must be on the same Wi-Fi."
    )
    devices.forEach { d ->
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(d.id) }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DeveloperBoard, contentDescription = null)
                Column(
                    Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                ) {
                    Text(d.name.ifBlank { d.host }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${d.user}@${d.host}" + if (d.port != 22) ":${d.port}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEdit(d.id) }) { Text("Edit") }
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
    Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (devices.isEmpty()) "Add your Pi" else "Add another Pi")
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Refresh every", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PiStore.REFRESH_CHOICES.forEach { sec ->
                    FilterChip(
                        selected = sec == refresh,
                        onClick = { PiStore.setRefreshSeconds(ctx, sec) },
                        label = { Text("${sec}s") },
                    )
                }
            }
            Text(
                "2 seconds is the default and recommended. Faster uses a little more battery " +
                    "on the phone and adds a tiny load on the Pi; slower makes the figures less live.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Add / edit

@Composable
fun PiEditScreen(deviceId: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val existing = remember(deviceId) { PiStore.get(ctx, deviceId) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "pi") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Name") }, placeholder = { Text("RetroPie Pi 5") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = host, onValueChange = { host = it; error = null },
        label = { Text("IP address") }, placeholder = { Text("192.168.1.50") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); error = null },
        label = { Text("SSH port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it; error = null },
        label = { Text("Username") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it; error = null },
        label = { Text("Password") },
        placeholder = { if (existing != null) Text("Leave blank to keep the saved one") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showPassword) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Body("The password is stored encrypted on this phone. It's also used for sudo when you reboot or shut down.")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            val portNum = port.toIntOrNull()
            error = when {
                host.isBlank() -> "Enter the Pi's IP address"
                user.isBlank() -> "Enter a username"
                portNum == null || portNum !in 1..65535 -> "Enter a port between 1 and 65535"
                existing == null && password.isEmpty() -> "Enter the password"
                else -> null
            }
            if (error == null) {
                val trimmedHost = host.trim()
                PiStore.save(
                    ctx,
                    PiDevice(
                        id = existing?.id ?: PiDevice.newId(),
                        name = name.trim(),
                        host = trimmedHost,
                        port = portNum ?: 22,
                        user = user.trim(),
                        encPassword = if (password.isEmpty()) existing!!.encPassword else Secret.encrypt(password),
                        // A different address is a different machine: forget the old identity.
                        hostKey = if (existing != null && existing.host == trimmedHost && existing.port == portNum) existing.hostKey else null,
                    )
                )
                onDone()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save") }
    if (existing != null) {
        OutlinedButton(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete this Pi") }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name.ifBlank { existing.host }}?") },
            text = { Text("Its saved address and password will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    PiStore.delete(ctx, existing.id)
                    confirmDelete = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Live monitor

@Composable
fun PiMonitorScreen(deviceId: String, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val device = remember(deviceId) { PiStore.get(ctx, deviceId) }
    if (device == null) {
        Body("This Pi has been deleted.")
        return
    }
    var stats by remember { mutableStateOf<PiStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyChanged by remember { mutableStateOf(false) }
    var trustNewKey by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var lastUpdate by remember { mutableLongStateOf(0L) }
    var connection by remember { mutableStateOf<PiConnection?>(null) }
    var powerMessage by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = reboot, false = shut down
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Polls every few seconds (2 by default), only while this screen is visible.
    LaunchedEffect(deviceId, attempt, trustNewKey) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.IO) {
                var conn: PiConnection? = null
                try {
                    val c = PiConnection(ctx, device, trustNewKey)
                    conn = c
                    c.connect()
                    connection = c
                    error = null
                    keyChanged = false
                    val parser = StatsParser()
                    while (isActive) {
                        val out = c.run(StatsParser.COMMAND)
                        stats = parser.parse(out, System.currentTimeMillis())
                        lastUpdate = System.currentTimeMillis()
                        delay(PiStore.refreshSeconds(ctx) * 1_000L)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HostKeyChangedException) {
                    keyChanged = true
                } catch (e: Exception) {
                    error = if (powerMessage != null) null else (e.message ?: "Lost connection to the Pi.")
                } finally {
                    connection = null
                    conn?.disconnect()
                }
            }
        }
    }

    powerMessage?.let {
        Card(Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(16.dp)) }
    }

    if (keyChanged) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This Pi's identity has changed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "That's normal if you've reinstalled the Pi's SD card, or a different device now " +
                        "has this IP address. Otherwise, don't connect. Your password hasn't been sent.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { trustNewKey = true; attempt++ }) { Text("Trust the new identity") }
            }
        }
    }

    error?.let { msg ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Can't reach ${device.name.ifBlank { device.host }}", style = MaterialTheme.typography.titleMedium)
                Text(msg, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { error = null; attempt++ }) { Text("Try again") }
                    OutlinedButton(onClick = onEdit) { Text("Edit details") }
                }
            }
        }
    }

    if (stats == null && error == null && !keyChanged && powerMessage == null) {
        Body("Connecting to ${device.host}…")
    }

    stats?.let { s -> StatsCards(s, lastUpdate, live = connection != null) }

    if (stats != null && powerMessage == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { confirm = true },
                enabled = connection != null,
                modifier = Modifier.weight(1f),
            ) { Text("Reboot") }
            Button(
                onClick = { confirm = false },
                enabled = connection != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Shut down") }
        }
        Body("EmulationStation is closed properly first, so game lists and favourites are saved.")
    }

    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit details") }

    confirm?.let { reboot ->
        val label = device.name.ifBlank { device.host }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (reboot) "Reboot $label?" else "Shut down $label?") },
            text = {
                Text(
                    if (reboot) "Any game that's running will be closed."
                    else "Any game that's running will be closed. You'll need to power the Pi back on yourself."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val c = connection ?: return@TextButton
                    powerMessage = if (reboot) "Rebooting $label. Come back in a minute and tap Try again."
                    else "Shutting down $label. It's safe to unplug once the green light stops flashing."
                    scope.launch(Dispatchers.IO) { c.power(reboot) }
                }) { Text(if (reboot) "Reboot" else "Shut down") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun StatsCards(s: PiStats, lastUpdate: Long, live: Boolean) {
    StatCard(s.model.ifBlank { "Raspberry Pi" }) {
        LabelValue("Hostname", s.hostname)
        LabelValue("Up for", formatUptime(s.uptimeSec))
        LabelValue(
            "Updated",
            (if (live) "" else "Stopped. ") +
                DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.UK).format(Date(lastUpdate)),
        )
    }

    StatCard("CPU  ${s.cpuTotal.toInt()}%") {
        Bar(s.cpuTotal / 100f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            s.cpuCores.forEachIndexed { i, v ->
                Column(Modifier.weight(1f)) {
                    Text("Core ${i + 1}: ${v.toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Bar(v / 100f)
                }
            }
        }
        s.freqMhz?.let { LabelValue("Clock", String.format(Locale.UK, "%.2f GHz", it / 1000f)) }
        LabelValue("Load (1, 5, 15 min)", s.load)
    }

    val temp = s.tempC
    StatCard(if (temp != null) String.format(Locale.UK, "Temperature  %.1f °C", temp) else "Temperature") {
        temp?.let {
            Bar(
                (it / 85f).coerceIn(0f, 1f),
                color = when {
                    it >= 80f -> MaterialTheme.colorScheme.error
                    it >= 70f -> Color(0xFFE6A100)
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        LabelValue("Fan", s.fanRpm?.let { "${String.format(Locale.UK, "%,d", it)} rpm" } ?: "No fan reading")
        throttleMessages(s.throttled).forEach { (text, bad) ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    StatCard("Memory") {
        LabelValue("RAM", "${bytes(s.memUsed)} of ${bytes(s.memTotal)}")
        Bar(fraction(s.memUsed, s.memTotal))
        if (s.swapTotal > 0) {
            LabelValue("Swap", "${bytes(s.swapUsed)} of ${bytes(s.swapTotal)}")
            Bar(fraction(s.swapUsed, s.swapTotal))
        }
    }

    StatCard("Storage") {
        LabelValue("Used", "${bytes(s.diskUsed)} of ${bytes(s.diskTotal)}")
        Bar(fraction(s.diskUsed, s.diskTotal))
    }

    StatCard("Network") {
        LabelValue("Download", s.netRxRate?.let { "${bytes(it.toLong())}/s" } ?: "…")
        LabelValue("Upload", s.netTxRate?.let { "${bytes(it.toLong())}/s" } ?: "…")
    }

    if (s.top.isNotEmpty()) {
        StatCard("Busiest processes") {
            Row(Modifier.fillMaxWidth()) {
                Text("CPU %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
                Text("Mem %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
                Text("Name", style = MaterialTheme.typography.labelMedium)
            }
            s.top.forEach { (cpu, mem, name) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(cpu, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                    Text(mem, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Bar(value: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0f, 1f) },
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    )
    Spacer(Modifier.height(2.dp))
}

private fun fraction(used: Long, total: Long): Float = if (total <= 0) 0f else used.toFloat() / total

fun bytes(b: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$b B" else String.format(Locale.UK, "%.1f %s", v, units[i])
}

fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3_600
    val m = (sec % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/** Decodes `vcgencmd get_throttled`. Returns (message, isProblem). */
fun throttleMessages(value: Int?): List<Pair<String, Boolean>> {
    if (value == null) return listOf("Throttling status isn't available on this device." to false)
    if (value == 0) return listOf("No under-voltage or throttling since boot." to false)
    val out = mutableListOf<Pair<String, Boolean>>()
    if (value and 0x1 != 0) out += "Under-voltage now: check the power supply." to true
    if (value and 0x2 != 0) out += "CPU speed capped now." to true
    if (value and 0x4 != 0) out += "Throttled now." to true
    if (value and 0x8 != 0) out += "Soft temperature limit reached now." to true
    if (value and 0x10000 != 0 && value and 0x1 == 0) out += "Under-voltage happened since boot." to true
    if (value and 0x20000 != 0 && value and 0x2 == 0) out += "CPU speed was capped since boot." to true
    if (value and 0x40000 != 0 && value and 0x4 == 0) out += "Throttling happened since boot." to true
    if (value and 0x80000 != 0 && value and 0x8 == 0) out += "Soft temperature limit reached since boot." to true
    return out
}
