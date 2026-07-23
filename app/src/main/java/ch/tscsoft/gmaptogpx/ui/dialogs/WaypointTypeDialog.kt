package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.data.models.SearchSuggestion

@Composable
fun WaypointTypeDialog(
    suggestion: SearchSuggestion,
    onSetStart: () -> Unit,
    onSetEnd: () -> Unit,
    onAddIntermediate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(suggestion.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(suggestion.description, style = MaterialTheme.typography.bodySmall)
                Text("Wie möchtest du diesen Ort hinzufügen?", style = MaterialTheme.typography.labelMedium)
                
                Button(
                    onClick = onSetStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MyLocation, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Als Startpunkt")
                }
                
                Button(
                    onClick = onSetEnd,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Flag, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Als Zielpunkt")
                }
                
                OutlinedButton(
                    onClick = onAddIntermediate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Place, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Als Zwischenstopp")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
