package ch.tscsoft.gmaptogpx

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
    val altitudes: List<Double> = emptyList(),
    val distances: List<Double> = emptyList(),
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
    val altitudes: List<Double> = emptyList(),
    val distances: List<Double> = emptyList(),
    val distance: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0
)

class MapViewModel : ViewModel() {
    private var prefs: android.content.SharedPreferences? = null
    private var lastSharedText: String? = null
    private var lastPoints: List<Pair<Double, Double>> = emptyList()

    var status by mutableStateOf("Warte auf Google Maps Link...")
        private set
    var isProcessing by mutableStateOf(value = false)
        private set
    var debugUrl by mutableStateOf<String?>(null)
        private set
    var bikeProfile by mutableStateOf("fastbike")
    var autoAltCount by mutableStateOf(0)
    
    // Colors (Store as #AARRGGBB)
    var colorMain by mutableStateOf("#FF0000FF")
    var colorAlt1 by mutableStateOf("#FFFF00FF")
    var colorAlt2 by mutableStateOf("#FF00FFFF")
    var colorAlt3 by mutableStateOf("#FFFF8800")
    var colorOriginal by mutableStateOf("#99666666")
    
    var routeOptions by mutableStateOf<List<RouteOption>>(emptyList())
        private set
    var visibleRoutes by mutableStateOf<Set<Int>>(emptySet())
        private set
    var isMapFullscreen by mutableStateOf(false)

    fun initPrefs(context: android.content.Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            bikeProfile = prefs?.getString("bike_profile", "fastbike") ?: "fastbike"
            autoAltCount = prefs?.getInt("auto_alt_count", 0) ?: 0
            
            colorMain = prefs?.getString("color_main", "#FF0000FF") ?: "#FF0000FF"
            colorAlt1 = prefs?.getString("color_alt1", "#FFFF00FF") ?: "#FFFF00FF"
            colorAlt2 = prefs?.getString("color_alt2", "#FF00FFFF") ?: "#FF00FFFF"
            colorAlt3 = prefs?.getString("color_alt3", "#FFFF8800") ?: "#FFFF8800"
            colorOriginal = prefs?.getString("color_original", "#99666666") ?: "#99666666"
        }
    }

    fun refresh(context: android.content.Context) {
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun updateProfile(newProfile: String, context: android.content.Context) {
        bikeProfile = newProfile
        prefs?.edit()?.putString("bike_profile", newProfile)?.apply()
        
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun updateAutoAltCount(count: Int, context: android.content.Context) {
        autoAltCount = count
        prefs?.edit()?.putInt("auto_alt_count", count)?.apply()
        
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun updateColor(type: String, hex: String) {
        when(type) {
            "main" -> { colorMain = hex; prefs?.edit()?.putString("color_main", hex)?.apply() }
            "alt1" -> { colorAlt1 = hex; prefs?.edit()?.putString("color_alt1", hex)?.apply() }
            "alt2" -> { colorAlt2 = hex; prefs?.edit()?.putString("color_alt2", hex)?.apply() }
            "alt3" -> { colorAlt3 = hex; prefs?.edit()?.putString("color_alt3", hex)?.apply() }
            "original" -> { colorOriginal = hex; prefs?.edit()?.putString("color_original", hex)?.apply() }
        }
    }

    fun resetColors() {
        colorMain = "#FF0000FF"
        colorAlt1 = "#FFFF00FF"
        colorAlt2 = "#FF00FFFF"
        colorAlt3 = "#FFFF8800"
        colorOriginal = "#99666666"
        
        prefs?.edit()?.let {
            it.putString("color_main", colorMain)
            it.putString("color_alt1", colorAlt1)
            it.putString("color_alt2", colorAlt2)
            it.putString("color_alt3", colorAlt3)
            it.putString("color_original", colorOriginal)
            it.apply()
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
                    lastPoints = points
                    val options = mutableListOf<RouteOption>()
                    val isBRouter = resolvedUrl.contains("brouter.de/brouter-web")
                    
                    if (bikeProfile == "direct" || points.size < 2) {
                        // Nur die Original-Punkte anzeigen
                        val googleGpx = createGpx(points)
                        val dist = calculateDistance(points)
                        options.add(RouteOption("Importierte Punkte", points, emptyList(), emptyList(), googleGpx, true, points, distanceMeters = dist))
                    } else if (isBRouter) {
                        // BRouter Link: Nur diese eine Route laden, keine Alternativen
                        val urlProfile = "profile=([^&]+)".toRegex().find(resolvedUrl)?.groupValues?.get(1)
                        val effectiveProfile = urlProfile ?: bikeProfile
                        val profileName = getProfileLabel(effectiveProfile)
                        
                        status = "Lade BRouter Route ($profileName)..."
                        val result = getBikeRoute(points, effectiveProfile, 0)
                        options.add(RouteOption("BRouter Import", result.points, result.altitudes, result.distances, createGpx(result.points), inputPoints = points, alternativeIdx = 0, distanceMeters = result.distance, elevationGain = result.elevationGain, elevationLoss = result.elevationLoss, totalTimeSeconds = result.totalTimeSeconds))
                    } else {
                        val profileName = getProfileLabel(bikeProfile)
                        
                        // Fetch Alternative 0 (Main)
                        status = "Berechne $profileName..."
                        val mainResult = getBikeRoute(points, bikeProfile, 0)
                        val mainRoute = mainResult.points
                        
                        options.add(RouteOption("Hauptroute", mainRoute, mainResult.altitudes, mainResult.distances, createGpx(mainRoute), inputPoints = points, alternativeIdx = 0, distanceMeters = mainResult.distance, elevationGain = mainResult.elevationGain, elevationLoss = mainResult.elevationLoss, totalTimeSeconds = mainResult.totalTimeSeconds))
                        
                        // Fetch Alternatives according to setting
                        for (i in 1..autoAltCount) {
                            status = "Berechne $profileName Alternative $i..."
                            val altResult = getBikeRoute(points, bikeProfile, i)
                            val altRoute = altResult.points
                            // Nur hinzufügen, wenn sie sich von der Hauptroute und anderen Optionen unterscheidet
                            if (altRoute != mainRoute && altRoute != points && options.none { it.points == altRoute }) {
                                options.add(RouteOption("Alternative $i", altRoute, altResult.altitudes, altResult.distances, createGpx(altRoute), inputPoints = points, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds))
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

    private fun calculateDistanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon/2) * Math.sin(dLon/2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
        return r * c
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
                
                // Berechnung des Abstiegs
                var elevGain = filteredGain
                var elevLoss = filteredGain - plainAscent
                
                // Extrahiere Zeit robust
                val timeStr = properties?.get("total-time")?.jsonPrimitive?.content 
                             ?: properties?.get("time")?.jsonPrimitive?.content
                val totalTimeSeconds = timeStr?.toDoubleOrNull()?.toInt() ?: 0

                val geometry = features[0].jsonObject["geometry"]?.jsonObject
                val coordinates = geometry?.get("coordinates")?.jsonArray
                
                if (coordinates != null && coordinates.size >= 2) {
                    val resPoints = mutableListOf<Pair<Double, Double>>()
                    val resAlts = mutableListOf<Double>()
                    val resDists = mutableListOf<Double>()
                    var currentDist = 0.0
                    var lastP: Pair<Double, Double>? = null

                    coordinates.forEach {
                        val p = it.jsonArray
                        val lat = p[1].jsonPrimitive.double
                        val lon = p[0].jsonPrimitive.double
                        val alt = if (p.size >= 3) p[2].jsonPrimitive.double else 0.0
                        
                        lastP?.let { lp ->
                            currentDist += calculateDistanceBetween(lp.first, lp.second, lat, lon)
                        }
                        
                        resPoints.add(lat to lon)
                        resAlts.add(alt)
                        resDists.add(currentDist)
                        lastP = lat to lon
                    }

                    // Fallback: Manuelle Berechnung NUR wenn BRouter keine Daten lieferte (filteredGain == 0)
                    if (elevGain <= 0) {
                        var calcGain = 0.0
                        var calcLoss = 0.0
                        var lastZ: Double? = null
                        val threshold = 3.0 
                        
                        for (z in resAlts) {
                            if (lastZ != null) {
                                val diff = z - lastZ
                                if (diff > threshold) { calcGain += diff; lastZ = z } 
                                else if (diff < -threshold) { calcLoss += -diff; lastZ = z }
                            } else { lastZ = z }
                        }
                        elevGain = calcGain.toInt()
                        elevLoss = calcLoss.toInt()
                    }
                    
                    return@withContext BRouterResult(resPoints, resAlts, resDists, dist, elevGain, elevLoss, totalTimeSeconds)
                }
            }
        } catch (e: Exception) {
        }
        BRouterResult(points)
    }

    fun fetchAlternatives() {
        if (lastPoints.isEmpty() || isProcessing) return
        
        isProcessing = true
        viewModelScope.launch {
            try {
                val currentOptions = routeOptions.toMutableList()
                val profileName = getProfileLabel(bikeProfile)
                val mainRoute = currentOptions.find { it.title == "Hauptroute" }?.points
                
                for (i in 1..3) {
                    // Skip if already loaded
                    if (currentOptions.any { it.alternativeIdx == i && !it.isOriginal }) continue
                    
                    status = "Berechne $profileName Alternative $i..."
                    val altResult = getBikeRoute(lastPoints, bikeProfile, i)
                    val altRoute = altResult.points
                    
                    if (altRoute != lastPoints && altRoute != mainRoute && currentOptions.none { it.points == altRoute }) {
                        currentOptions.add(RouteOption("Alternative $i", altRoute, altResult.altitudes, altResult.distances, createGpx(altRoute), inputPoints = lastPoints, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds))
                    }
                }
                routeOptions = currentOptions
                visibleRoutes = currentOptions.indices.toSet()
                status = "Alternativen geladen!"
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: e.message}"
            } finally {
                isProcessing = false
            }
        }
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

        // --- NEW: BRouter-Web lonlats Support ---
        if (decodedUrl.contains("lonlats=")) {
            val lonlatsPart = decodedUrl.substringAfter("lonlats=").substringBefore("&")
            // Format is: lon,lat;lon,lat;...
            val points = mutableListOf<Pair<Double, Double>>()
            lonlatsPart.split(";").forEach { pair ->
                val parts = pair.split(",")
                if (parts.size >= 2) {
                    val lon = parts[0].toDoubleOrNull()
                    val lat = parts[1].toDoubleOrNull()
                    if (lat != null && lon != null && isPlausible(lat, lon)) {
                        points.add(lat to lon)
                    }
                }
            }
            if (points.isNotEmpty()) return points
        }

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
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        MainTopAppBar(viewModel)
                    }
                ) { innerPadding ->
                    MainScreen(viewModel, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(viewModel: MapViewModel) {
    var showMenu by remember { mutableStateOf(false) }
    var showAltSubMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val altOptions = listOf(0 to "Nur Hauptroute", 1 to "+1 Alternative", 2 to "+2 Alternativen", 3 to "+3 Alternativen")
    val currentAltLabel = altOptions.find { it.first == viewModel.autoAltCount }?.second ?: ""

    CenterAlignedTopAppBar(
        title = {
            Text(
                "GMap to GPX",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        actions = {
            IconButton(onClick = { viewModel.refresh(context) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
            }
        },
        navigationIcon = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Einstellungen")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Routing Option")
                                Text(currentAltLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Route, null) },
                        trailingIcon = { Icon(Icons.Default.ChevronRight, null) },
                        onClick = { showAltSubMenu = true }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Farben") },
                        leadingIcon = { Icon(Icons.Default.Palette, null) },
                        onClick = {
                            showColorDialog = true
                            showMenu = false
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Hilfe") },
                        leadingIcon = { Icon(Icons.Default.Help, null) },
                        onClick = {
                            showInfoDialog = true
                            showMenu = false
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Rechtliches") },
                        leadingIcon = { Icon(Icons.Default.Gavel, null) },
                        onClick = {
                            showLegalDialog = true
                            showMenu = false
                        }
                    )
                }

                DropdownMenu(
                    expanded = showAltSubMenu,
                    onDismissRequest = { showAltSubMenu = false }
                ) {
                    altOptions.forEach { (count, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                RadioButton(selected = viewModel.autoAltCount == count, onClick = null)
                            },
                            onClick = {
                                viewModel.updateAutoAltCount(count, context)
                                showAltSubMenu = false
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    )

    if (showColorDialog) {
        ColorConfigDialog(viewModel) { showColorDialog = false }
    }

    if (showInfoDialog) {
        AppInfoDialog { showInfoDialog = false }
    }

    if (showLegalDialog) {
        LegalDialog { showLegalDialog = false }
    }
}

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
                //Text("Einstellungen", style = MaterialTheme.typography.labelLarge)
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
                Icon(Icons.Default.Refresh, contentDescription = "Zurücksetzen")//, tint = MaterialTheme.colorScheme.error)
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

    // Parse current ARGB hex
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
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
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
                                        // Update color but keep current alpha
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

@Composable
fun AppInfoDialog(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Über", "Hilfe", "Quellen")

    AlertDialog(
        onDismissRequest = onDismiss,
        //title = { Text("Hilfe") },
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
fun LegalDialog(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf( "Datenschutz", "Haftung") //"Impressum",

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
                        //0 -> ImpressumText()
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
fun AboutAppText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Über GMap to GPX", style = MaterialTheme.typography.labelLarge)
        Text("Diese App schließt die Lücke zwischen der komfortablen Routenplanung in Google Maps und der Nutzung auf dedizierten GPS-Geräten oder Fahrrad-Navis.", style = MaterialTheme.typography.bodySmall)
        Text("Sie extrahiert die Wegpunkte aus Google Maps Links und berechnet mithilfe der BRouter-Engine eine optimierte, fahrradtaugliche Route.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("© by Fredi Tschumi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Version 1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun ImpressumText() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("© by Fredi Tschumi", style = MaterialTheme.typography.labelLarge)
        /*Text("Angaben gemäß § 5 TMG:", style = MaterialTheme.typography.labelLarge)
        Text("[Dein Name/Unternehmen]\n[Deine Straße Hausnummer]\n[PLZ Ort]", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Kontakt:", style = MaterialTheme.typography.labelLarge)
        Text("Telefon: [Deine Telefonnummer]\nE-Mail: [Deine E-Mail-Adresse]", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Verantwortlich für den Inhalt nach § 55 Abs. 2 RStV:", style = MaterialTheme.typography.labelLarge)
        Text("[Dein Name]\n[Deine Adresse]", style = MaterialTheme.typography.bodySmall)*/
    }
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

@Composable
fun ElevationChart(altitudes: List<Double>, distances: List<Double>, modifier: Modifier = Modifier) {
    if (altitudes.isEmpty() || altitudes.size != distances.size) return

    val minAlt = (altitudes.minOrNull() ?: 0.0)
    val maxAlt = (altitudes.maxOrNull() ?: 0.0)
    val totalDist = distances.lastOrNull() ?: 1.0
    val altRange = (maxAlt - minAlt).coerceAtLeast(10.0)

    val color = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = size.width
            val height = size.height

            val path = Path()
            val fillPath = Path()

            distances.forEachIndexed { i, d ->
                val x = (d / totalDist * width).toFloat()
                val y = (height - ((altitudes[i] - minAlt) / altRange * height)).toFloat()

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                
                if (i == distances.size - 1) {
                    fillPath.lineTo(x, height)
                    fillPath.close()
                }
            }

            drawPath(fillPath, color = color.copy(alpha = 0.2f))
            drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${minAlt.toInt()}m", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("${maxAlt.toInt()}m", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedRouteForDialog by remember { mutableStateOf<RouteOption?>(null) }

    val configuration = LocalConfiguration.current
    val mapHeight = (configuration.screenHeightDp.dp * 0.45f).coerceIn(250.dp, 500.dp)

    val profiles = listOf(
        "fastbike" to "Rennrad",
        "trekking" to "Trekking",
        "mtb" to "MTB",
        "shortest" to "Kürzeste",
        "safety" to "Sicherste",
        //"vm-forum" to "Velomobil",
        //"hiking-soft" to "Wandern",
        "hiking-mountain" to "Wandern",//"Bergwandern",
        "moped" to "Mofa/Moped"
        //--"car-test" to "PKW",
        //--"direct" to "Google URL (nur Punkte)"
    )

    var expandedProfile by remember { mutableStateOf(false) }
    val currentProfileLabel = profiles.find { it.first == viewModel.bikeProfile }?.second ?: viewModel.bikeProfile

    fun onPreview(option: RouteOption) {
        val inputPoints = option.inputPoints.ifEmpty { option.points }
        val coordsString = inputPoints.joinToString(";") { "${it.second},${it.first}" }
        val firstPoint = inputPoints.first()
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
                Column {
                    val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)
                    val anstieg = "${option.elevationGain} hm"
                    val abstieg = "${option.elevationLoss} hm"
                    val timeText = if (option.totalTimeSeconds > 0) {
                        val h = option.totalTimeSeconds / 3600
                        val m = (option.totalTimeSeconds % 3600) / 60
                        if (h > 0) "${h} h ${m} min" else "${m} min"
                    } else ""
                    Text("Distanz: $km\nAnstieg: $anstieg\nAbstieg: $abstieg\nZeit: $timeText")
                    
                    if (option.altitudes.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Höhenprofil", style = MaterialTheme.typography.labelLarge)
                        ElevationChart(
                            altitudes = option.altitudes,
                            distances = option.distances,
                            modifier = Modifier.fillMaxWidth().height(100.dp).padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { viewModel.shareGpx(option, context) }
                    selectedRouteForDialog = null
                }) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(" Teilen")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    onPreview(option)
                    selectedRouteForDialog = null
                }) {
                    Icon(imageVector = Icons.Default.Language, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(" Edit")
                }
            }
        )
    }

    if (viewModel.isMapFullscreen) {
        BackHandler { viewModel.isMapFullscreen = false }
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            MapPreview(
                options = viewModel.routeOptions,
                visibleRoutes = viewModel.visibleRoutes,
                currentProfile = viewModel.bikeProfile,
                colors = listOf(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal),
                onRouteSelected = { index -> selectedRouteForDialog = viewModel.routeOptions.getOrNull(index) },
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
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Profile Dropdown on Main Page
            ExposedDropdownMenuBox(
                expanded = expandedProfile,
                onExpandedChange = { expandedProfile = !expandedProfile },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentProfileLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Routing Profil") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProfile) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedProfile,
                    onDismissRequest = { expandedProfile = false }
                ) {
                    profiles.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.updateProfile(id, context)
                                expandedProfile = false
                            }
                        )
                    }
                }
            }

            if (viewModel.routeOptions.isEmpty() || viewModel.isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = viewModel.status, style = MaterialTheme.typography.bodyMedium)
            }

            if (viewModel.isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            val hasAllAlts = viewModel.routeOptions.count { !it.isOriginal && it.alternativeIdx > 0 } >= 3
            val isDirect = viewModel.bikeProfile == "direct"
            val isBRouterImport = viewModel.debugUrl?.contains("brouter.de/brouter-web") == true

            if (viewModel.routeOptions.isNotEmpty() && !viewModel.isProcessing && !hasAllAlts && !isDirect && !isBRouterImport) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.fetchAlternatives() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Alternativen berechnen")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (viewModel.routeOptions.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(mapHeight).clip(MaterialTheme.shapes.medium)) {
                    MapPreview(
                        options = viewModel.routeOptions,
                        visibleRoutes = viewModel.visibleRoutes,
                        currentProfile = viewModel.bikeProfile,
                        colors = listOf(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal),
                        onRouteSelected = { index -> selectedRouteForDialog = viewModel.routeOptions.getOrNull(index) },
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
                    onShare = { scope.launch { viewModel.shareGpx(option, context) } },
                    colors = listOf(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (viewModel.routeOptions.isEmpty() && !viewModel.isProcessing) {
                Text(
                    text = "Teile einen Ort aus Google Maps mit dieser App, um eine GPX Datei zu erstellen.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
fun MapPreview(
    options: List<RouteOption>,
    visibleRoutes: Set<Int>,
    currentProfile: String,
    colors: List<String>,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(options, visibleRoutes, currentProfile, colors) {
        val jsonString = options.mapIndexed { index, opt ->
            val isVisible = visibleRoutes.contains(index)
            val coords = if (isVisible) opt.points.joinToString(",") { "[${it.first},${it.second}]" } else ""

            val argbHex = when {
                opt.isOriginal -> colors[4]
                opt.title == "Hauptroute" -> colors[0]
                opt.title == "Alternative 1" -> colors[1]
                opt.title == "Alternative 2" -> colors[2]
                opt.title == "Alternative 3" -> colors[3]
                else -> colors[3]
            }

            // Convert ARGB to Leaflet color and opacity
            val colorObj = android.graphics.Color.parseColor(argbHex)
            val opacity = (android.graphics.Color.alpha(colorObj) / 255.0)
            val rgbHex = String.format("#%06X", (0xFFFFFF and colorObj))

            val km = String.format(Locale.US, "%.1f km", opt.distanceMeters / 1000.0)
            val timeText = if (opt.totalTimeSeconds > 0) {
                val h = opt.totalTimeSeconds / 3600
                val m = (opt.totalTimeSeconds % 3600) / 60
                if (h > 0) "${h}h ${m}min" else "${m}min"
            } else ""
            """{"index":$index, "km":"$km", "time":"$timeText", "color":"$rgbHex", "opacity":$opacity, "points":[$coords],"isVisible":$isVisible,"isOriginal":${opt.isOriginal}}"""
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
                .route-label { background: transparent !important; border: none !important; box-shadow: none !important; padding: 0 !important; pointer-events: auto !important; }
                .route-label:before { display: none !important; }
                .label-inner { border: 1px solid #333; border-radius: 3px; padding: 1px 2px; font-size: 12px; line-height: 1.1; font-weight: bold; text-align: center; box-shadow: 0 1px 2px rgba(0,0,0,0.2); white-space: nowrap; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map');
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OSM' }).addTo(map);
                var routes = $jsonString;
                var group = new L.featureGroup();
                function isLight(color) {
                    var r, g, b, hsp;
                    color = color.replace('#', '');
                    if (color.length === 3) color = color.split('').map(function(s){return s+s;}).join('');
                    r = parseInt(color.substring(0,2),16); g = parseInt(color.substring(2,4),16); b = parseInt(color.substring(4,6),16);
                    hsp = Math.sqrt(0.299 * (r * r) + 0.587 * (g * g) + 0.114 * (b * b));
                    return hsp > 127.5;
                }
                routes.forEach(function(route, index) {
                    if (!route.isVisible || route.points.length === 0) return;
                    var polyline = L.polyline(route.points, { 
                        color: route.color, 
                        opacity: route.opacity,
                        weight: (route.index === 0 && !route.isOriginal) ? 6 : 4, 
                        interactive: true 
                    }).addTo(map);
                    var openFn = function() { if (window.Android) window.Android.selectRoute(route.index); };
                    polyline.on('click', openFn);
                    var labelText = route.km;
                    if (route.time) labelText += " " + route.time ;
                    if (route.isOriginal) labelText = "G: " + labelText;
                    if (route.points.length > 2) {
                        var positions = [0.5, 0.3, 0.7, 0.4, 0.6];
                        var posFactor = positions[index % positions.length];
                        var targetIdx = Math.floor(route.points.length * posFactor);
                        var pos = route.points[targetIdx];
                        var textColor = isLight(route.color) ? 'black' : 'white';
                        var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + route.color + '; color:' + textColor + '; opacity:' + route.opacity + ';">' + labelText + '</div>';
                        var tooltip = L.tooltip({ permanent: true, direction: 'top', className: 'route-label', interactive: true, offset: [0, -5] }).setLatLng(pos).setContent(content).addTo(map);
                        tooltip.on('click', openFn);
                    }
                    group.addLayer(polyline);
                });
                if (group.getLayers().length > 0) {
                    setTimeout(function() { map.invalidateSize(); map.fitBounds(group.getBounds().pad(0.1)); }, 200);
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
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun selectRoute(index: Int) { onRouteSelected(index) }
                }, "Android")
                webViewClient = WebViewClient()
                setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) { v.parent.requestDisallowInterceptTouchEvent(true) }
                    false
                }
            }
        },
        update = { webView ->
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
    onShare: () -> Unit,
    colors: List<String>
) {
    val routeColorHex = when {
        option.isOriginal -> colors[4]
        option.title == "Hauptroute" -> colors[0]
        option.title == "Alternative 1" -> colors[1]
        option.title == "Alternative 2" -> colors[2]
        option.title == "Alternative 3" -> colors[3]
        else -> colors[3]
    }
    val routeColor = try { Color(android.graphics.Color.parseColor(routeColorHex)) } catch (e: Exception) { Color.Gray }
    val timeText = if (option.totalTimeSeconds > 0) {
        val h = option.totalTimeSeconds / 3600
        val m = (option.totalTimeSeconds % 3600) / 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    } else ""
    val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)

    ElevatedCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = option.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Teilen", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (option.distanceMeters > 0) {
                Text(text = if (timeText.isNotEmpty()) "$km • $timeText" else km, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(
                checked = isVisible,
                onCheckedChange = { onToggleVisibility() },
                colors = CheckboxDefaults.colors(checkedColor = routeColor, uncheckedColor = MaterialTheme.colorScheme.outline)
            )
        }
    }
}
