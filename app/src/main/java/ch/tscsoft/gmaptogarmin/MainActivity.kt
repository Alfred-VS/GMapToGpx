package ch.tscsoft.gmaptogpx

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
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

data class RouteOption(
    val title: String,
    val points: List<Pair<Double, Double>>,
    val gpxContent: String,
    val isOriginal: Boolean = false,
    val inputPoints: List<Pair<Double, Double>> = emptyList(),
    val alternativeIdx: Int = 0,
    val distanceMeters: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0
)

data class BRouterResult(
    val points: List<Pair<Double, Double>>,
    val distance: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0
)

class MapViewModel : ViewModel() {
    private var prefs: android.content.SharedPreferences? = null
    private var lastSharedText: String? = null

    var status by mutableStateOf("Warte auf Google Maps Link...")
        private set
    var isProcessing by mutableStateOf(value = false)
        private set
    var debugUrl by mutableStateOf<String?>(null)
        private set
    var bikeProfile by mutableStateOf("fastbike")
    
    var routeOptions by mutableStateOf<List<RouteOption>>(emptyList())
        private set
    var visibleRoutes by mutableStateOf<Set<Int>>(emptySet())
        private set
    var isMapFullscreen by mutableStateOf(false)

    fun initPrefs(context: android.content.Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            bikeProfile = prefs?.getString("bike_profile", "fastbike") ?: "fastbike"
        }
    }

    fun updateProfile(newProfile: String, context: android.content.Context) {
        bikeProfile = newProfile
        prefs?.edit()?.putString("bike_profile", newProfile)?.apply()
        
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun toggleRouteVisibility(index: Int) {
        visibleRoutes = if (visibleRoutes.contains(index)) {
            visibleRoutes - index
        } else {
            visibleRoutes + index
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
        routeOptions = emptyList()

        viewModelScope.launch {
            try {
                var resolvedUrl = resolveUrl(url)
                debugUrl = resolvedUrl
                var points = extractAllCoordinates(resolvedUrl)
                
                if (points.isEmpty() && resolvedUrl.contains("goo.gl")) {
                    status = "Erneuter Versuch der URL-Auflösung..."
                    kotlinx.coroutines.delay(800)
                    resolvedUrl = resolveUrl(url)
                    debugUrl = resolvedUrl
                    points = extractAllCoordinates(resolvedUrl)
                }

                if (points.isNotEmpty()) {
                    val options = mutableListOf<RouteOption>()
                    
                    if (bikeProfile == "direct" || points.size < 2) {
                        // Nur die Original-Punkte anzeigen
                        val googleGpx = createGpx(points)
                        val dist = calculateDistance(points)
                        options.add(RouteOption("Google Punkte", points, googleGpx, true, points, distanceMeters = dist))
                    } else {
                        val profileName = getProfileLabel(bikeProfile)
                        
                        // Fetch Alternative 0 (Main)
                        status = "Berechne $profileName..."
                        val mainResult = getBikeRoute(points, bikeProfile, 0)
                        val mainRoute = mainResult.points
                        
                        options.add(RouteOption("Hauptroute", mainRoute, createGpx(mainRoute), inputPoints = points, alternativeIdx = 0, distanceMeters = mainResult.distance, elevationGain = mainResult.elevationGain, elevationLoss = mainResult.elevationLoss, totalTimeSeconds = mainResult.totalTimeSeconds))
                        
                        // Fetch Alternatives 1, 2, 3
                        for (i in 1..3) {
                            status = "Berechne $profileName Alternative $i..."
                            val altResult = getBikeRoute(points, bikeProfile, i)
                            val altRoute = altResult.points
                            // Nur hinzufügen, wenn sie sich von der Hauptroute und anderen Optionen unterscheidet
                            if (altRoute != mainRoute && altRoute != points && options.none { it.points == altRoute }) {
                                options.add(RouteOption("Alternative $i", altRoute, createGpx(altRoute), inputPoints = points, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds))
                            }
                        }
                    }
                    
                    routeOptions = options
                    visibleRoutes = options.indices.toSet()
                    status = "Route bereit!"
                } else {
                    status = "Keine Koordinaten gefunden."
                }
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    private fun getProfileLabel(id: String) = when(id) {
        "fastbike" -> "Rennrad"
        "mtb" -> "Mountainbike"
        "trekking" -> "Trekking"
        "shortest" -> "Kürzeste"
        "safety" -> "Sicherste"
        "hiking-soft" -> "Wandern"
        "hiking-mountain" -> "Bergwandern"
        "vm-forum" -> "Velomobil"
        "moped" -> "Mofa/Moped"
        "car-test" -> "PKW"
        else -> id
    }

    private fun calculateDistance(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i+1]
            val r = 6371000.0
            val dLat = Math.toRadians(p2.first - p1.first)
            val dLon = Math.toRadians(p2.second - p1.second)
            val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                    Math.cos(Math.toRadians(p1.first)) * Math.cos(Math.toRadians(p2.first)) *
                    Math.sin(dLon/2) * Math.sin(dLon/2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
            total += r * c
        }
        return total
    }

    private suspend fun getBikeRoute(points: List<Pair<Double, Double>>, profile: String, altIdx: Int = 0): BRouterResult = withContext(Dispatchers.IO) {
        try {
            val coordsString = points.joinToString("|") { "${it.second},${it.first}" }
            val urlString = "https://brouter.de/brouter?lonlats=$coordsString&profile=$profile&alternativeidx=$altIdx&format=geojson"
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 GMapToGpx/1.0")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            
            val responseCode = connection.responseCode
            if (responseCode != 200) return@withContext BRouterResult(points)

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            
            val features = json["features"]?.jsonArray
            if (features != null && features.isNotEmpty()) {
                val properties = features[0].jsonObject["properties"]?.jsonObject
                
                // Extrahiere Distanz robust
                val distStr = properties?.get("track-length")?.jsonPrimitive?.content
                val dist = distStr?.toDoubleOrNull() ?: 0.0
                
                // Extrahiere Anstieg und Netto-Höhenunterschied
                val gainStr = properties?.get("filtered ascend")?.jsonPrimitive?.content 
                             ?: properties?.get("filtered-ascend")?.jsonPrimitive?.content
                val plainStr = properties?.get("plain-ascend")?.jsonPrimitive?.content
                
                val filteredGain = gainStr?.toDoubleOrNull()?.toInt() ?: 0
                val plainAscent = plainStr?.toDoubleOrNull()?.toInt() ?: 0
                
                // Berechnung des Abstiegs: Total Abstieg = Total Anstieg - Netto-Höhenunterschied
                // BRouter liefert meist keinen "filtered descend" direkt im Header
                var elevGain = filteredGain
                var elevLoss = filteredGain - plainAscent
                
                // Extrahiere Zeit robust
                val timeStr = properties?.get("total-time")?.jsonPrimitive?.content 
                             ?: properties?.get("time")?.jsonPrimitive?.content
                val totalTimeSeconds = timeStr?.toDoubleOrNull()?.toInt() ?: 0

                val geometry = features[0].jsonObject["geometry"]?.jsonObject
                val coordinates = geometry?.get("coordinates")?.jsonArray
                
                // Fallback: Manuelle Berechnung NUR wenn BRouter keine Daten lieferte (filteredGain == 0)
                if (coordinates != null && coordinates.size >= 2) {
                    if (elevGain <= 0) {
                        var calcGain = 0.0
                        var calcLoss = 0.0
                        var lastZ: Double? = null
                        // Einfacher Hysteresefilter (3m) für die manuelle Berechnung
                        val threshold = 3.0 
                        
                        for (i in 0 until coordinates.size) {
                            val p = coordinates[i].jsonArray
                            if (p.size >= 3) {
                                val z = p[2].jsonPrimitive.doubleOrNull
                                if (z != null) {
                                    if (lastZ != null) {
                                        val diff = z - lastZ
                                        if (diff > threshold) {
                                            calcGain += diff
                                            lastZ = z
                                        } else if (diff < -threshold) {
                                            calcLoss += -diff
                                            lastZ = z
                                        }
                                    } else {
                                        lastZ = z
                                    }
                                }
                            }
                        }
                        elevGain = calcGain.toInt()
                        elevLoss = calcLoss.toInt()
                    }
                    
                    val resultPoints = coordinates.map {
                        val point = it.jsonArray
                        point[1].jsonPrimitive.double to point[0].jsonPrimitive.double
                    }
                    return@withContext BRouterResult(resultPoints, dist, elevGain, elevLoss, totalTimeSeconds)
                }
            }
        } catch (e: Exception) {
        }
        BRouterResult(points)
    }

    suspend fun shareGpx(option: RouteOption, context: android.content.Context) {
        val uri = prepareShareUri(option.gpxContent, context)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/gpx+xml")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "GPX öffnen mit..."))
            } catch (e: Exception) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "GPX teilen"))
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selectedRouteForDialog by remember { mutableStateOf<RouteOption?>(null) }
    
    val configuration = LocalConfiguration.current
    val mapHeight = (configuration.screenHeightDp.dp * 0.45f).coerceIn(250.dp, 500.dp)
    
    val profiles = listOf(
        "fastbike" to "Rennrad",
        "trekking" to "Trekking",
        "mtb" to "MTB",
        "shortest" to "Kürzeste",
        "safety" to "Sicherste",
        "vm-forum" to "Velomobil",
        "hiking-soft" to "Wandern",
        "hiking-mountain" to "Bergwandern",
        "moped" to "Mofa/Moped",
        "car-test" to "PKW (Test)",
        "direct" to "Google URL (nur Punkte)"
    )
    
    val currentLabel = profiles.find { it.first == viewModel.bikeProfile }?.second ?: viewModel.bikeProfile

    fun onPreview(option: RouteOption) {
        val inputPoints = option.inputPoints.ifEmpty { option.points }
        val coordsString = inputPoints.joinToString(";") { "${it.second},${it.first}" }
        val firstPoint = inputPoints.first()
        
        // Use the original input points and the alternative index in the URL
        val profile = if (option.isOriginal) "trekking" else viewModel.bikeProfile
        val altPart = if (option.isOriginal) "" else "&alternativeidx=${option.alternativeIdx}"
        
        val previewUrl = "https://brouter.de/brouter-web/#map=13/${firstPoint.first}/${firstPoint.second}/standard&lonlats=$coordsString&profile=$profile$altPart"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))
        context.startActivity(intent)
    }

    if (selectedRouteForDialog != null) {
        val option = selectedRouteForDialog!!
        AlertDialog(
            onDismissRequest = { selectedRouteForDialog = null },
            modifier = Modifier.fillMaxWidth(0.95f),
            title = { Text(option.title) },
            text = {
                val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)
                val anstieg = "${option.elevationGain} hm"
                val abstieg = "${option.elevationLoss} hm"
                
                val timeText = if (option.totalTimeSeconds > 0) {
                    val h = option.totalTimeSeconds / 3600
                    val m = (option.totalTimeSeconds % 3600) / 60
                    if (h > 0) "${h} h ${m} min" else "${m} min"
                } else ""
                
                Text("Distanz: $km\nAnstieg: $anstieg\nAbstieg: $abstieg\nZeit: $timeText")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { viewModel.shareGpx(option, context) }
                    selectedRouteForDialog = null
                }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    //Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(" Teilen")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    onPreview(option)
                    selectedRouteForDialog = null
                }) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Web")
                }
            }
        )
    }

    if (viewModel.isMapFullscreen) {
        BackHandler {
            viewModel.isMapFullscreen = false
        }
        
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            MapPreview(
                options = viewModel.routeOptions,
                visibleRoutes = viewModel.visibleRoutes,
                currentProfile = viewModel.bikeProfile,
                onRouteSelected = { index ->
                    selectedRouteForDialog = viewModel.routeOptions.getOrNull(index)
                },
                modifier = Modifier.fillMaxSize()
            )
            
            SmallFloatingActionButton(
                onClick = { viewModel.isMapFullscreen = false },
                modifier = Modifier.padding(16.dp).align(Alignment.TopEnd),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Vollbild beenden")
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Google Maps to GPX",
                style = MaterialTheme.typography.headlineMedium,
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(0.dp)) {
                    //Text("Routing-Profil:", style = MaterialTheme.typography.labelLarge)

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (!viewModel.isProcessing) expanded = !expanded },
                        modifier = Modifier.padding(vertical = 0.dp)
                    ) {
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            profiles.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.updateProfile(id, context)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            if (viewModel.routeOptions.isEmpty() || viewModel.isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = viewModel.status,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (viewModel.isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            if (viewModel.routeOptions.isNotEmpty()) {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight)
                    .clip(MaterialTheme.shapes.medium)
                ) {
                    MapPreview(
                        options = viewModel.routeOptions,
                        visibleRoutes = viewModel.visibleRoutes,
                        currentProfile = viewModel.bikeProfile,
                        onRouteSelected = { index ->
                            selectedRouteForDialog = viewModel.routeOptions.getOrNull(index)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    SmallFloatingActionButton(
                        onClick = { viewModel.isMapFullscreen = true },
                        modifier = Modifier.padding(8.dp).align(Alignment.TopEnd),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Vollbild")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }

            viewModel.routeOptions.forEachIndexed { index, option ->
                RouteOptionCard(
                    option = option,
                    isVisible = viewModel.visibleRoutes.contains(index),
                    onToggleVisibility = { viewModel.toggleRouteVisibility(index) },
                    onClick = { selectedRouteForDialog = option },
                    onShare = {
                        scope.launch { viewModel.shareGpx(option, context) }
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (viewModel.routeOptions.isEmpty() && !viewModel.isProcessing) {
                Text(
                    text = "Teile einen Ort aus Google Maps mit dieser App, um eine GPX Datei zu erstellen.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                if (viewModel.debugUrl != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Letzte URL: ${viewModel.debugUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MapPreview(
    options: List<RouteOption>,
    visibleRoutes: Set<Int>,
    currentProfile: String,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(options, visibleRoutes, currentProfile) {
        val jsonString = options.mapIndexed { index, opt ->
            val isVisible = visibleRoutes.contains(index)
            val coords = if (isVisible) opt.points.joinToString(",") { "[${it.first},${it.second}]" } else ""
            var color = if (opt.isOriginal) "#666666" else if (opt.title == "Hauptroute") "#0000FF" else "#FF8800"
            color = if (opt.title == "Alternative 1") "#FF00FF" else color
            color = if (opt.title == "Alternative 2") "#00FFFF" else color
            color = if (opt.title == "Alternative 3") "#FF8800" else color
            
            val km = String.format(Locale.US, "%.1f km", opt.distanceMeters / 1000.0)
            val timeText = if (opt.totalTimeSeconds > 0) {
                val h = opt.totalTimeSeconds / 3600
                val m = (opt.totalTimeSeconds % 3600) / 60
                if (h > 0) "${h}h ${m}min" else "${m}min"
            } else ""

            """{"index":$index, "km":"$km", "time":"$timeText", "color":"$color","points":[$coords],"isVisible":$isVisible,"isOriginal":${opt.isOriginal}}"""
        }.joinToString(",", prefix = "[", postfix = "]")

        """
        <!DOCTYPE html>
        <html>
        <head>
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                #map { height: 100%; width: 100%; position: absolute; top: 0; bottom: 0; left: 0; right: 0; }
                body { margin: 0; padding: 0; }
                .leaflet-interactive { cursor: pointer; }
                .route-label {
                    background: transparent !important;
                    border: none !important;
                    box-shadow: none !important;
                    padding: 0 !important;
                    pointer-events: auto !important;
                }
                .route-label:before {
                    display: none !important;
                }
                .label-inner {
                    border: 1px solid #333;
                    border-radius: 3px;
                    padding: 1px 2px;
                    font-size: 12px;
                    line-height: 1.1;
                    font-weight: bold;
                    text-align: center;
                    box-shadow: 0 1px 2px rgba(0,0,0,0.2);
                    white-space: nowrap;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map');
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    attribution: '© OSM'
                }).addTo(map);
                
                var routes = $jsonString;
                var group = new L.featureGroup();
                
                routes.forEach(function(route, index) {
                    if (!route.isVisible || route.points.length === 0) return;

                    var polyline = L.polyline(route.points, {
                        color: route.color,
                        weight: route.color === '#0000FF' ? 6 : 4,
                        opacity: route.color === '#666666' ? 0.5 : 0.8,
                        interactive: true
                    }).addTo(map);
                    
                    var openFn = function() {
                        if (window.Android) {
                            window.Android.selectRoute(route.index);
                        }
                    };
                    
                    polyline.on('click', openFn);
                    
                    // Simplify Label Text: Distanz & Zeit
                    var labelText = route.km;
                    if (route.time) {
                        labelText += " " + route.time ;
                    }
                    if (route.isOriginal) {
                        labelText = "G: " + labelText;
                    }
                    
                    // Add permanent label
                    if (route.points.length > 2) {
                        // Stagger labels along the route (30%, 50%, 70%, etc.)
                        var positions = [0.5, 0.3, 0.7, 0.4, 0.6];
                        var posFactor = positions[index % positions.length];
                        var targetIdx = Math.floor(route.points.length * posFactor);
                        var pos = route.points[targetIdx];
                        
                        var textColor = (route.color === '#00FFFF' || route.color === '#FF8800') ? 'black' : 'white';
                        var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + route.color + '; color:' + textColor + ';">' + labelText + '</div>';

                        var tooltip = L.tooltip({
                            permanent: true,
                            direction: 'top',
                            className: 'route-label',
                            interactive: true,
                            offset: [0, -5]
                        })
                        .setLatLng(pos)
                        .setContent(content)
                        .addTo(map);
                        
                        tooltip.on('click', openFn);
                    }

                    group.addLayer(polyline);
                });
                
                if (group.getLayers().length > 0) {
                    setTimeout(function() {
                        map.invalidateSize();
                        map.fitBounds(group.getBounds().pad(0.1));
                    }, 200);
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun selectRoute(index: Int) {
                        onRouteSelected(index)
                    }
                }, "Android")
                webViewClient = WebViewClient()

                // Verhindert, dass das äußere Scroll-Element (Column) die Touch-Events abfängt,
                // wenn der Benutzer mit der Karte interagiert.
                setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            v.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    false
                }
            }
        },
        update = { webView ->
            // Nur neu laden, wenn sich der Inhalt wirklich geändert hat.
            // Dies verhindert das träge Verhalten bei Recompositions.
            if (webView.tag != htmlContent) {
                webView.loadDataWithBaseURL("https://brouter.de", htmlContent, "text/html", "UTF-8", null)
                webView.tag = htmlContent
            }
        }
    )
}

@Composable
fun RouteOptionCard(
    option: RouteOption,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onClick: () -> Unit,
    onShare: () -> Unit
) {
    val routeColor = when {
        option.isOriginal -> Color(0xFF666666)
        option.title == "Hauptroute" -> Color(0xFF0000FF)
        option.title == "Alternative 1" -> Color(0xFFFF00FF)
        option.title == "Alternative 2" -> Color(0xFF00FFFF)
        else -> Color(0xFFFF8800)
    }

    val timeText = if (option.totalTimeSeconds > 0) {
        val h = option.totalTimeSeconds / 3600
        val m = (option.totalTimeSeconds % 3600) / 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    } else ""

    val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Teilen",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (option.distanceMeters > 0) {
                Text(
                    text = if (timeText.isNotEmpty()) "$km • $timeText" else km,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Checkbox(
                checked = isVisible,
                onCheckedChange = { onToggleVisibility() },
                colors = CheckboxDefaults.colors(
                    checkedColor = routeColor,
                    uncheckedColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}
