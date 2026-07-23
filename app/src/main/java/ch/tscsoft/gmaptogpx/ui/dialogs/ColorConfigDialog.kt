package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel

@Composable
fun ColorConfigDialog(viewModel: MapViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Routenfarben") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ColorRow("Hauptroute", viewModel.colorMain) { viewModel.updateColor("main", it) }
                ColorRow("Alternative 1", viewModel.colorAlt1) { viewModel.updateColor("alt1", it) }
                ColorRow("Alternative 2", viewModel.colorAlt2) { viewModel.updateColor("alt2", it) }
                ColorRow("Alternative 3", viewModel.colorAlt3) { viewModel.updateColor("alt3", it) }
                ColorRow("Original/Import", viewModel.colorOriginal) { viewModel.updateColor("original", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        dismissButton = {
            IconButton(onClick = { viewModel.resetColors() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Zurücksetzen")
            }
        }
    )
}

@Composable
fun ColorRow(label: String, currentHex: String, onColorSelected: (String) -> Unit) {
    val colors = listOf(
        "#0000FF", "#FF00FF", "#00FFFF", "#FF8800", "#FF0000",
        "#00FF00", "#666666", "#8800FF", "#000000", "#FFD700",
        "#ADFF2F", "#00FF7F", "#40E0D0", "#1E90FF", "#9370DB",
        "#FF69B4", "#FF4500", "#8B4513", "#708090", "#BC8F8F",
        "#F0E68C", "#D2B48C", "#A9A9A9", "#7B68EE", "#00CED1"
    )
    var expanded by remember { mutableStateOf(false) }

    val currentColor = Color(android.graphics.Color.parseColor(currentHex))
    val alphaValue = currentColor.alpha

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(currentColor, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .clickable { expanded = true }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Box(modifier = Modifier.padding(8.dp).width(200.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.height(180.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(colors.size) { index ->
                            val hex = colors[index]
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(android.graphics.Color.parseColor(hex)), MaterialTheme.shapes.small)
                                    .clickable {
                                        val newColor = android.graphics.Color.parseColor(hex)
                                        val alpha = (alphaValue * 255).toInt()
                                        val argb = (alpha shl 24) or (newColor and 0x00FFFFFF)
                                        onColorSelected(String.format("#%08X", argb))
                                        expanded = false
                                    }
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = alphaValue,
                onValueChange = { newAlpha ->
                    val color = android.graphics.Color.parseColor(currentHex)
                    val alpha = (newAlpha * 255).toInt()
                    val argb = (alpha shl 24) or (color and 0x00FFFFFF)
                    onColorSelected(String.format("#%08X", argb))
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("${(alphaValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
        }
    }
}
