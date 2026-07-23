package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.data.models.PoiType

@Composable
fun PoiSettingsDialog(
    enabledTypes: Set<PoiType>,
    onToggleType: (PoiType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("POIs auf der Karte") },
        text = {
            LazyColumn {
                items(PoiType.values()) { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = enabledTypes.contains(type),
                            onCheckedChange = { onToggleType(type) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${type.icon} ${type.label}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fertig") }
        }
    )
}
