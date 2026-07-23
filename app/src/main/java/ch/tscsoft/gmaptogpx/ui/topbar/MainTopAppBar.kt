package ch.tscsoft.gmaptogpx.ui.topbar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.models.ROUTE_PROFILES
import ch.tscsoft.gmaptogpx.ui.dialogs.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    viewModel: MapViewModel
) {
    var showMenu by remember { mutableStateOf(false) }
    // ...
    var showProfileMenu by remember { mutableStateOf(false) }
    var showAltSubMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val altOptions = listOf(0 to "Nur Hauptroute", 1 to "+1 Alternative", 2 to "+2 Alternativen", 3 to "+3 Alternativen")
    val currentAltLabel = altOptions.find { it.first == viewModel.autoAltCount }?.second ?: ""

    CenterAlignedTopAppBar(
        title = {
            Text(
                "GMap to GPX",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        actions = {
            IconButton(onClick = { viewModel.showRoutePlanner = true }) {
                Icon(Icons.Default.Search, contentDescription = "Route planen")
            }
            IconButton(onClick = { showBookmarksDialog = true }) {
                Icon(Icons.Default.Bookmarks, contentDescription = "Bookmarks")
            }
            IconButton(onClick = { viewModel.refresh(context) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
            }
        },
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Einstellungen")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Routing Option")
                                    Text(currentAltLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Route, null) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null) },
                            onClick = { showAltSubMenu = true }
                        )

                        DropdownMenuItem(
                            text = { Text("Offline Karten") },
                            leadingIcon = { Icon(Icons.Default.Map, null) },
                            onClick = {
                                showOfflineDialog = true
                                showMenu = false
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text("Farben") },
                            leadingIcon = { Icon(Icons.Default.Palette, null) },
                            onClick = {
                                showColorDialog = true
                                showMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Hilfe") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, null) },
                            onClick = {
                                showInfoDialog = true
                                showMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Rechtliches") },
                            leadingIcon = { Icon(Icons.Default.Gavel, null) },
                            onClick = {
                                showLegalDialog = true
                                showMenu = false
                            }
                        )
                    }

                    DropdownMenu(
                        expanded = showAltSubMenu,
                        onDismissRequest = { showAltSubMenu = false }
                    ) {
                        altOptions.forEach { (count, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    RadioButton(selected = viewModel.autoAltCount == count, onClick = null)
                                },
                                onClick = {
                                    viewModel.updateAutoAltCount(count, context)
                                    showAltSubMenu = false
                                    showMenu = false
                                }
                            )
                        }
                    }
                }

                Box {
                    IconButton(onClick = { showProfileMenu = true }) {
                        Icon(Icons.Default.DirectionsBike, contentDescription = "Routenprofil")
                    }
                    DropdownMenu(
                        expanded = showProfileMenu,
                        onDismissRequest = { showProfileMenu = false }
                    ) {
                        ROUTE_PROFILES.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    if (viewModel.bikeProfile == id) {
                                        Icon(Icons.Default.Check, null)
                                    } else {
                                        Spacer(Modifier.width(24.dp))
                                    }
                                },
                                onClick = {
                                    viewModel.updateProfile(id, context)
                                    showProfileMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    )

    if (showColorDialog) {
        ColorConfigDialog(viewModel) { showColorDialog = false }
    }

    if (showBookmarksDialog) {
        BookmarksDialog(viewModel) { showBookmarksDialog = false }
    }

    if (showOfflineDialog) {
        OfflineMapsDialog(viewModel) { showOfflineDialog = false }
    }

    if (showInfoDialog) {
        AppInfoDialog { showInfoDialog = false }
    }

    if (showLegalDialog) {
        LegalDialog { showLegalDialog = false }
    }
}
