package ch.tscsoft.gmaptogpx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.data.models.Waypoint
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointList(
    waypoints: List<Waypoint>,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Wegpunkte", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClearAll) {
                Text("Alle löschen", color = MaterialTheme.colorScheme.error)
            }
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(waypoints) { index, waypoint ->
                val label = when(index) {
                    0 -> "Start"
                    waypoints.size - 1 -> "Ziel"
                    else -> "Via ${index}"
                }
                
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { 
                        Text(waypoint.address ?: "${String.format(Locale.US, "%.4f", waypoint.lat)}, ${String.format(Locale.US, "%.4f", waypoint.lon)}") 
                    },
                    leadingContent = { Icon(Icons.Default.Place, null) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Nach oben")
                            }
                            IconButton(onClick = { onMove(index, index + 1) }, enabled = index < waypoints.size - 1) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Nach unten")
                            }
                            IconButton(onClick = { onDelete(index) }) {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        }
        
        if (waypoints.isEmpty()) {
            Text(
                "Noch keine Wegpunkte. Nutze die Suche oder klicke lange auf die Karte.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}
