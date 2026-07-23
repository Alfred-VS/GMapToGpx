package ch.tscsoft.gmaptogpx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.data.models.SearchSuggestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<SearchSuggestion>,
    onSuggestionSelected: (SearchSuggestion) -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf(false) }

    DockedSearchBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (active) 0.dp else 16.dp),
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { active = false },
        active = active,
        onActiveChange = { active = it },
        placeholder = { Text("Ort suchen...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                }
            }
        },
        colors = SearchBarDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                ListItem(
                    headlineContent = { Text("Meine aktuelle Position", color = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("Aktuellen Standort als Wegpunkt nutzen") },
                    leadingContent = { Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        onUseCurrentLocation()
                        active = false
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            items(suggestions) { suggestion ->
                ListItem(
                    headlineContent = { Text(suggestion.name) },
                    supportingContent = { Text(suggestion.description) },
                    modifier = Modifier.clickable {
                        onSuggestionSelected(suggestion)
                        active = false
                    }
                )
            }
        }
    }
}
