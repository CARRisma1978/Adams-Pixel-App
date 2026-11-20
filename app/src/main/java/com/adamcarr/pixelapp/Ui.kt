package com.adamcarr.pixelapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Uses your wallpaper colours, like Pixel's own apps. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Changes whenever a setting changes or the screen comes back (e.g. after granting a permission). */
@Composable
fun rememberRefreshTick(): Int {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val prefs = Prefs.get(ctx)
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return tick
}

fun profileIcon(p: VolumeProfile): ImageVector = when (p) {
    VolumeProfile.NORMAL -> Icons.AutoMirrored.Filled.VolumeUp
    VolumeProfile.QUIET -> Icons.AutoMirrored.Filled.VolumeDown
    VolumeProfile.SILENT -> Icons.AutoMirrored.Filled.VolumeOff
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SwitchCard(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun EnableSwitch(key: String, checked: Boolean) {
    val ctx = LocalContext.current
    SwitchCard("Turn on", checked) { Features.set(ctx, key, it) }
}

/** Shows a card for each permission this feature still needs. */
@Composable
fun NeededPermissions(tick: Int, items: (Context) -> List<PermItem>) {
    val ctx = LocalContext.current
    val missing = remember(tick) { items(ctx).filter { !it.granted } }
    missing.forEach { PermissionCard(it) }
}

@Composable
fun PermissionCard(item: PermItem) {
    val ctx = LocalContext.current
    val granted = item.granted
    Card(
        Modifier.fillMaxWidth(),
        colors = if (granted) CardDefaults.cardColors() else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = if (granted) "Granted" else "Needed",
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(item.why, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.launch(item.intent) }) { Text("Grant") }
                    item.extraIntent?.let { extra ->
                        OutlinedButton(onClick = { ctx.launch(extra) }) { Text(item.extraLabel ?: "More") }
                    }
                }
            }
        }
    }
}

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
