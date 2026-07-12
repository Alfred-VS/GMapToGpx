package ch.tscsoft.gmaptogpx

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.tscsoft.gmaptogpx.ui.theme.GMapToGpxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

import androidx.core.content.FileProvider
import java.io.File

import kotlinx.serialization.json.*

private val jsonParser = Json { ignoreUnknownKeys = true }

class MapViewModel : ViewModel() {
    private var prefs: android.content.SharedPreferences? = null
    private var lastSharedText: String? = null

    var status by mutableStateOf("Warte auf Google Maps Link...")
        private set
    var gpxUri by mutableStateOf<Uri?>(null)
        private set
    var shareUri by mutableStateOf<Uri?>(null)
        private set
    var isProcessing by mutableStateOf(value = false)
        private set
    var debugUrl by mutableStateOf<String?>(null)
        private set
    var bikeProfile by mutableStateOf("fastbike")
    var lastPoints by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set
    var lastInputPoints by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set

    fun initPrefs(context: android.content.Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            bikeProfile = prefs?.getString("bike_profile", "fastbike") ?: "fastbike"
        }
    }

    fun updateProfile(newProfile: String, context: android.content.Context) {
        bikeProfile = newProfile
        prefs?.edit()?.putString("bike_profile", newProfile)?.apply()
        
        // Re-process if we have a last shared text
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun processSharedText(text: String, context: android.content.Context) {
        lastSharedText = text
        initPrefs(context)

        val url = extractUrl(text)
        if (url == null) {
            status = "Kein Link gefunden."
            return
        }

        isProcessing = true
        status = "Verarbeite Route..."
        viewModelScope.launch {
            try {
                var resolvedUrl = resolveUrl(url)
                debugUrl = resolvedUrl
                var points = extractAllCoordinates(resolvedUrl)
                
                // Retry once if no points found (sometimes resolution fails on first try)
                if (points.isEmpty() && resolvedUrl.contains("goo.gl")) {
                    status = "Erneuter Versuch der URL-Auflösung..."
                    kotlinx.coroutines.delay(800)
                    resolvedUrl = resolveUrl(url)
                    debugUrl = resolvedUrl
                    points = extractAllCoordinates(resolvedUrl)
                }

                if (points.size >= 2) {
                    lastInputPoints = points
                    val finalPoints = if (bikeProfile == "direct") {
                        status = "Übernehme Google Punkte..."
                        points
                    } else {
                        val profileName = when(bikeProfile) {
                            "fastbike" -> "Rennrad"
                            "mtb" -> "Mountainbike"
                            else -> "Trekking"
                        }
                        status = "Berechne $profileName-Route..."
                        getBikeRoute(points, bikeProfile)
                    }
                    
                    lastPoints = finalPoints
                    val gpxContent = createGpx(finalPoints)
                    gpxUri = saveGpxToDownloads(gpxContent, context.contentResolver)
                    shareUri = prepareShareUri(gpxContent, context)
                    
                    status = when {
                        bikeProfile == "direct" -> "Google Punkte übernommen (${finalPoints.size} Punkte)!"
                        finalPoints.size > points.size -> "Route erstellt (${finalPoints.size} Punkte)!"
                        else -> "Routing fehlgeschlagen, nutze Luftlinie."
                    }
                } else if (points.size == 1) {
                    lastInputPoints = points
                    lastPoints = points
                    val gpxContent = createGpx(points)
                    gpxUri = saveGpxToDownloads(gpxContent, context.contentResolver)
                    shareUri = prepareShareUri(gpxContent, context)
                    status = "Wegpunkt erstellt!"
                } else {
                    status = "Keine Koordinaten gefunden. Tippe auf ein Profil zum Erneuern."
                }
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    private suspend fun getBikeRoute(points: List<Pair<Double, Double>>, profile: String): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        try {
            // BRouter coordinates are lon,lat separated by |
            val coordsString = points.joinToString("|") { "${it.second},${it.first}" }
            val urlString = "https://brouter.de/brouter?lonlats=$coordsString&profile=$profile&alternativeidx=0&format=geojson"
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 GMapToGpx/1.0")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            
            val responseCode = connection.responseCode
            if (responseCode != 200) return@withContext points

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            
            val features = json["features"]?.jsonArray
            if (features != null && features.isNotEmpty()) {
                val geometry = features[0].jsonObject["geometry"]?.jsonObject
                val coordinates = geometry?.get("coordinates")?.jsonArray
                
                if (coordinates != null && coordinates.size > 2) {
                    return@withContext coordinates.map {
                        val point = it.jsonArray
                        // GeoJSON returns [lon, lat]
                        point[1].jsonPrimitive.double to point[0].jsonPrimitive.double
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        points
    }

    private suspend fun prepareShareUri(content: String, context: android.content.Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val shareDir = File(context.cacheDir, "shared_gpx")
            shareDir.mkdirs()
            val file = File(shareDir, "GoogleMapsRoute.gpx")
            file.writeText(content)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://[^\\s]+)".toRegex()
        return regex.find(text)?.value
    }

    private suspend fun resolveUrl(shortUrl: String): String = withContext(Dispatchers.IO) {
        var currentUrl = shortUrl
        try {
            var redirects = 0
            while (redirects < 10) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                // Use a desktop user agent to get the full web URL instead of a mobile/app deep link
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.connect()
                
                val location = connection.getHeaderField("Location")
                
                if (location != null) {
                    // Handle intent links (often used by Google Maps App)
                    if (location.startsWith("intent://")) {
                        val daddr = "daddr=([^&]+)".toRegex().find(location)?.groupValues?.get(1)
                        if (daddr != null) {
                            currentUrl = "https://maps.google.com/?q=$daddr"
                            break
                        }
                    }
                    
                    currentUrl = if (location.startsWith("http")) location else {
                        val base = URL(currentUrl)
                        URL(base.protocol, base.host, location).toString()
                    }
                    redirects++
                    connection.disconnect()
                    continue
                }
                connection.disconnect()
                break
            }
        } catch (e: Exception) {
        }
        currentUrl
    }

    private fun extractAllCoordinates(url: String): List<Pair<Double, Double>> {
        val decodedUrl = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (e: Exception) { url }
        
        fun isPlausible(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)

        // 1. Identify Camera View (usually follows @)
        val cameraPoint = "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)".toRegex().find(decodedUrl)?.let {
            val lat = it.groupValues[1].toDoubleOrNull()
            val lon = it.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) lat to lon else null
        }

        // 2. Directions Path Segments (Absolute priority for order)
        // Format: .../dir/Start/Stop1/Stop2/Destination/...
        if (decodedUrl.contains("/dir/")) {
            val pathPart = decodedUrl.substringAfter("/dir/").substringBefore("/@").substringBefore("?")
            val segments = pathPart.split("/")
            val pathPoints = mutableListOf<Pair<Double, Double>>()
            
            for (segment in segments) {
                // Try to find lat,lon in each specific path segment
                "(-?\\d+\\.\\d+)[, ]+(-?\\d+\\.\\d+)".toRegex().find(segment)?.let {
                    val lat = it.groupValues[1].toDoubleOrNull()
                    val lon = it.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null && isPlausible(lat, lon)) {
                        pathPoints.add(lat to lon)
                    }
                }
            }
            // If the path itself contained coordinates, this is the correct order.
            if (pathPoints.size >= 2) return pathPoints.distinct()
        }

        // 3. Fallback: Search in the rest of the URL, but stay after /dir/ if present
        val searchIn = if (decodedUrl.contains("/dir/")) decodedUrl.substringAfter("/dir/") else decodedUrl
        val matches = mutableListOf<Pair<Int, Pair<Double, Double>>>()

        // Search for !1d lon !2d lat (common in data blob)
        "!1d(-?\\d+\\.\\d+)!2d(-?\\d+\\.\\d+)".toRegex().findAll(searchIn).forEach {
            val lon = it.groupValues[1].toDoubleOrNull()
            val lat = it.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isPlausible(lat, lon)) {
                matches.add(it.range.first to (lat to lon))
            }
        }

        // Search for !3d lat !4d lon (common in data blob)
        "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)".toRegex().findAll(searchIn).forEach {
            val lat = it.groupValues[1].toDoubleOrNull()
            val lon = it.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isPlausible(lat, lon)) {
                matches.add(it.range.first to (lat to lon))
            }
        }

        // Generic lat,lon fallback (only for high precision to avoid false positives)
        "(-?\\d+\\.\\d+)[, ]+(-?\\d+\\.\\d+)".toRegex().findAll(searchIn).forEach {
            val lat = it.groupValues[1].toDoubleOrNull()
            val lon = it.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isPlausible(lat, lon)) {
                if (it.groupValues[1].substringAfter(".").length > 3) {
                    matches.add(it.range.first to (lat to lon))
                }
            }
        }

        val sortedPoints = matches.sortedBy { it.first }.map { it.second }.toMutableList()
        
        // Remove camera view if it's in the list
        cameraPoint?.let { cp ->
            val cpLat = cp.first
            val cpLon = cp.second
            if (cpLat != null && cpLon != null) {
                sortedPoints.removeAll { 
                    Math.abs(it.first - cpLat) < 0.0001 && Math.abs(it.second - cpLon) < 0.0001
                }
            }
        }

        return sortedPoints.distinct()
    }

    private fun createGpx(points: List<Pair<Double, Double>>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val time = sdf.format(Date())
        
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GMapToGpx\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><time>$time</time></metadata>\n")

        if (points.size == 1) {
            val p = points[0]
            val lat = String.format(Locale.US, "%.6f", p.first)
            val lon = String.format(Locale.US, "%.6f", p.second)
            sb.append("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
            sb.append("    <name>Google Maps Ort</name>\n")
            sb.append("    <time>$time</time>\n")
            sb.append("  </wpt>\n")
        } else {
            sb.append("  <trk>\n")
            sb.append("    <name>Google Maps Route</name>\n")
            sb.append("    <trkseg>\n")
            for (p in points) {
                val lat = String.format(Locale.US, "%.6f", p.first)
                val lon = String.format(Locale.US, "%.6f", p.second)
                sb.append("      <trkpt lat=\"$lat\" lon=\"$lon\">\n")
                sb.append("        <time>$time</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>")
        return sb.toString()
    }

    private suspend fun saveGpxToDownloads(content: String, contentResolver: android.content.ContentResolver): Uri? = withContext(Dispatchers.IO) {
        val fileName = "Location_${System.currentTimeMillis()}.gpx"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = contentResolver.insert(collection, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream ->
                // Ensure UTF-8 encoding explicitly
                outputStream.write(content.toByteArray(Charsets.UTF_8))
            }
        }
        uri
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val viewModel: MapViewModel = viewModel()
            val context = LocalContext.current

            // Initialize preferences
            LaunchedEffect(Unit) {
                viewModel.initPrefs(context)
            }
            
            // Handle incoming intent
            LaunchedEffect(intent) {
                if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        viewModel.processSharedText(it, context)
                    }
                }
            }

            GMapToGpxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(viewModel, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important to update the intent for LaunchedEffect
    }
}

@Composable
fun MainScreen(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Google Maps to GPX",
            style = MaterialTheme.typography.headlineMedium,
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        Text("Fahrrad-Profil auswählen:", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = viewModel.bikeProfile == "fastbike",
                onClick = { if (!viewModel.isProcessing) viewModel.updateProfile("fastbike", context) },
                label = { Text("Rennrad") }
            )
            FilterChip(
                selected = viewModel.bikeProfile == "mtb",
                onClick = { if (!viewModel.isProcessing) viewModel.updateProfile("mtb", context) },
                label = { Text("MTB") }
            )
            FilterChip(
                selected = viewModel.bikeProfile == "trekking",
                onClick = { if (!viewModel.isProcessing) viewModel.updateProfile("trekking", context) },
                label = { Text("Trekking") }
            )
            FilterChip(
                selected = viewModel.bikeProfile == "direct",
                onClick = { if (!viewModel.isProcessing) viewModel.updateProfile("direct", context) },
                label = { Text("Google URL") }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = viewModel.status,
            style = MaterialTheme.typography.bodyLarge
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (viewModel.isProcessing) {
            CircularProgressIndicator()
        }
        
        if (viewModel.shareUri != null) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(viewModel.shareUri, "application/gpx+xml")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    try {
                        context.startActivity(Intent.createChooser(intent, "GPX öffnen mit..."))
                    } catch (e: Exception) {
                        // Fallback to SEND if VIEW fails
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_STREAM, viewModel.shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "GPX teilen"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("GPX öffnen")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val points = viewModel.lastInputPoints
                    if (points.isNotEmpty()) {
                        val coordsString = points.joinToString(";") { "${it.second},${it.first}" }
                        val firstPoint = points.first()
                        val profile = if (viewModel.bikeProfile == "direct") "trekking" else viewModel.bikeProfile
                        val previewUrl = "https://brouter.de/brouter-web/#map=13/${firstPoint.first}/${firstPoint.second}/standard&lonlats=$coordsString&profile=$profile"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Vorschau der Strecke")
            }
        } else {
            Text(
                text = "Teile einen Ort aus Google Maps mit dieser App, um eine GPX Datei zu erstellen.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            
            if (viewModel.debugUrl != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Letzte URL: ${viewModel.debugUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}
