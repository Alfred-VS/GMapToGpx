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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
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

data class RouteSegment(
    val surface: String,
    val highway: String,
    val gradient: Double, // in %
    val distance: Double, // in meters
    val startIndex: Int,
    val endIndex: Int
)

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
    val totalTimeSeconds: Int = 0,
    val segments: List<RouteSegment> = emptyList(),
    val surfaceSummary: Map<String, Double> = emptyMap()
)

data class BRouterResult(
    val points: List<Pair<Double, Double>>,
    val altitudes: List<Double> = emptyList(),
    val distances: List<Double> = emptyList(),
    val distance: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0,
    val segments: List<RouteSegment> = emptyList()
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
    var autoAltCount by mutableIntStateOf(0)
    
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
    var mapType by mutableStateOf("standard")

    var highlightedPointIndex by mutableStateOf<Int?>(null)
    var highlightedRouteIndex by mutableStateOf<Int?>(null)

    fun initPrefs(context: android.content.Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            bikeProfile = prefs?.getString("bike_profile", "fastbike") ?: "fastbike"
            autoAltCount = prefs?.getInt("auto_alt_count", 0) ?: 0
            mapType = prefs?.getString("map_type", "standard") ?: "standard"

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
        if (highlightedRouteIndex == index) {
            highlightedPointIndex = null
            highlightedRouteIndex = null
        }
    }

    fun setHighlight(routeIdx: Int?, pointIdx: Int?) {
        highlightedRouteIndex = routeIdx
        highlightedPointIndex = pointIdx
    }

    fun toggleMapType() {
        mapType = if (mapType == "standard") "satellite" else "standard"
        prefs?.edit()?.putString("map_type", mapType)?.apply()
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
                        options.add(RouteOption("BRouter Import", result.points, result.altitudes, result.distances, createGpx(result.points), inputPoints = points, alternativeIdx = 0, distanceMeters = result.distance, elevationGain = result.elevationGain, elevationLoss = result.elevationLoss, totalTimeSeconds = result.totalTimeSeconds, segments = result.segments, surfaceSummary = createSurfaceSummary(result.segments)))
                    } else {
                        val profileName = getProfileLabel(bikeProfile)
                        
                        // Fetch Alternative 0 (Main)
                        status = "Berechne $profileName..."
                        val mainResult = getBikeRoute(points, bikeProfile, 0)
                        val mainRoute = mainResult.points
                        
                        options.add(RouteOption("Hauptroute", mainRoute, mainResult.altitudes, mainResult.distances, createGpx(mainRoute), inputPoints = points, alternativeIdx = 0, distanceMeters = mainResult.distance, elevationGain = mainResult.elevationGain, elevationLoss = mainResult.elevationLoss, totalTimeSeconds = mainResult.totalTimeSeconds, segments = mainResult.segments, surfaceSummary = createSurfaceSummary(mainResult.segments)))
                        
                        // Fetch Alternatives according to setting
                        for (i in 1..autoAltCount) {
                            status = "Berechne $profileName Alternative $i..."
                            val altResult = getBikeRoute(points, bikeProfile, i)
                            val altRoute = altResult.points
                            // Nur hinzufügen, wenn sie sich von der Hauptroute und anderen Optionen unterscheidet
                            if (altRoute != mainRoute && altRoute != points && options.none { it.points == altRoute }) {
                                options.add(RouteOption("Alternative $i", altRoute, altResult.altitudes, altResult.distances, createGpx(altRoute), inputPoints = points, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds, segments = altResult.segments, surfaceSummary = createSurfaceSummary(altResult.segments)))
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

    fun processSharedUri(uri: Uri, context: android.content.Context) {
        initPrefs(context)
        isProcessing = true
        status = "Lade GPX Datei..."
        routeOptions = emptyList()

        viewModelScope.launch {
            try {
                val gpxContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }

                if (gpxContent != null) {
                    val points = mutableListOf<Pair<Double, Double>>()
                    val alts = mutableListOf<Double>()
                    
                    // Simple GPX Parsing using Regex (as we don't have a full XML parser library dependency)
                    // Matches <trkpt lat="..." lon="..."> and optional <ele>...</ele>
                    val trkptRegex = """<trkpt\s+lat=["']([-+]?\d+\.\d+)["']\s+lon=["']([-+]?\d+\.\d+)["']>(\s*<ele>([-+]?\d+\.\d+)</ele>)?""".toRegex()
                    trkptRegex.findAll(gpxContent).forEach { match ->
                        val lat = match.groupValues[1].toDoubleOrNull()
                        val lon = match.groupValues[2].toDoubleOrNull()
                        val ele = match.groupValues[4].toDoubleOrNull() ?: 0.0
                        if (lat != null && lon != null) {
                            points.add(lat to lon)
                            alts.add(ele)
                        }
                    }

                    // Fallback to waypoints if no trackpoints found
                    if (points.isEmpty()) {
                        val wptRegex = """<wpt\s+lat=["']([-+]?\d+\.\d+)["']\s+lon=["']([-+]?\d+\.\d+)["']>(\s*<ele>([-+]?\d+\.\d+)</ele>)?""".toRegex()
                        wptRegex.findAll(gpxContent).forEach { match ->
                            val lat = match.groupValues[1].toDoubleOrNull()
                            val lon = match.groupValues[2].toDoubleOrNull()
                            val ele = match.groupValues[4].toDoubleOrNull() ?: 0.0
                            if (lat != null && lon != null) {
                                points.add(lat to lon)
                                alts.add(ele)
                            }
                        }
                    }

                    if (points.isNotEmpty()) {
                        lastPoints = points
                        val dist = calculateDistance(points)
                        
                        // Elevation Gain/Loss calculation
                        var gain = 0.0
                        var loss = 0.0
                        for (i in 1 until alts.size) {
                            val diff = alts[i] - alts[i-1]
                            if (diff > 0.5) gain += diff
                            else if (diff < -0.5) loss += -diff
                        }

                        val distances = mutableListOf<Double>()
                        var currentD = 0.0
                        distances.add(0.0)
                        for (i in 1 until points.size) {
                            currentD += calculateDistanceBetween(points[i-1].first, points[i-1].second, points[i].first, points[i].second)
                            distances.add(currentD)
                        }

                        val option = RouteOption(
                            title = "Importierte GPX",
                            points = points,
                            altitudes = alts,
                            distances = distances,
                            gpxContent = gpxContent,
                            isOriginal = true,
                            inputPoints = points,
                            distanceMeters = dist,
                            elevationGain = gain.toInt(),
                            elevationLoss = loss.toInt()
                        )
                        routeOptions = listOf(option)
                        visibleRoutes = setOf(0)
                        status = "GPX geladen!"
                        lastSharedText = null // Reset last shared text
                    } else {
                        status = "Keine Wegpunkte in GPX gefunden."
                    }
                } else {
                    status = "Datei konnte nicht gelesen werden."
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
            if (features != null && !features.isEmpty()) {
                val feature = features[0].jsonObject
                val properties = feature["properties"]?.jsonObject
                
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

                val geometry = feature["geometry"]?.jsonObject
                val coordinates = geometry?.get("coordinates")?.jsonArray
                
                // Parsing messages for surface and highway
                val messages = properties?.get("messages")?.jsonArray
                val resSegments = mutableListOf<RouteSegment>()
                
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

                    // Parse segments from messages
                    if (messages != null && messages.size > 1) {
                        var lastSurface = ""
                        var lastHighway = ""
                        var segmentDist = 0.0
                        var startAlt = 0.0
                        var startIndex = 0

                        for (i in 1 until messages.size) {
                            val msg = messages[i].jsonArray
                            if (msg.size < 10) continue
                            
                            val lat = msg[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                            val lon = msg[0].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                            val p = (lat / 1000000.0) to (lon / 1000000.0)
                            
                            val alt = msg[2].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                            val d = msg[3].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                            val tags = msg[9].jsonPrimitive.content
                            
                            val surface = "surface=(\\S+)".toRegex().find(tags)?.groupValues?.get(1) ?: "unbekannt"
                            val highway = "highway=(\\S+)".toRegex().find(tags)?.groupValues?.get(1) ?: "unbekannt"
                            
                            // Find corresponding index in resPoints
                            val currentIndex = resPoints.indices.minByOrNull { idx ->
                                val rp = resPoints[idx]
                                Math.pow(rp.first - p.first, 2.0) + Math.pow(rp.second - p.second, 2.0)
                            } ?: 0

                            if (i == 1) {
                                lastSurface = surface
                                lastHighway = highway
                                startAlt = alt
                                startIndex = currentIndex
                            } else if (surface != lastSurface || highway != lastHighway || i == messages.size - 1) {
                                val endIndex = currentIndex
                                val elevDiff = alt - startAlt
                                val gradient = if (segmentDist > 0) (elevDiff / segmentDist) * 100.0 else 0.0
                                
                                resSegments.add(RouteSegment(lastSurface, lastHighway, gradient, segmentDist, startIndex, endIndex))
                                
                                lastSurface = surface
                                lastHighway = highway
                                segmentDist = d
                                startAlt = alt
                                startIndex = endIndex
                            } else {
                                segmentDist += d
                            }
                        }
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

                    return@withContext BRouterResult(resPoints, resAlts, resDists, dist, elevGain, elevLoss, totalTimeSeconds, resSegments)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        BRouterResult(points)
    }

    private fun createSurfaceSummary(segments: List<RouteSegment>): Map<String, Double> {
        val summary = mutableMapOf<String, Double>()
        var totalDist = 0.0
        segments.forEach { seg ->
            val label = when (seg.surface) {
                "asphalt", "paved", "concrete" -> "Asphalt"
                "gravel", "fine_gravel", "compacted", "dirt", "ground", "unpaved" -> "Schotter"
                "paving_stones", "sett", "cobblestone" -> "Pflaster"
                "sand" -> "Sand"
                "unbekannt" -> "Unbekannt"
                else -> seg.surface.replaceFirstChar { it.uppercase() }
            }
            summary[label] = (summary[label] ?: 0.0) + seg.distance
            totalDist += seg.distance
        }
        return if (totalDist > 0) {
            summary.mapValues { it.value / totalDist }
        } else {
            emptyMap()
        }
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
                        currentOptions.add(RouteOption("Alternative $i", altRoute, altResult.altitudes, altResult.distances, createGpx(altRoute), inputPoints = lastPoints, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds, segments = altResult.segments, surfaceSummary = createSurfaceSummary(altResult.segments)))
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
        val regex = "(https?://\\S+)".toRegex()
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
            sortedPoints.removeAll { 
                Math.abs(it.first - cpLat) < 0.0001 && Math.abs(it.second - cpLon) < 0.0001
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
                when {
                    intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            viewModel.processSharedText(it, context)
                        }
                    }
                    (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) && 
                    (intent.type?.contains("gpx") == true || intent.data?.path?.endsWith(".gpx") == true || intent.type == "application/octet-stream") -> {
                        val uri = if (intent.action == Intent.ACTION_SEND) {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                            }
                        } else {
                            intent.data
                        }
                        uri?.let { viewModel.processSharedUri(it, context) }
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
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, null) },
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

@Composable
fun ImpressumText() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("© by Fredi Tschumi", style = MaterialTheme.typography.labelLarge)
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

private fun getSurfaceColor(surface: String): Color {
    return when (surface.lowercase()) {
        "asphalt", "paved", "concrete" -> Color(0xFF666666)
        "gravel", "fine_gravel", "compacted", "dirt", "ground", "unpaved", "schotter" -> Color(0xFFC2B280)
        "paving_stones", "sett", "cobblestone", "pflaster" -> Color(0xFF999999)
        "sand" -> Color(0xFFEDC9AF)
        "unbekannt" -> Color(0xFF2196F3)
        else -> Color(0xFF2196F3)
    }
}

@Composable
fun ElevationChart(
    option: RouteOption,
    modifier: Modifier = Modifier,
    highlightedIndex: Int? = null,
    onPointHighlighted: (Int?) -> Unit = {}
) {
    val altitudes = option.altitudes
    val distances = option.distances
    if (altitudes.isEmpty() || altitudes.size != distances.size) return

    val minAlt = (altitudes.minOrNull() ?: 0.0)
    val maxAlt = (altitudes.maxOrNull() ?: 0.0)
    val totalDist = distances.lastOrNull() ?: 1.0
    val altRange = (maxAlt - minAlt).coerceAtLeast(10.0)

    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightColor = MaterialTheme.colorScheme.error

    Column(modifier = modifier) {
        val currentAlt = if (highlightedIndex != null && highlightedIndex in altitudes.indices) {
            altitudes[highlightedIndex]
        } else maxAlt

        Text(
            "${currentAlt.toInt()} m",
            style = MaterialTheme.typography.labelSmall,
            color = if (highlightedIndex != null) highlightColor else labelColor,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(distances) {
                    detectTapGestures { offset ->
                        val x = offset.x
                        val index = findNearestIndex(x, size.width, distances)
                        onPointHighlighted(index)
                    }
                }
                .pointerInput(distances) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val index = findNearestIndex(offset.x, size.width, distances)
                            onPointHighlighted(index)
                        },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change, _ ->
                            val index = findNearestIndex(change.position.x, size.width, distances)
                            onPointHighlighted(index)
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height

            if (option.segments.isNotEmpty()) {
                option.segments.forEach { seg ->
                    val segPath = Path()
                    val segFillPath = Path()
                    val segColor = getSurfaceColor(seg.surface)

                    for (i in seg.startIndex..seg.endIndex) {
                        if (i >= distances.size) break
                        val x = (distances[i] / totalDist * width).toFloat()
                        val y = (height - ((altitudes[i] - minAlt) / altRange * height)).toFloat()

                        if (i == seg.startIndex) {
                            segPath.moveTo(x, y)
                            segFillPath.moveTo(x, height)
                            segFillPath.lineTo(x, y)
                        } else {
                            segPath.lineTo(x, y)
                            segFillPath.lineTo(x, y)
                        }
                    }
                    val lastIdx = Math.min(seg.endIndex, distances.size - 1)
                    val lastX = (distances[lastIdx] / totalDist * width).toFloat()
                    segFillPath.lineTo(lastX, height)
                    segFillPath.close()

                    drawPath(segFillPath, color = segColor.copy(alpha = 0.3f))
                    drawPath(segPath, color = segColor, style = Stroke(width = 2.dp.toPx()))
                }
            } else {
                val path = Path()
                val fillPath = Path()
                val color = Color(0xFF2196F3)

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

            if (highlightedIndex != null && highlightedIndex in distances.indices) {
                val hX = (distances[highlightedIndex] / totalDist * width).toFloat()
                val hY = (height - ((altitudes[highlightedIndex] - minAlt) / altRange * height)).toFloat()

                drawLine(
                    color = highlightColor,
                    start = Offset(hX, 0f),
                    end = Offset(hX, height),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(
                    color = highlightColor,
                    center = Offset(hX, hY),
                    radius = 4.dp.toPx()
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentDist = if (highlightedIndex != null && highlightedIndex in distances.indices) {
                distances[highlightedIndex]
            } else totalDist

            Text("${minAlt.toInt()} m", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                String.format(Locale.US, "%.1f km", currentDist / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                color = if (highlightedIndex != null) highlightColor else labelColor
            )
        }

        if (option.surfaceSummary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                option.surfaceSummary.forEach { (label, ratio) ->
                    if (ratio > 0.05) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(getSurfaceColor(label))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$label ${(ratio * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun findNearestIndex(x: Float, width: Int, distances: List<Double>): Int? {
    if (distances.isEmpty()) return null
    val totalDist = distances.last()
    if (totalDist <= 0) return 0
    val targetDist = (x / width) * totalDist
    
    var low = 0
    var high = distances.size - 1
    
    while (low <= high) {
        val mid = (low + high) / 2
        if (distances[mid] < targetDist) low = mid + 1
        else if (distances[mid] > targetDist) high = mid - 1
        else return mid
    }
    
    return when {
        low >= distances.size -> distances.size - 1
        high < 0 -> 0
        else -> if (Math.abs(distances[low] - targetDist) < Math.abs(distances[high] - targetDist)) low else high
    }
}

@Composable
fun RouteDetailDialog(
    option: RouteOption,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(option.title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)
                val anstieg = "${option.elevationGain} m"
                val abstieg = "${option.elevationLoss} m"
                val timeText = if (option.totalTimeSeconds > 0) {
                    val h = option.totalTimeSeconds / 3600
                    val m = (option.totalTimeSeconds % 3600) / 60
                    if (h > 0) "${h} h ${m} min" else "${m} min"
                } else ""
                
                Text("➜ $km ▲$anstieg ▼$abstieg${if(timeText.isNotEmpty()) " \uD83D\uDD57 $timeText" else ""}",
                    style = MaterialTheme.typography.bodySmall)

                val isSteep = option.segments.any { it.gradient > 15.0 }
                if (isSteep) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Achtung: Sehr steile Abschnitte (>15%)", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (option.altitudes.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ElevationChart(
                        option = option,
                        modifier = Modifier.fillMaxWidth().height(150.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row (horizontalArrangement = Arrangement.spacedBy(8.dp)){
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                    Text(" Edit", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Text(" Teilen", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { viewModel.routeOptions.size })

    val colors = remember(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal) {
        listOf(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal)
    }

    val profiles = listOf(
        "fastbike" to "Rennrad",
        "trekking" to "Trekking",
        "mtb" to "MTB",
        "shortest" to "Kürzeste",
        "safety" to "Sicherste",
        "hiking-mountain" to "Wandern",
        "moped" to "Mofa/Moped"
    )

    var expandedProfile by remember { mutableStateOf(false) }
    val currentProfileLabel = profiles.find { it.first == viewModel.bikeProfile }?.second ?: viewModel.bikeProfile
    var selectedRouteForDialog by remember { mutableStateOf<RouteOption?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        if (viewModel.highlightedRouteIndex != pagerState.currentPage) {
            viewModel.setHighlight(null, null)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val fixedElementsHeight = 240.dp
        val minMapHeight = 200.dp
        val minChartHeight = 80.dp
        
        val availableDynamicSpace = totalHeight - fixedElementsHeight
        
        val (mapHeight, chartHeight) = if (availableDynamicSpace > (minMapHeight + minChartHeight)) {
            val excess = availableDynamicSpace - (minMapHeight + minChartHeight)
            val cH = (minChartHeight + excess * 0.35f).coerceAtMost(200.dp)
            val mH = availableDynamicSpace - cH
            mH to cH
        } else {
            minMapHeight to minChartHeight
        }

        fun onPreview(option: RouteOption) {
            val inputPoints = option.inputPoints.ifEmpty { option.points }
            if (inputPoints.isEmpty()) return
            val coordsString = inputPoints.joinToString(";") { "${it.second},${it.first}" }
            val firstPoint = inputPoints.first()
            val profile = if (option.isOriginal) "trekking" else viewModel.bikeProfile
            val altPart = if (option.isOriginal) "" else "&alternativeidx=${option.alternativeIdx}"
            val previewUrl = "https://brouter.de/brouter-web/#map=13/${firstPoint.first}/${firstPoint.second}/standard&lonlats=$coordsString&profile=$profile$altPart"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))
            context.startActivity(intent)
        }


        val currentDialogRoute = selectedRouteForDialog
        if (currentDialogRoute != null) {
            RouteDetailDialog(
                option = currentDialogRoute,
                onDismiss = { selectedRouteForDialog = null },
                onShare = { 
                    scope.launch { viewModel.shareGpx(currentDialogRoute, context) }
                    selectedRouteForDialog = null
                },
                onEdit = { 
                    onPreview(currentDialogRoute)
                    selectedRouteForDialog = null
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
                    colors = colors,
                    mapType = viewModel.mapType,
                    selectedRouteIndex = pagerState.currentPage,
                    highlightedRouteIndex = viewModel.highlightedRouteIndex,
                    highlightedPointIndex = viewModel.highlightedPointIndex,
                    onRouteSelected = { index -> 
                        val option = viewModel.routeOptions.getOrNull(index)
                        if (option != null) {
                            selectedRouteForDialog = option
                            scope.launch { pagerState.scrollToPage(index) }
                        }
                    },
                    onPointSelected = { rIdx, pIdx ->
                        viewModel.setHighlight(rIdx, pIdx)
                        scope.launch { pagerState.scrollToPage(rIdx) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Row(modifier = Modifier.padding(16.dp).align(Alignment.TopEnd)) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.toggleMapType() },
                        modifier = Modifier.padding(end = 8.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Icon(
                            if (viewModel.mapType == "standard") Icons.Default.Layers else Icons.Default.Map,
                            contentDescription = "Kartentyp umschalten"
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = { viewModel.isMapFullscreen = false },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Vollbild beenden")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasAllAlts = viewModel.routeOptions.count { !it.isOriginal && it.alternativeIdx > 0 } >= 3
                    val isDirect = viewModel.bikeProfile == "direct"
                    val isBRouterImport = viewModel.debugUrl?.contains("brouter.de/brouter-web") == true
                    val canFetchAlts = viewModel.routeOptions.isNotEmpty() && !viewModel.isProcessing && !hasAllAlts && !isDirect && !isBRouterImport

                    ExposedDropdownMenuBox(
                        expanded = expandedProfile,
                        onExpandedChange = { expandedProfile = !expandedProfile },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = currentProfileLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProfile) },
                            modifier = Modifier.menuAnchor().fillMaxWidth().height(56.dp),
                            textStyle = MaterialTheme.typography.bodyMedium
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

                    if (canFetchAlts) {
                        IconButton(
                            onClick = { viewModel.fetchAlternatives() }
                        ) {
                            Icon(Icons.Default.Route, contentDescription = "Alternativen berechnen", tint = MaterialTheme.colorScheme.primary)
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


                Spacer(modifier = Modifier.height(8.dp))

                if (viewModel.routeOptions.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(mapHeight).clip(MaterialTheme.shapes.medium)) {
                        MapPreview(
                            options = viewModel.routeOptions,
                            visibleRoutes = viewModel.visibleRoutes,
                            currentProfile = viewModel.bikeProfile,
                            colors = colors,
                            mapType = viewModel.mapType,
                            selectedRouteIndex = pagerState.currentPage,
                            highlightedRouteIndex = viewModel.highlightedRouteIndex,
                            highlightedPointIndex = viewModel.highlightedPointIndex,
                            onRouteSelected = { index -> 
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            onPointSelected = { rIdx, pIdx ->
                                viewModel.setHighlight(rIdx, pIdx)
                                scope.launch { pagerState.animateScrollToPage(rIdx) }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Row(modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.toggleMapType() },
                                modifier = Modifier.padding(end = 8.dp),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(
                                    if (viewModel.mapType == "standard") Icons.Default.Layers else Icons.Default.Map,
                                    contentDescription = "Kartentyp umschalten"
                                )
                            }
                            SmallFloatingActionButton(
                                onClick = { viewModel.isMapFullscreen = true },
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Vollbild")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (viewModel.routeOptions.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        pageSpacing = 16.dp
                    ) { page ->
                        val option = viewModel.routeOptions[page]
                        val routeColorHex = when {
                            option.isOriginal -> viewModel.colorOriginal
                            option.title == "Hauptroute" -> viewModel.colorMain
                            option.title == "Alternative 1" -> viewModel.colorAlt1
                            option.title == "Alternative 2" -> viewModel.colorAlt2
                            option.title == "Alternative 3" -> viewModel.colorAlt3
                            else -> viewModel.colorAlt3
                        }
                        val routeColor = try { Color(android.graphics.Color.parseColor(routeColorHex)) } catch (e: Exception) { Color.Gray }

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp ,4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(option.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Checkbox(
                                        checked = viewModel.visibleRoutes.contains(page),
                                        onCheckedChange = { viewModel.toggleRouteVisibility(page) },
                                        colors = CheckboxDefaults.colors(checkedColor = routeColor)
                                    )
                                }

                                val km = String.format(Locale.US, "%.1f km", option.distanceMeters / 1000.0)
                                val anstieg = "${option.elevationGain} m"
                                val abstieg = "${option.elevationLoss} m"
                                val timeText = if (option.totalTimeSeconds > 0) {
                                    val h = option.totalTimeSeconds / 3600
                                    val m = (option.totalTimeSeconds % 3600) / 60
                                    if (h > 0) "${h} h ${m} min" else "${m} min"
                                } else ""
                                
                                Text("➜ $km ▲$anstieg ▼$abstieg${if(timeText.isNotEmpty()) " \uD83D\uDD57 $timeText" else ""}",
                                    style = MaterialTheme.typography.bodySmall)

                                if (option.altitudes.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    ElevationChart(
                                        option = option,
                                        highlightedIndex = if (viewModel.highlightedRouteIndex == page) viewModel.highlightedPointIndex else null,
                                        onPointHighlighted = { idx -> viewModel.setHighlight(page, idx) },
                                        modifier = Modifier.fillMaxWidth().height(chartHeight).padding(top = 4.dp)
                                    )
                                }
                                
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                                    OutlinedButton(
                                        onClick = { onPreview(option) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                                        Text(" Edit", style = MaterialTheme.typography.labelMedium)
                                    }

                                    Button(
                                        onClick = { scope.launch { viewModel.shareGpx(option, context) } },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                                        Text(" Teilen", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    if (viewModel.routeOptions.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            repeat(viewModel.routeOptions.size) { iteration ->
                                val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .size(6.dp)
                                )
                            }
                        }
                    }
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
}


@Composable
fun MapPreview(
    options: List<RouteOption>,
    visibleRoutes: Set<Int>,
    currentProfile: String,
    colors: List<String>,
    mapType: String = "standard",
    selectedRouteIndex: Int = -1,
    highlightedRouteIndex: Int? = null,
    highlightedPointIndex: Int? = null,
    onRouteSelected: (Int) -> Unit,
    onPointSelected: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(options, visibleRoutes, currentProfile, colors, mapType) {
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

            val colorObj = android.graphics.Color.parseColor(argbHex)
            val opacity = (android.graphics.Color.alpha(colorObj) / 255.0)
            val rgbHex = String.format("#%06X", (0xFFFFFF and colorObj))

            val km = String.format(Locale.US, "%.1f km", opt.distanceMeters / 1000.0)
            val timeText = if (opt.totalTimeSeconds > 0) {
                val h = opt.totalTimeSeconds / 3600
                val m = (opt.totalTimeSeconds % 3600) / 60
                if (h > 0) "${h}h ${m}min" else "${m}min"
            } else ""
            """{"index":$index, "km":"$km", "time":"$timeText", "color":"$rgbHex", "opacity":$opacity, "points":[$coords], "isVisible":$isVisible,"isOriginal":${opt.isOriginal}}"""
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
                .label-inner { border: 1px solid #333; border-radius: 3px; padding: 1px 2px; font-size: 12px; line-height: 1.1; font-weight: bold; text-align: center; box-shadow: 0 1px 2px rgba(0,0,0,0.2); white-space: nowrap; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map');
                
                var osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OSM' });
                var satellite = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
                    attribution: 'Tiles &copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EBP, and the GIS User Community'
                });

                if ('$mapType' === 'satellite') {
                    satellite.addTo(map);
                } else {
                    osm.addTo(map);
                }

                var routesData = $jsonString;
                var routeLayers = [];
                var labelLayers = [];
                var highlightMarker = null;
                var group = new L.featureGroup();

                function isLight(color) {
                    var r, g, b, hsp;
                    color = color.replace('#', '');
                    if (color.length === 3) color = color.split('').map(function(s){return s+s;}).join('');
                    r = parseInt(color.substring(0,2),16); g = parseInt(color.substring(2,4),16); b = parseInt(color.substring(4,6),16);
                    hsp = Math.sqrt(0.299 * (r * r) + 0.587 * (g * g) + 0.114 * (b * b));
                    return hsp > 127.5;
                }

                function highlightRoute(selectedIndex) {
                    routeLayers.forEach(function(layer, index) {
                        var route = routesData[index];
                        if (!route.isVisible || !layer) return;
                        var isSelected = (index === selectedIndex);
                        
                        layer.setStyle({
                            opacity: isSelected ? 1.0 : (route.opacity * 0.7),
                            weight: isSelected ? 5 : ((index === 0 && !route.isOriginal) ? 4 : 2.5)
                        });
                        if (isSelected) layer.bringToFront();
                        
                        var label = labelLayers[index];
                        if (label) {
                            var textColor = isLight(route.color) ? 'black' : 'white';
                            var opacity = isSelected ? 1.0 : (route.opacity * 0.8);
                            var labelText = route.km;
                            if (route.isOriginal) labelText = "G: " + labelText;
                            
                            var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + (isSelected ? 'black' : route.color) + '; border-width:' + (isSelected ? '2px' : '1px') + '; color:' + textColor + '; opacity:' + opacity + ';">' + labelText + '</div>';
                            label.setContent(content);
                            if (isSelected) label.bringToFront();
                        }
                    });
                    if (highlightMarker) highlightMarker.bringToFront();
                }

                function setHighlightMarker(routeIdx, pointIdx) {
                    if (highlightMarker) {
                        map.removeLayer(highlightMarker);
                        highlightMarker = null;
                    }
                    if (routeIdx === null || pointIdx === null || routeIdx === undefined || pointIdx === undefined) return;
                    
                    var route = routesData[routeIdx];
                    if (!route || !route.isVisible || !route.points[pointIdx]) return;
                    
                    var pos = route.points[pointIdx];
                    highlightMarker = L.circleMarker(pos, {
                        radius: 8,
                        color: 'white',
                        weight: 2,
                        opacity: 1,
                        fillColor: 'red',
                        fillOpacity: 1,
                        interactive: false
                    }).addTo(map);
                    highlightMarker.bringToFront();
                    map.panTo(pos);
                }

                routesData.forEach(function(route, index) {
                    if (!route.isVisible || route.points.length === 0) {
                        routeLayers.push(null);
                        labelLayers.push(null);
                        return;
                    }

                    var polyline = L.polyline(route.points, { 
                        color: route.color, 
                        opacity: route.opacity * 0.7,
                        weight: (index === 0 && !route.isOriginal) ? 4 : 2.5,
                        interactive: true 
                    }).addTo(map);
                    
                    var openFn = function() { if (window.Android) window.Android.selectRoute(route.index); };
                    polyline.on('click', function(e) {
                        if (window.Android) {
                            var p = e.latlng;
                            var minD = Infinity;
                            var bestIdx = 0;
                            for (var i=0; i<route.points.length; i++) {
                                var rp = route.points[i];
                                var d = Math.pow(p.lat - rp[0], 2) + Math.pow(p.lng - rp[1], 2);
                                if (d < minD) { minD = d; bestIdx = i; }
                            }
                            window.Android.selectPoint(route.index, bestIdx);
                        }
                    });
                    
                    var tooltip = null;
                    if (route.points.length > 2) {
                        var positions = [0.5, 0.3, 0.7, 0.4, 0.6];
                        var posFactor = positions[index % positions.length];
                        var targetIdx = Math.floor(route.points.length * posFactor);
                        var pos = route.points[targetIdx];
                        var textColor = isLight(route.color) ? 'black' : 'white';
                        var labelText = route.km;
                        if (route.isOriginal) labelText = "G: " + labelText;
                        
                        var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + route.color + '; color:' + textColor + '; opacity:' + (route.opacity * 0.8) + ';">' + labelText + '</div>';
                        tooltip = L.tooltip({ permanent: true, direction: 'top', className: 'route-label', interactive: true, offset: [0, -5] }).setLatLng(pos).setContent(content).addTo(map);
                        tooltip.on('click', openFn);
                    }
                    
                    routeLayers.push(polyline);
                    labelLayers.push(tooltip);
                    group.addLayer(polyline);
                });

                if (group.getLayers().length > 0) {
                    setTimeout(function() { 
                        map.invalidateSize(); 
                        map.fitBounds(group.getBounds().pad(0.1)); 
                        highlightRoute($selectedRouteIndex);
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
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun selectRoute(index: Int) { onRouteSelected(index) }

                    @android.webkit.JavascriptInterface
                    fun selectPoint(routeIdx: Int, pointIdx: Int) { onPointSelected(routeIdx, pointIdx) }
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
            } else {
                webView.evaluateJavascript("highlightRoute($selectedRouteIndex)", null)
                webView.evaluateJavascript("setHighlightMarker($highlightedRouteIndex, $highlightedPointIndex)", null)
            }
        }
    )
}
