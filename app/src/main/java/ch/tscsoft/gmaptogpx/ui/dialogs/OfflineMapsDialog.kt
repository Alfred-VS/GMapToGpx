package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import java.util.*

@Composable
fun OfflineMapsDialog(viewModel: MapViewModel, onDismiss: () -> Unit) {
    var cacheSize by remember { mutableLongStateOf(viewModel.getCacheSize()) }
    var showConfirmClear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline Karten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Die App speichert automatisch Kacheln der angezeigten Karten. " +
                            "Zusätzlich können Kacheln für eine Route vorab geladen werden.",
                    style = MaterialTheme.typography.bodySmall
                )

                ListItem(
                    headlineContent = { Text("Belegter Speicher") },
                    supportingContent = {
                        val sizeMb = String.format(Locale.US, "%.1f MB", cacheSize / (1024.0 * 1024.0))
                        Text(sizeMb)
                    },
                    leadingContent = { Icon(Icons.Default.Storage, null) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = {
            TextButton(
                onClick = { showConfirmClear = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Cache löschen")
            }
        }
    )

    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            title = { Text("Cache löschen?") },
            text = { Text("Alle heruntergeladenen Kartenkacheln werden unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        cacheSize = viewModel.getCacheSize()
                        showConfirmClear = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = false }) { Text("Abbrechen") }
            }
        )
    }
}
