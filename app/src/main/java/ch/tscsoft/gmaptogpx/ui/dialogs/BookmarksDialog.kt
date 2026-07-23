package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.BookmarkFolder
import ch.tscsoft.gmaptogpx.data.BookmarkRoute
import java.util.*

@Composable
fun BookmarksDialog(viewModel: MapViewModel, onDismiss: () -> Unit) {
    val folders by viewModel.allFolders.collectAsState(initial = emptyList())
    val rootBookmarks by viewModel.rootBookmarks.collectAsState(initial = emptyList())
    var currentFolderId by remember { mutableStateOf<Long?>(null) }
    var currentFolderName by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current

    var showAddFolderDialog by remember { mutableStateOf(false) }
    var folderNameToAdd by remember { mutableStateOf("") }

    var renameFolder by remember { mutableStateOf<BookmarkFolder?>(null) }
    var renameBookmark by remember { mutableStateOf<BookmarkRoute?>(null) }
    var newName by remember { mutableStateOf("") }

    val bookmarksInFolderState = if (currentFolderId != null) {
        viewModel.getBookmarksInFolder(currentFolderId!!).collectAsState(initial = emptyList())
    } else {
        null
    }
    val bookmarksInFolder = bookmarksInFolderState?.value ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentFolderId != null) {
                    IconButton(onClick = { 
                        currentFolderId = null
                        currentFolderName = null
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                }
                Text(currentFolderName ?: "Bookmarks")
                Spacer(Modifier.weight(1f))
                if (currentFolderId == null) {
                    IconButton(onClick = { showAddFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Ordner hinzufügen")
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (currentFolderId == null) {
                        items(folders.size) { index ->
                            val folder = folders[index]
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { 
                                            renameFolder = folder
                                            newName = folder.name
                                        }) {
                                            Icon(Icons.Default.Edit, null)
                                        }
                                        IconButton(onClick = { viewModel.deleteFolder(folder) }) {
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    currentFolderId = folder.id
                                    currentFolderName = folder.name
                                }
                            )
                        }
                        items(rootBookmarks.size) { index ->
                            val bookmark = rootBookmarks[index]
                            BookmarkItem(bookmark, viewModel, onDismiss, onRename = {
                                renameBookmark = bookmark
                                newName = bookmark.title
                            })
                        }
                    } else {
                        items(bookmarksInFolder.size) { index ->
                            val bookmark = bookmarksInFolder[index]
                            BookmarkItem(bookmark, viewModel, onDismiss, onRename = {
                                renameBookmark = bookmark
                                newName = bookmark.title
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )

    if (showAddFolderDialog) {
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text("Neuer Ordner") },
            text = {
                TextField(
                    value = folderNameToAdd,
                    onValueChange = { folderNameToAdd = it },
                    placeholder = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderNameToAdd.isNotBlank()) {
                        viewModel.addFolder(folderNameToAdd)
                        folderNameToAdd = ""
                        showAddFolderDialog = false
                    }
                }) { Text("Erstellen") }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (renameFolder != null) {
        AlertDialog(
            onDismissRequest = { renameFolder = null },
            title = { Text("Ordner umbenennen") },
            text = {
                TextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renameFolder?.let { viewModel.updateFolder(it.copy(name = newName)) }
                    renameFolder = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renameFolder = null }) { Text("Abbrechen") }
            }
        )
    }

    if (renameBookmark != null) {
        AlertDialog(
            onDismissRequest = { renameBookmark = null },
            title = { Text("Bookmark umbenennen") },
            text = {
                TextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renameBookmark?.let { viewModel.updateBookmark(it.copy(title = newName)) }
                    renameBookmark = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renameBookmark = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
fun BookmarkItem(bookmark: BookmarkRoute, viewModel: MapViewModel, onDismiss: () -> Unit, onRename: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(bookmark.title) },
        supportingContent = {
            val km = String.format(Locale.US, "%.1f km", bookmark.distanceMeters / 1000.0)
            Text("$km ▲${bookmark.elevationGain}m ▼${bookmark.elevationLoss}m")
        },
        leadingContent = { Icon(Icons.Default.Route, null) },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, null)
                }
                IconButton(onClick = { viewModel.deleteBookmark(bookmark) }) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        modifier = Modifier.clickable {
            viewModel.loadBookmark(bookmark, context)
            onDismiss()
        }
    )
}
