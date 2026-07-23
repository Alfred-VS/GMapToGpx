package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.models.RouteOption
import ch.tscsoft.gmaptogpx.ui.components.ElevationChart
import java.util.*

@Composable
fun RouteDetailDialog(
    option: RouteOption,
    viewModel: MapViewModel,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(option.title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)
                val anstieg = "${option.elevationGain} m"
                val abstieg = "${option.elevationLoss} m"
                val timeText = if (option.totalTimeSeconds > 0) {
                    val h = option.totalTimeSeconds / 3600
                    val m = (option.totalTimeSeconds % 3600) / 60
                    if (h > 0) "${h} h ${m} min" else "${m} min"
                } else ""
                
                Text("➜ $km ▲$anstieg ▼$abstieg${if(timeText.isNotEmpty()) " \uD83D\uDD57 $timeText" else ""}",
                    style = MaterialTheme.typography.bodySmall)


                val isSteep = option.segments.any { it.gradient > 15.0 }
                if (isSteep) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Achtung: Sehr steile Abschnitte (>15%)", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (option.altitudes.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ElevationChart(
                        option = option,
                        modifier = Modifier.fillMaxWidth().height(150.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedIconButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Sichern")
                }
                OutlinedIconButton(onClick = { viewModel.downloadMapForRoute(option.points) }) {
                    Icon(Icons.Default.Download, contentDescription = "Offline")
                }
                OutlinedIconButton(onClick = onEdit) {
                    Icon(Icons.Default.Language, contentDescription = "Edit")
                }
                FilledIconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Teilen")
                }
            }
        }
    )

    if (showSaveDialog) {
        SaveBookmarkDialog(option, viewModel) { showSaveDialog = false }
    }
}
