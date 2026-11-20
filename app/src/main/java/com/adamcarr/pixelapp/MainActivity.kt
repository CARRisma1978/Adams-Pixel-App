package com.adamcarr.pixelapp

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CoreService.createChannel(this) // on Android 13+ this also triggers the notification prompt
        ServiceController.sync(this)
        setContent { AppTheme { AppRoot() } }
    }
}

enum class Screen(val title: String) {
    HOME("Adam's Pixel 9 Pro App"),
    CAMERA("Camera quiet"),
    CAR("Car tag"),
    KEEP_ON("Keep screen on"),
    WIRELESS("Wireless charging"),
    VOLUME("Volume profiles"),
    PI_LIST("Pi monitor"),
    PI_EDIT("Pi details"),
    PI_MONITOR("Pi monitor"),
    SETUP("Setup"),
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var piId by rememberSaveable { mutableStateOf<String?>(null) }
    val tick = rememberRefreshTick()
    val back = {
        screen = when (screen) {
            Screen.PI_EDIT, Screen.PI_MONITOR -> Screen.PI_LIST
            else -> Screen.HOME
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { back() }
    if (screen == Screen.HOME) {
        HomeScreen(tick) { screen = it }
        return
    }
    val title = when (screen) {
        Screen.PI_MONITOR -> PiStore.get(ctx, piId)?.let { it.name.ifBlank { it.host } } ?: screen.title
        Screen.PI_EDIT -> if (piId == null) "Add a Pi" else "Edit Pi"
        else -> screen.title
    }
    DetailScaffold(title, onBack = back) {
        when (screen) {
            Screen.CAMERA -> CameraSettings(tick)
            Screen.CAR -> CarSettings(tick)
            Screen.KEEP_ON -> KeepOnSettings(tick)
            Screen.WIRELESS -> WirelessSettings(tick)
            Screen.VOLUME -> VolumeSettings(tick)
            Screen.PI_LIST -> PiListScreen(
                tick,
                onOpen = { piId = it; screen = Screen.PI_MONITOR },
                onEdit = { piId = it; screen = Screen.PI_EDIT },
            )
            Screen.PI_EDIT -> PiEditScreen(piId) { screen = Screen.PI_LIST }
            Screen.PI_MONITOR -> piId?.let { id ->
                key(id) { PiMonitorScreen(id) { screen = Screen.PI_EDIT } }
            }
            Screen.SETUP -> SetupScreen(tick)
            Screen.HOME -> {}
        }
    }
}

// ---------------------------------------------------------------- Home (option A: simple list)

/** Menu order, saved when you drag a row. New features get added at the end. */
object MenuOrder {
    private const val KEY = "menu_order"
    private val DEFAULT = listOf("camera", "car", "keep_on", "wireless", "volume", "pi")

    fun load(ctx: Context): List<String> {
        val saved = Prefs.get(ctx).getString(KEY, null)?.split(",")?.filter { it in DEFAULT }.orEmpty()
        return saved.distinct() + DEFAULT.filter { it !in saved }
    }

    fun save(ctx: Context, order: List<String>) =
        Prefs.get(ctx).edit { putString(KEY, order.joinToString(",")) }
}

private data class HomeState(
    val camera: Boolean,
    val car: Boolean,
    val keepOn: Boolean,
    val keepOnActive: Boolean,
    val wireless: Boolean,
    val wirelessWifi: Boolean,
    val wirelessActive: Boolean,
    val volume: Boolean,
    val volumeCurrent: VolumeProfile?,
    val piCount: Int,
    val missing: Int,
) {
    companion object {
        fun load(ctx: Context) = HomeState(
            camera = Prefs.enabled(ctx, Prefs.CAMERA),
            car = Prefs.enabled(ctx, Prefs.CAR),
            keepOn = Prefs.enabled(ctx, Prefs.KEEP_ON),
            keepOnActive = KeepScreenOn.isActive(ctx),
            wireless = Prefs.enabled(ctx, Prefs.WIRELESS),
            wirelessWifi = Prefs.enabled(ctx, Prefs.WIRELESS_WIFI),
            wirelessActive = Wireless.isActive(ctx),
            volume = Prefs.enabled(ctx, Prefs.VOLUME),
            volumeCurrent = Volume.current(ctx),
            piCount = PiStore.all(ctx).size,
            missing = Permissions.missingCount(ctx),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tick: Int, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val s = remember(tick) { HomeState.load(ctx) }

    // Drag to reorder: long-press a row, then drag it up or down.
    var order by remember { mutableStateOf(MenuOrder.load(ctx)) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var startTop by remember { mutableFloatStateOf(0f) }
    var totalDrag by remember { mutableFloatStateOf(0f) }

    fun topOf(id: String, list: List<String>): Float {
        var y = 0f
        for (x in list) {
            if (x == id) return y
            y += heights[x] ?: 0
        }
        return y
    }

    fun endDrag() {
        if (draggingId != null) MenuOrder.save(ctx, order)
        draggingId = null
        totalDrag = 0f
    }

    Scaffold(topBar = { TopAppBar(title = { Text(Screen.HOME.title) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            var y = 0f
                            val hit = order.firstOrNull { id ->
                                val h = heights[id] ?: 0
                                val inside = pos.y >= y && pos.y < y + h
                                y += h
                                inside
                            }
                            if (hit != null) {
                                draggingId = hit
                                startTop = topOf(hit, order)
                                totalDrag = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = { endDrag() },
                        onDragCancel = { endDrag() },
                        onDrag = { change, amount ->
                            val id = draggingId ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val h = (heights[id] ?: 0).toFloat()
                            val listHeight = order.sumOf { heights[it] ?: 0 }.toFloat()
                            totalDrag = (totalDrag + amount.y).coerceIn(-startTop, listHeight - h - startTop)
                            val center = startTop + totalDrag + h / 2f
                            while (true) {
                                val list = order
                                val idx = list.indexOf(id)
                                if (idx < list.lastIndex) {
                                    val next = list[idx + 1]
                                    val nextCenter = topOf(next, list) + (heights[next] ?: 0) / 2f
                                    if (center >= nextCenter) {
                                        order = list.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                        continue
                                    }
                                }
                                if (idx > 0) {
                                    val prev = list[idx - 1]
                                    val prevCenter = topOf(prev, list) + (heights[prev] ?: 0) / 2f
                                    if (center <= prevCenter) {
                                        order = list.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                        continue
                                    }
                                }
                                break
                            }
                        },
                    )
                }
            ) {
                order.forEach { id ->
                    key(id) {
                        val dragging = draggingId == id
                        Box(
                            Modifier
                                .onSizeChanged { heights[id] = it.height }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) startTop + totalDrag - topOf(id, order) else 0f
                                    shadowElevation = if (dragging) 8.dp.toPx() else 0f
                                }
                        ) {
                            MenuRow(id, s, dragging, onOpen)
                        }
                    }
                }
            }
            ListItem(
                headlineContent = {
                    Text(
                        when (s.missing) {
                            0 -> "All permissions granted"
                            1 -> "1 permission needed"
                            else -> "${s.missing} permissions needed"
                        }
                    )
                },
                supportingContent = { Text("Tap to review. Long-press a row above to move it.") },
                leadingContent = {
                    Icon(
                        if (s.missing == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (s.missing == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable { onOpen(Screen.SETUP) },
            )
        }
    }
}

@Composable
private fun MenuRow(id: String, s: HomeState, dragging: Boolean, onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    when (id) {
        "camera" -> FeatureRow(
            Icons.Filled.PhotoCamera, "Camera quiet",
            "Do Not Disturb while the camera is open",
            s.camera, { Features.set(ctx, Prefs.CAMERA, it) }, dragging,
        ) { onOpen(Screen.CAMERA) }
        "car" -> FeatureRow(
            Icons.Filled.DirectionsCar, "Car tag",
            "NFC tag toggles Bluetooth",
            s.car, { Features.set(ctx, Prefs.CAR, it) }, dragging,
        ) { onOpen(Screen.CAR) }
        "keep_on" -> FeatureRow(
            Icons.Filled.StayCurrentPortrait, "Keep screen on",
            if (s.keepOn && s.keepOnActive) "On now. Tap the tile or lock to stop" else "Quick Settings tile",
            s.keepOn, { Features.set(ctx, Prefs.KEEP_ON, it) }, dragging,
        ) { onOpen(Screen.KEEP_ON) }
        "wireless" -> FeatureRow(
            Icons.Filled.BatteryChargingFull, "Wireless charging",
            buildString {
                append(if (s.wireless && s.wirelessActive) "Charging now, Do Not Disturb on" else "Do Not Disturb on the charger")
                if (s.wirelessWifi) append(", Wi-Fi off")
            },
            s.wireless, { Features.set(ctx, Prefs.WIRELESS, it) }, dragging,
        ) { onOpen(Screen.WIRELESS) }
        "volume" -> FeatureRow(
            profileIcon(s.volumeCurrent ?: VolumeProfile.NORMAL), "Volume profiles",
            "Home screen: ${s.volumeCurrent?.label ?: "not set yet"}",
            s.volume, { Features.set(ctx, Prefs.VOLUME, it) }, dragging,
        ) { onOpen(Screen.VOLUME) }
        "pi" -> FeatureRow(
            Icons.Filled.DeveloperBoard, "Pi monitor",
            when (s.piCount) {
                0 -> "Add your Raspberry Pi"
                1 -> "1 Pi"
                else -> "${s.piCount} Pis"
            },
            null, null, dragging,
        ) { onOpen(Screen.PI_LIST) }
    }
}

@Composable
fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    dragging: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checked != null) Switch(checked = checked, onCheckedChange = onCheckedChange)
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Long-press to move",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

// ---------------------------------------------------------------- Camera quiet

@Composable
fun CameraSettings(tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { Prefs.enabled(ctx, Prefs.CAMERA) }
    EnableSwitch(Prefs.CAMERA, on)
    Body(
        "When the camera app comes to the front, Do Not Disturb switches to Priority only. " +
            "It goes back to how it was when you leave the camera. Who can still get through is " +
            "set in Settings > Modes > Do Not Disturb."
    )
    var pkg by remember { mutableStateOf(Prefs.cameraPackage(ctx)) }
    OutlinedTextField(
        value = pkg,
        onValueChange = {
            pkg = it
            Prefs.get(ctx).edit { putString(Prefs.CAMERA_PACKAGE, it.trim()) }
        },
        label = { Text("Camera app package") },
        supportingText = { Text("Leave as is for Pixel Camera") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Body("The app checks which app is in front about once a second while the screen is on, so it can take up to a second to kick in.")
    NeededPermissions(tick) {
        listOf(Permissions.dnd(it), Permissions.usageAccess(it), Permissions.notifications(it), Permissions.battery(it))
    }
}

// ---------------------------------------------------------------- Car tag

@Composable
fun CarSettings(tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { Prefs.enabled(ctx, Prefs.CAR) }
    EnableSwitch(Prefs.CAR, on)
    Body(
        "Tap your phone on the Car tag to toggle Bluetooth: on if it's off, off if it's on. " +
            "Android only reads NFC tags while the screen is on and unlocked."
    )
    Button(
        onClick = { ctx.startActivity(Intent(ctx, WriteTagActivity::class.java)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Write car tag") }
    Body("Do this once. Writing replaces what's on the tag now, including the old Tasker setup.")
    OutlinedButton(
        onClick = { Car.toggleBluetooth(ctx) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Toggle Bluetooth now (test)") }
    NeededPermissions(tick) { listOf(Permissions.nfc(it)) }
}

// ---------------------------------------------------------------- Keep screen on

@Composable
fun KeepOnSettings(tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { Prefs.enabled(ctx, Prefs.KEEP_ON) }
    val active = remember(tick) { KeepScreenOn.isActive(ctx) }
    EnableSwitch(Prefs.KEEP_ON, on)
    Body(
        "Adds a Keep screen on tile to Quick Settings. Tap it to stop the screen timing out. " +
            "Tap it again, or lock the phone, to go back to your normal timeout."
    )
    if (Build.VERSION.SDK_INT >= 33) {
        OutlinedButton(
            onClick = { requestAddTile(ctx) },
            enabled = on,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add tile to Quick Settings") }
    } else {
        Body("To add the tile: swipe down twice, tap the pencil, and drag Keep screen on into your tiles.")
    }
    Button(
        onClick = {
            if (active) {
                KeepScreenOn.turnOff(ctx, announce = true)
            } else if (!KeepScreenOn.turnOn(ctx)) {
                ctx.toast("Allow Modify system settings first")
            }
            ServiceController.sync(ctx)
        },
        enabled = on,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (active) "Turn off now" else "Turn on now") }
    NeededPermissions(tick) { listOf(Permissions.writeSettings(it), Permissions.notifications(it)) }
}

private fun requestAddTile(ctx: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = ctx.getSystemService(StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        ComponentName(ctx, KeepScreenTileService::class.java),
        ctx.getString(R.string.tile_label),
        Icon.createWithResource(ctx, R.drawable.ic_tile),
        ctx.mainExecutor,
    ) { }
}

// ---------------------------------------------------------------- Wireless charging

@Composable
fun WirelessSettings(tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { Prefs.enabled(ctx, Prefs.WIRELESS) }
    val wifi = remember(tick) { Prefs.enabled(ctx, Prefs.WIRELESS_WIFI) }
    val active = remember(tick) { Wireless.isActive(ctx) }
    EnableSwitch(Prefs.WIRELESS, on)
    Body(
        "While the phone charges wirelessly, Do Not Disturb switches to Priority only. " +
            "When you take it off the charger, it goes back to how it was."
    )
    Body(
        "Some chargers stop when the battery is full, which looks like the phone was taken off. " +
            "If that happens while the phone is locked, Do Not Disturb stays on until you unlock " +
            "it, your alarm goes off, or the battery drops below 90%. Once your alarm has gone off, " +
            "lifting the phone turns Do Not Disturb off straight away. Short dropouts under 30 " +
            "seconds are ignored."
    )
    SwitchCard("Also turn Wi-Fi off while charging", wifi) {
        Prefs.setEnabled(ctx, Prefs.WIRELESS_WIFI, it)
    }
    Body("Wi-Fi comes back on when you take the phone off the charger, but only if this app turned it off.")
    val screenOff = remember(tick) { Wireless.quickOffEnabled(ctx) }
    SwitchCard("Turn the screen off 10 seconds after putting it on the charger", screenOff) {
        Prefs.setEnabled(ctx, Prefs.WIRELESS_SCREEN_OFF, it)
    }
    Body("Your normal screen timeout comes back as soon as the screen goes off, so using the phone on the charger afterwards isn't affected. Skipped while Keep screen on is active.")
    if (on) Body(if (active) "Status: on the charger, Do Not Disturb on." else "Status: not on a wireless charger.")
    NeededPermissions(tick) {
        listOf(Permissions.dnd(it), Permissions.writeSettings(it), Permissions.notifications(it), Permissions.battery(it))
    }
    val events = remember(tick) { Wireless.recentActivity(ctx) }
    if (events.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.UK) }
                events.forEach { (time, message) ->
                    Row {
                        Text(
                            fmt.format(Date(time)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp),
                        )
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Volume profiles

@Composable
fun VolumeSettings(tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { Prefs.enabled(ctx, Prefs.VOLUME) }
    val current = remember(tick) { Volume.current(ctx) }
    var pickTarget by remember { mutableStateOf<Pair<VolumeProfile, Int>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pickTarget
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            Volume.setSound(ctx, target.first, target.second, uri)
        }
        pickTarget = null
    }

    EnableSwitch(Prefs.VOLUME, on)
    Body(
        "Pick a profile from the home-screen icon or here. The icon and its name change to show " +
            "the current profile. Normal and Quiet can also set your ringtone and notification sound."
    )
    if (on && remember { VolumeShortcut.canPin(ctx) }) {
        OutlinedButton(
            onClick = { VolumeShortcut.requestPin(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add to home screen") }
    }
    VolumeProfile.entries.forEach { profile ->
        ProfileCard(
            profile = profile,
            isCurrent = profile == current,
            tick = tick,
            onPick = { type ->
                pickTarget = profile to type
                picker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Volume.sound(ctx, profile, type))
                )
            },
            onApply = { Volume.apply(ctx, profile) },
        )
    }
    NeededPermissions(tick) { listOf(Permissions.dnd(it), Permissions.writeSettings(it)) }
}

@Composable
fun ProfileCard(
    profile: VolumeProfile,
    isCurrent: Boolean,
    tick: Int,
    onPick: (Int) -> Unit,
    onApply: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(profileIcon(profile), contentDescription = null)
                Text(
                    if (isCurrent) "${profile.label} (current)" else profile.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                )
                FilledTonalButton(onClick = onApply) { Text("Use now") }
            }
            Volume.STREAM_NAMES.forEachIndexed { i, name -> VolumeSlider(profile, i, name) }
            if (profile.locked) {
                Text(
                    "Silent is fixed: alarms stay on, everything else is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (profile.hasSounds) Volume.SOUND_TYPES.forEach { type ->
                val uri = remember(tick) { Volume.sound(ctx, profile, type) }
                val title = remember(uri) { Volume.soundTitle(ctx, uri) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (type == RingtoneManager.TYPE_RINGTONE) "Ringtone" else "Notification sound",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onPick(type) }) { Text("Choose") }
                    if (uri != null) {
                        TextButton(onClick = { Volume.setSound(ctx, profile, type, null) }) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
fun VolumeSlider(profile: VolumeProfile, index: Int, name: String) {
    val ctx = LocalContext.current
    val max = remember { Volume.maxLevel(ctx, index).coerceAtLeast(1) }
    var value by remember(profile, index) {
        mutableFloatStateOf(Volume.level(ctx, profile, index).coerceIn(0, max).toFloat())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { Volume.setLevel(ctx, profile, index, value.roundToInt()) },
            enabled = !profile.locked,
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f),
        )
        Text(
            value.roundToInt().toString(),
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

// ---------------------------------------------------------------- Setup

@Composable
fun SetupScreen(tick: Int) {
    val ctx = LocalContext.current
    val items = remember(tick) { Permissions.all(ctx) }
    Body("Grant these once. Each one is only used by the features it mentions.")
    items.forEach { PermissionCard(it) }
}
