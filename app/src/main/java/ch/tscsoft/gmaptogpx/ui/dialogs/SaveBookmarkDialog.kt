package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.models.RouteOption

@Composable
fun SaveBookmarkDialog(
    option: RouteOption,
    viewModel: MapViewModel,
    onDismiss: () -> Unit
) {
    var bookmarkTitle by remember { mutableStateOf(option.title) }
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    val folders by viewModel.allFolders.collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route speichern") },
        text = {
            Column {
                TextField(
                    value = bookmarkTitle,
                    onValueChange = { bookmarkTitle = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Ordner wählen:", style = MaterialTheme.typography.labelSmall)
                LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedFolderId = null }) {
                            RadioButton(selected = selectedFolderId == null, onClick = { selectedFolderId = null })
                            Text("Hauptverzeichnis")
                        }
                    }
                    items(folders.size) { index ->
                        val folder = folders[index]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedFolderId = folder.id }) {
                            RadioButton(selected = selectedFolderId == folder.id, onClick = { selectedFolderId = folder.id })
                            Text(folder.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveBookmark(option, bookmarkTitle, selectedFolderId)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
