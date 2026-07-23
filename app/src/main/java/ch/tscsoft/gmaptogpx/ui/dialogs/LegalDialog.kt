package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LegalDialog(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf( "Datenschutz", "Haftung")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rechtliche Informationen") },
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
                        0-> DatenschutzText()
                        1 -> HaftungText()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
fun DatenschutzText() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Datenschutzerklärung", style = MaterialTheme.typography.labelLarge)
        Text("1. Datenverarbeitung", style = MaterialTheme.typography.labelSmall)
        Text("Diese App verarbeitet geteilte Google Maps Links, um Routendaten von BRouter.de abzurufen. Dabei werden technisch notwendige Daten (wie die IP-Adresse) an den Routing-Dienst (BRouter-Instanz) übertragen.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text("2. Lokale Speicherung", style = MaterialTheme.typography.labelSmall)
        Text("Die App speichert Präferenzen (Fahrradprofil, Farben) lokal auf Ihrem Endgerät. Es findet keine Übermittlung dieser Daten an unsere Server statt.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text("3. Standortdaten", style = MaterialTheme.typography.labelSmall)
        Text("Die App extrahiert Standortdaten aus den von Ihnen geteilten Links. Diese werden ausschließlich zur Routenberechnung und Anzeige verwendet.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HaftungText() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Haftungsausschluss", style = MaterialTheme.typography.labelLarge)
        Text("Haftung für Inhalte", style = MaterialTheme.typography.labelSmall)
        Text("Die Inhalte unserer App wurden mit größter Sorgfalt erstellt. Für die Richtigkeit, Vollständigkeit und Aktualität der Inhalte können wir jedoch keine Gewähr übernehmen.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text("Nutzung auf eigene Gefahr", style = MaterialTheme.typography.labelSmall)
        Text("Die berechneten Routen sind lediglich Vorschläge. Die Nutzung der Routen erfolgt auf eigene Gefahr. Beachten Sie stets die Gegebenheiten vor Ort und die geltende Straßenverkehrsordnung.", style = MaterialTheme.typography.bodySmall)
    }
}
