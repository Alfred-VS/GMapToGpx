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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
    val alternativeIdx: Int = 0
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
                        options.add(RouteOption("Google Punkte", points, googleGpx, true, points))
                    } else {
                        val profileName = getProfileLabel(bikeProfile)
                        
                        // Fetch Alternative 0 (Main)
                        status = "Berechne $profileName..."
                        val mainRoute = getBikeRoute(points, bikeProfile, 0)
                        
                        // Hauptroute immer hinzufügen (auch wenn BRouter keine Änderung vornimmt)
                        //options.add(RouteOption("$profileName (Hauptroute)", mainRoute, createGpx(mainRoute), inputPoints = points, alternativeIdx = 0))
                        options.add(RouteOption("Route 1", mainRoute, createGpx(mainRoute), inputPoints = points, alternativeIdx = 0))
                        
                        // Fetch Alternatives 1, 2, 3
                        for (i in 1..3) {
                            status = "Berechne $profileName Alternative $i..."
                            val altRoute = getBikeRoute(points, bikeProfile, i)
                            val j = i+1
                            // Nur hinzufügen, wenn sie sich von der Hauptroute und anderen Optionen unterscheidet
                            if (altRoute != mainRoute && altRoute != points && options.none { it.points == altRoute }) {
                                //options.add(RouteOption("$profileName (Alternative $i)", altRoute, createGpx(altRoute), inputPoints = points, alternativeIdx = i))
                                options.add(RouteOption("Route $j", altRoute, createGpx(altRoute), inputPoints = points, alternativeIdx = i))
                            }
                        }
                    }
                    
                    routeOptions = options
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

    private suspend fun getBikeRoute(points: List<Pair<Double, Double>>, profile: String, altIdx: Int = 0): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        try {
            val coordsString = points.joinToString("|") { "${it.second},${it.first}" }
            val urlString = "https://brouter.de/brouter?lonlats=$coordsString&profile=$profile&alternativeidx=$altIdx&format=geojson"
            
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
                        point[1].jsonPrimitive.double to point[0].jsonPrimitive.double
                    }
                }
            }
        } catch (e: Exception) {
        }
        points
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
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = viewModel.status,
            style = MaterialTheme.typography.bodyLarge
        )

        if (viewModel.isProcessing) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (viewModel.routeOptions.isNotEmpty()) {
            MapPreview(
                options = viewModel.routeOptions,
                currentProfile = viewModel.bikeProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }

        viewModel.routeOptions.forEach { option ->
            RouteOptionCard(
                option = option,
                currentProfile = viewModel.bikeProfile,
                onPreview = {
                    val inputPoints = option.inputPoints.ifEmpty { option.points }
                    val coordsString = inputPoints.joinToString(";") { "${it.second},${it.first}" }
                    val firstPoint = inputPoints.first()
                    
                    // Use the original input points and the alternative index in the URL
                    val profile = if (option.isOriginal) "trekking" else viewModel.bikeProfile
                    val altPart = if (option.isOriginal) "" else "&alternativeidx=${option.alternativeIdx}"
                    
                    val previewUrl = "https://brouter.de/brouter-web/#map=13/${firstPoint.first}/${firstPoint.second}/standard&lonlats=$coordsString&profile=$profile$altPart"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))
                    context.startActivity(intent)
                },
                onShare = {
                    scope.launch { viewModel.shareGpx(option, context) }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (viewModel.routeOptions.isEmpty() && !viewModel.isProcessing) {
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

@Composable
fun MapPreview(options: List<RouteOption>, currentProfile: String, modifier: Modifier = Modifier) {
    val htmlContent = remember(options, currentProfile) {
        val jsonString = options.joinToString(",", prefix = "[", postfix = "]") { opt ->
            val coords = opt.points.joinToString(",") { "[${it.first},${it.second}]" }
            var color = if (opt.isOriginal) "#666666" else if (opt.title.equals("Route 1")) "#0000FF" else "#FF8800"
            color = if (opt.title.equals("Route 2")) "#FF00FF" else color
            color = if (opt.title.equals("Route 3")) "#00FFFF" else color
            color = if (opt.title.equals("Route 4")) "#FF8800" else color
            
            val inputPoints = opt.inputPoints.ifEmpty { opt.points }
            val coordsString = inputPoints.joinToString(";") { "${it.second},${it.first}" }
            val firstPoint = inputPoints.first()
            val profile = if (opt.isOriginal) "trekking" else currentProfile
            val altPart = if (opt.isOriginal) "" else "&alternativeidx=${opt.alternativeIdx}"
            val previewUrl = "https://brouter.de/brouter-web/#map=13/${firstPoint.first}/${firstPoint.second}/standard&lonlats=$coordsString&profile=$profile$altPart"
            
            """{"title":"${opt.title}","color":"$color","points":[$coords],"previewUrl":"$previewUrl","isOriginal":${opt.isOriginal}}"""
        }

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
                    background: rgba(255, 255, 255, 0.9);
                    border: 2px solid #333;
                    border-radius: 4px;
                    padding: 2px 6px;
                    font-size: 11px;
                    font-weight: bold;
                    white-space: nowrap;
                    color: #000;
                    box-shadow: 0 1px 3px rgba(0,0,0,0.3);
                    pointer-events: auto !important;
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
                
                routes.forEach(function(route) {
                    var polyline = L.polyline(route.points, {
                        color: route.color,
                        weight: route.color === '#0000FF' ? 6 : 4,
                        opacity: route.color === '#666666' ? 0.5 : 0.8,
                        interactive: true
                    }).addTo(map);
                    
                    var openFn = function() {
                        if (window.Android) {
                            window.Android.openRoute(route.previewUrl);
                        }
                    };
                    
                    polyline.on('click', openFn);
                    
                    // Simplify Label Text
                    var labelText = "Route";
                    if (route.isOriginal) labelText = "Google";
                    else if (route.title.includes("Route 1")) labelText = " R1 ";
                    else if (route.title.includes("Route 2")) labelText = " R2";
                    else if (route.title.includes("Route 3")) labelText = " R3 ";
                    else if (route.title.includes("Route 4")) labelText = " R4 ";
                    
                    // Add permanent label
                    if (route.points.length > 2) {
                        var midIdx = Math.floor(route.points.length / 2);
                        var midPoint = route.points[midIdx];
                        
                        var tooltip = L.tooltip({
                            permanent: true,
                            direction: 'top',
                            className: 'route-label',
                            interactive: true
                        })
                        .setLatLng(midPoint)
                        .setContent(labelText)
                        .addTo(map);
                        
                        tooltip.on('click', openFn);
                    }

                    group.addLayer(polyline);
                });
                
                if (routes.length > 0) {
                    map.fitBounds(group.getBounds().pad(0.1));
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
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun openRoute(url: String) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                }, "Android")
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://brouter.de", htmlContent, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://brouter.de", htmlContent, "text/html", "UTF-8", null)
        }
    )
}

@Composable
fun RouteOptionCard(
    option: RouteOption,
    currentProfile: String,
    onPreview: () -> Unit,
    onShare: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        //Column(modifier = Modifier.padding(4.dp)) {

            
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${option.title} ",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )

                OutlinedButton(onClick = onPreview, modifier = Modifier.weight(2f)) {
                    Text("Detail")
                }
                Button(onClick = onShare, modifier = Modifier.weight(2f)) {
                    Text("Öffnen")
                }
            }
       // }
    }
}
