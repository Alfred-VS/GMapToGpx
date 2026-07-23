package ch.tscsoft.gmaptogpx.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.MapViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherSettingsDialog(viewModel: MapViewModel, onDismiss: () -> Unit) {
    var selectedTimeMillis by remember { mutableLongStateOf(viewModel.weatherStartTime) }
    
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance().apply { timeInMillis = selectedTimeMillis } }
    
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedTimeMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                // Open-Meteo limit: approx 16 days
                val now = System.currentTimeMillis()
                val sixteenDays = 16L * 24 * 60 * 60 * 1000
                return utcTimeMillis >= (now - 24 * 60 * 60 * 1000) && utcTimeMillis <= (now + sixteenDays)
            }
        }
    )
    
    val timePickerState = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE),
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wetter-Zeitpunkt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Startzeitpunkt der Tour:", style = MaterialTheme.typography.labelMedium)
                
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Event, null)
                    Spacer(Modifier.width(8.dp))
                    Text(sdf.format(Date(selectedTimeMillis)))
                }
                
                TextButton(
                    onClick = { 
                        selectedTimeMillis = System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Jetzt")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.weatherStartTime = selectedTimeMillis
                viewModel.refreshWeather(context)
                onDismiss()
            }) { Text("Übernehmen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val newCal = Calendar.getInstance().apply { timeInMillis = it }
                        calendar.set(Calendar.YEAR, newCal.get(Calendar.YEAR))
                        calendar.set(Calendar.MONTH, newCal.get(Calendar.MONTH))
                        calendar.set(Calendar.DAY_OF_MONTH, newCal.get(Calendar.DAY_OF_MONTH))
                        selectedTimeMillis = calendar.timeInMillis
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("OK") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    calendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    calendar.set(Calendar.MINUTE, timePickerState.minute)
                    selectedTimeMillis = calendar.timeInMillis
                    showTimePicker = false
                }) { Text("OK") }
            },
            title = { Text("Uhrzeit wählen") },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }
}
