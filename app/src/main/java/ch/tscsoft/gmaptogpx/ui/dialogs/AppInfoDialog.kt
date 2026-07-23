package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.BuildConfig

@Composable
fun AppInfoDialog(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Über", "Hilfe", "Quellen")

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when (selectedTab) {
                        0 -> AboutAppText()
                        1 -> HowToText()
                        2 -> SourcesText()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
fun AboutAppText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Über GMap to GPX", style = MaterialTheme.typography.labelLarge)
        Text("Diese App schließt die Lücke zwischen der komfortablen Routenplanung in Google Maps und der Nutzung auf dedizierten GPS-Geräten oder Fahrrad-Navis.", style = MaterialTheme.typography.bodySmall)
        Text("Sie extrahiert die Wegpunkte aus Google Maps Links und berechnet mithilfe der BRouter-Engine eine optimierte, fahrradtaugliche Route.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("© by Fredi Tschumi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SourcesText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dienste & Datenquellen", style = MaterialTheme.typography.labelLarge)
        
        Text("Routing Engine", style = MaterialTheme.typography.labelSmall)
        Text("• BRouter (brouter.de): Hochperformantes, fahrradspezifisches Routing.", style = MaterialTheme.typography.bodySmall)
        
        Text("Kartendaten", style = MaterialTheme.typography.labelSmall)
        Text("• OpenStreetMap: Die freie Weltkarte, erstellt von Freiwilligen weltweit (ODbL Lizenz).", style = MaterialTheme.typography.bodySmall)
        
        Text("Bibliotheken", style = MaterialTheme.typography.labelSmall)
        Text("• Leaflet.js: Anzeige der interaktiven Karte.\n• Google Material Icons: Grafische Benutzeroberfläche.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HowToText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kurzanleitung", style = MaterialTheme.typography.labelLarge)
        Text("1. Öffne Google Maps und wähle einen Ort oder plane eine Route.", style = MaterialTheme.typography.bodySmall)
        Text("2. Tippe auf 'Teilen' und wähle diese App (GMap to GPX) aus.", style = MaterialTheme.typography.bodySmall)
        Text("3. Die App berechnet automatisch die Route basierend auf deinem Profil.", style = MaterialTheme.typography.bodySmall)
        Text("4. Über den 'Teilen'-Button an der Route kannst du die GPX-Datei exportieren.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Tipp: Über das Menü oben links kannst du die Anzahl der Alternativrouten einstellen.", style = MaterialTheme.typography.bodySmall)
    }
}
