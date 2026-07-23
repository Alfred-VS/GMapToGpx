package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.models.SearchSuggestion
import ch.tscsoft.gmaptogpx.ui.components.SearchBar
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerDialog(
    viewModel: MapViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var activeSearchTarget by remember { mutableStateOf<SearchTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route planen") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                if (activeSearchTarget != null) {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            viewModel.performSearch(it)
                        },
                        suggestions = viewModel.searchSuggestions,
                        onSuggestionSelected = { suggestion ->
                            when (val target = activeSearchTarget) {
                                is SearchTarget.Start -> viewModel.setStartPoint(suggestion.lat, suggestion.lon, context)
                                is SearchTarget.End -> viewModel.setEndPoint(suggestion.lat, suggestion.lon, context)
                                is SearchTarget.Intermediate -> viewModel.addIntermediateWaypoint(suggestion.lat, suggestion.lon, context)
                                is SearchTarget.Replace -> viewModel.updateWaypoint(target.index, suggestion.lat, suggestion.lon, context)
                                else -> {}
                            }
                            searchQuery = ""
                            activeSearchTarget = null
                        },
                        onUseCurrentLocation = {
                            viewModel.userLocation?.let { loc ->
                                when (val target = activeSearchTarget) {
                                    is SearchTarget.Start -> viewModel.setStartPoint(loc.first, loc.second, context)
                                    is SearchTarget.End -> viewModel.setEndPoint(loc.first, loc.second, context)
                                    is SearchTarget.Intermediate -> viewModel.addIntermediateWaypoint(loc.first, loc.second, context)
                                    is SearchTarget.Replace -> viewModel.updateWaypoint(target.index, loc.first, loc.second, context)
                                    else -> {}
                                }
                            }
                            searchQuery = ""
                            activeSearchTarget = null
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    TextButton(
                        onClick = { activeSearchTarget = null; searchQuery = "" },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Abbrechen")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Start point
                        item {
                            val startWp = viewModel.waypoints.getOrNull(0)
                            RoutePointRow(
                                label = "Start",
                                address = startWp?.address ?: "Startpunkt wählen",
                                icon = Icons.Default.MyLocation,
                                onEdit = { activeSearchTarget = SearchTarget.Start },
                                onDelete = if (startWp != null) { { viewModel.removeWaypoint(0) } } else null
                            )
                        }

                        // Intermediate points
                        if (viewModel.waypoints.size > 2) {
                            itemsIndexed(viewModel.waypoints.subList(1, viewModel.waypoints.size - 1)) { index, wp ->
                                val realIndex = index + 1
                                RoutePointRow(
                                    label = "Via ${index + 1}",
                                    address = wp.address ?: "Ort wählen",
                                    icon = Icons.Default.Place,
                                    onEdit = { activeSearchTarget = SearchTarget.Replace(realIndex) },
                                    onDelete = { viewModel.removeWaypoint(realIndex) }
                                )
                            }
                        }

                        // Add stop button
                        item {
                            OutlinedButton(
                                onClick = { activeSearchTarget = SearchTarget.Intermediate },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AddLocationAlt, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Zwischenstopp hinzufügen")
                            }
                        }

                        // Destination point
                        item {
                            val endWp = if (viewModel.waypoints.size >= 2) viewModel.waypoints.last() else null
                            val endIndex = if (viewModel.waypoints.isNotEmpty()) viewModel.waypoints.size - 1 else 0
                            
                            RoutePointRow(
                                label = "Ziel",
                                address = endWp?.address ?: "Zielpunkt wählen",
                                icon = Icons.Default.Flag,
                                onEdit = { activeSearchTarget = SearchTarget.End },
                                onDelete = if (endWp != null && viewModel.waypoints.size >= 2) { { viewModel.removeWaypoint(viewModel.waypoints.size - 1) } } else null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fertig") }
        }
    )
}

@Composable
fun RoutePointRow(
    label: String,
    address: String,
    icon: ImageVector,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).clickable { onEdit() }) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

sealed class SearchTarget {
    object Start : SearchTarget()
    object End : SearchTarget()
    object Intermediate : SearchTarget()
    data class Replace(val index: Int) : SearchTarget()
}
