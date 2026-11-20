package com.adamcarr.pixelapp

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- Volume pop-up (from the widget)

class VolumePopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { VolumePopup(onDone = { finish() }) } }
    }
}

@Composable
fun VolumePopup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val enabled = remember { Prefs.enabled(ctx, Prefs.VOLUME) }
    val current = remember { Volume.current(ctx) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
        ) {
            if (!enabled) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Volume profiles are turned off in the app.")
                    TextButton(onClick = {
                        ctx.startActivity(
                            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDone()
                    }) { Text("Open app") }
                }
            } else {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VolumeProfile.entries.forEach { p ->
                        val selected = p == current
                        Surface(
                            onClick = {
                                Volume.apply(ctx, p)
                                onDone()
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(92.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(profileIcon(p), contentDescription = null, modifier = Modifier.size(32.dp))
                                Text(p.label, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Write the Car NFC tag

class WriteTagActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private var status by mutableStateOf("Hold the Car tag against the back of your phone.")
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent {
            AppTheme {
                DetailScaffold("Write car tag", onBack = { finish() }) {
                    Icon(
                        Icons.Filled.Nfc,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc
        when {
            adapter == null -> status = "This phone doesn't have NFC."
            !adapter.isEnabled -> status = "Turn on NFC in Settings, then come back."
            else -> adapter.enableReaderMode(
                this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null,
            )
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfc?.disableReaderMode(this)
        } catch (e: Exception) {
            // not enabled; nothing to do
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createUri(Car.TAG_URI),
                NdefRecord.createApplicationRecord(packageName),
            )
        )
        val result = try {
            write(tag, message)
        } catch (e: Exception) {
            "Couldn't write the tag. Hold it still and try again."
        }
        runOnUiThread { status = result }
    }

    private fun write(tag: Tag, message: NdefMessage): String {
        val done = "Car tag written. Tap it any time to toggle Bluetooth."
        Ndef.get(tag)?.let { ndef ->
            ndef.connect()
            try {
                if (!ndef.isWritable) return "This tag is locked and can't be rewritten."
                if (ndef.maxSize < message.byteArrayLength) return "This tag is too small."
                ndef.writeNdefMessage(message)
                return done
            } finally {
                ndef.close()
            }
        }
        NdefFormatable.get(tag)?.let { formatable ->
            formatable.connect()
            try {
                formatable.format(message)
                return done
            } finally {
                formatable.close()
            }
        }
        return "This type of tag isn't supported."
    }
}
