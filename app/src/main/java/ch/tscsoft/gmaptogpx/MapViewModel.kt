package ch.tscsoft.gmaptogpx

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.tscsoft.gmaptogpx.data.*
import ch.tscsoft.gmaptogpx.data.models.*
import ch.tscsoft.gmaptogpx.data.remote.BRouterService
import ch.tscsoft.gmaptogpx.data.remote.GeocodingService
import ch.tscsoft.gmaptogpx.data.remote.PoiService
import ch.tscsoft.gmaptogpx.data.remote.WeatherService
import ch.tscsoft.gmaptogpx.util.GpxUtil
import ch.tscsoft.gmaptogpx.util.LocationUtil
import ch.tscsoft.gmaptogpx.util.UrlResolver
import android.content.Intent
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    private val bRouterService: BRouterService,
    private val weatherService: WeatherService,
    private val mapDownloadManager: MapDownloadManager,
    private val tileCacheInterceptor: TileCacheInterceptor,
    private val geocodingService: GeocodingService,
    private val poiService: PoiService
) : ViewModel() {

    private var prefs: SharedPreferences? = null
    private var lastSharedText: String? = null
    private var lastPoints: List<Pair<Double, Double>> = emptyList()
    private var calculationJob: Job? = null

    val allFolders: Flow<List<BookmarkFolder>> = repository.allFolders
    val rootBookmarks: Flow<List<BookmarkRoute>> = repository.rootBookmarks
    val downloadProgress = mapDownloadManager.progress

    // --- New Feature State ---
    var waypoints by mutableStateOf<List<Waypoint>>(emptyList())
        private set
    var searchSuggestions by mutableStateOf<List<SearchSuggestion>>(emptyList())
        private set
    var activePois by mutableStateOf<List<Poi>>(emptyList())
        private set
    var enabledPoiTypes by mutableStateOf<Set<PoiType>>(emptySet())
        private set
    var showRoutePlanner by mutableStateOf(false)
    var searchJob: Job? = null
    var poiJob: Job? = null
    // -------------------------

    var status by mutableStateOf("Warte auf Google Maps Link...")
        private set
    var isProcessing by mutableStateOf(false)
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

    var showWeather by mutableStateOf(true)
    var weatherStartTime by mutableLongStateOf(System.currentTimeMillis())

    var userLocation by mutableStateOf<Pair<Double, Double>?>(null)
    var recordedPath by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set
    var centerOnUserRequested by mutableStateOf(false)
    var isFollowMode by mutableStateOf(false)
        private set

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // --- New Feature Methods ---
    fun addWaypoint(lat: Double, lon: Double, context: Context, index: Int? = null) {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) { LocationUtil.getFullAddress(lat, lon, context) }
            val newWaypoints = waypoints.toMutableList()
            val waypoint = Waypoint(lat, lon, address)
            if (index != null && index in newWaypoints.indices) {
                newWaypoints.add(index, waypoint)
            } else {
                newWaypoints.add(waypoint)
            }
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    fun removeWaypoint(index: Int) {
        if (index in waypoints.indices) {
            val newWaypoints = waypoints.toMutableList()
            newWaypoints.removeAt(index)
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    fun moveWaypoint(from: Int, to: Int) {
        if (from in waypoints.indices && to in waypoints.indices) {
            val newWaypoints = waypoints.toMutableList()
            val item = newWaypoints.removeAt(from)
            newWaypoints.add(to, item)
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    fun updateWaypoint(index: Int, lat: Double, lon: Double, context: Context) {
        if (index in waypoints.indices) {
            viewModelScope.launch {
                val address = withContext(Dispatchers.IO) { LocationUtil.getFullAddress(lat, lon, context) }
                val newWaypoints = waypoints.toMutableList()
                newWaypoints[index] = Waypoint(lat, lon, address)
                waypoints = newWaypoints
                calculateRouteFromWaypoints()
            }
        }
    }

    fun clearWaypoints() {
        waypoints = emptyList()
        routeOptions = emptyList()
        visibleRoutes = emptySet()
        lastPoints = emptyList()
        lastSharedText = null
        debugUrl = null
        highlightedPointIndex = null
        highlightedRouteIndex = null
        status = "Warte auf Google Maps Link oder neue Punkte..."
        centerOnUserRequested = true
    }

    fun setStartPoint(lat: Double, lon: Double, context: Context) {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) { LocationUtil.getFullAddress(lat, lon, context) }
            val newWaypoints = waypoints.toMutableList()
            val waypoint = Waypoint(lat, lon, address)
            if (newWaypoints.isEmpty()) {
                newWaypoints.add(waypoint)
            } else {
                newWaypoints[0] = waypoint
            }
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    fun setEndPoint(lat: Double, lon: Double, context: Context) {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) { LocationUtil.getFullAddress(lat, lon, context) }
            val newWaypoints = waypoints.toMutableList()
            newWaypoints.add(Waypoint(lat, lon, address))
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    fun addIntermediateWaypoint(lat: Double, lon: Double, context: Context) {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) { LocationUtil.getFullAddress(lat, lon, context) }
            val newWaypoints = waypoints.toMutableList()
            val waypoint = Waypoint(lat, lon, address)
            if (newWaypoints.size >= 2) {
                newWaypoints.add(newWaypoints.size - 1, waypoint)
            } else {
                newWaypoints.add(waypoint)
            }
            waypoints = newWaypoints
            calculateRouteFromWaypoints()
        }
    }

    private fun calculateRouteFromWaypoints() {
        if (waypoints.size < 2) {
            routeOptions = emptyList()
            return
        }

        calculationJob?.cancel()
        isProcessing = true
        status = "Berechne Route..."
        
        calculationJob = viewModelScope.launch {
            try {
                val coords = waypoints.map { it.lat to it.lon }
                lastPoints = coords
                
                val options = mutableListOf<RouteOption>()
                val profileName = getProfileLabel(bikeProfile)

                if (bikeProfile == "direct") {
                    val title = "Manuelle Route (Direkt)"
                    val dist = GpxUtil.calculateDistance(coords)
                    val gpx = GpxUtil.createGpx(coords, trackName = title)
                    options.add(RouteOption(title, coords, emptyList(), emptyList(), gpx, true, coords, distanceMeters = dist))
                    routeOptions = options
                    visibleRoutes = setOf(0)
                    status = "Direkt-Route berechnet!"
                } else {
                    val mainResult = bRouterService.getBikeRoute(coords, bikeProfile, 0)
                    val title = "Manuelle Route"
                    options.add(RouteOption(
                        title = title,
                        points = mainResult.points,
                        altitudes = mainResult.altitudes,
                        distances = mainResult.distances,
                        gpxContent = GpxUtil.createGpx(mainResult.points, mainResult.altitudes, mainResult.segments, trackName = title),
                        inputPoints = coords,
                        alternativeIdx = 0,
                        distanceMeters = mainResult.distance,
                        elevationGain = mainResult.elevationGain,
                        elevationLoss = mainResult.elevationLoss,
                        totalTimeSeconds = mainResult.totalTimeSeconds,
                        segments = mainResult.segments,
                        surfaceSummary = bRouterService.createSurfaceSummary(mainResult.segments)
                    ))
                    
                    routeOptions = options.toList()
                    visibleRoutes = setOf(0)
                    status = "Hauptroute berechnet..."

                    // Add automatic alternatives if autoAltCount > 0
                    for (i in 1..autoAltCount) {
                        status = "Berechne $profileName Alternative $i..."
                        val altResult = bRouterService.getBikeRoute(coords, bikeProfile, i)
                        val altRoute = altResult.points
                        if (altRoute != mainResult.points && altRoute != coords && options.none { it.points == altRoute }) {
                            val altTitle = "Alternative $i"
                            options.add(RouteOption(
                                title = altTitle,
                                points = altRoute,
                                altitudes = altResult.altitudes,
                                distances = altResult.distances,
                                gpxContent = GpxUtil.createGpx(altRoute, altResult.altitudes, altResult.segments, trackName = altTitle),
                                inputPoints = coords,
                                alternativeIdx = i,
                                distanceMeters = altResult.distance,
                                elevationGain = altResult.elevationGain,
                                elevationLoss = altResult.elevationLoss,
                                totalTimeSeconds = altResult.totalTimeSeconds,
                                segments = altResult.segments,
                                surfaceSummary = bRouterService.createSurfaceSummary(altResult.segments)
                            ))
                            routeOptions = options.toList()
                            visibleRoutes = options.indices.toSet()
                        }
                    }
                    status = if (options.size > 1) "Route mit Alternativen berechnet!" else "Route berechnet!"
                }
                
                if (showWeather) {
                    val updatedOptions = routeOptions.map { weatherService.fetchWeatherForRoute(it, showWeather, weatherStartTime) }
                    routeOptions = updatedOptions
                }
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            searchSuggestions = geocodingService.search(query)
        }
    }

    fun togglePoiType(type: PoiType) {
        enabledPoiTypes = if (enabledPoiTypes.contains(type)) {
            enabledPoiTypes - type
        } else {
            enabledPoiTypes + type
        }
        // Force refresh if we have a bbox (this needs to be triggered from UI when map moves)
    }

    fun refreshPois(south: Double, west: Double, north: Double, east: Double) {
        if (enabledPoiTypes.isEmpty()) {
            activePois = emptyList()
            return
        }
        poiJob?.cancel()
        poiJob = viewModelScope.launch {
            delay(1000)
            activePois = poiService.getPois(south, west, north, east, enabledPoiTypes)
        }
    }
    // ----------------------------

    fun updateLocation(context: Context, centerOnUser: Boolean = false, highlightOnRoute: Int? = null) {
        val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(context).also { fusedLocationClient = it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        userLocation = location.latitude to location.longitude
                        if (centerOnUser) {
                            centerOnUserRequested = true
                        }
                        if (highlightOnRoute != null) {
                            highlightNearestPointToUser(highlightOnRoute)
                        }
                    }
                }
        }
    }

    fun toggleFollowMode(context: Context, routeIndex: Int?) {
        if (isFollowMode) {
            stopFollowMode()
        } else {
            startFollowMode(context, routeIndex)
        }
    }

    private fun startFollowMode(context: Context, routeIndex: Int?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(context).also { fusedLocationClient = it }
        
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val newPos = location.latitude to location.longitude
                userLocation = newPos
                recordedPath = recordedPath + newPos
                centerOnUserRequested = true
                if (routeIndex != null) {
                    highlightNearestPointToUser(routeIndex)
                }
            }
        }

        client.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        isFollowMode = true
    }

    fun stopFollowMode() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        locationCallback = null
        isFollowMode = false
    }

    fun clearRecordedPath() {
        recordedPath = emptyList()
    }

    fun refreshWeather(context: Context) {
        if (!showWeather) {
            routeOptions = routeOptions.map { it.copy(weatherSamples = emptyList()) }
            return
        }
        viewModelScope.launch {
            status = "Aktualisiere Wetter..."
            val updatedOptions = routeOptions.map { weatherService.fetchWeatherForRoute(it, showWeather, weatherStartTime) }
            routeOptions = updatedOptions
            status = "Wetter aktualisiert!"
        }
    }

    fun saveRecordedPath(context: Context) {
        if (recordedPath.isEmpty()) return
        
        viewModelScope.launch {
            val startName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(recordedPath.first().first, recordedPath.first().second, context) }
            val endName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(recordedPath.last().first, recordedPath.last().second, context) }
            val locTitle = if (startName != null && endName != null) "$startName -> $endName" else null
            
            val timeTitle = "Aufzeichnung ${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())}"
            val finalTitle = if (locTitle != null) "$locTitle ($timeTitle)" else timeTitle
            
            val gpx = GpxUtil.createGpx(recordedPath, trackName = finalTitle)
            saveBookmark(RouteOption(finalTitle, recordedPath, gpxContent = gpx), finalTitle)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopFollowMode()
    }

    fun initPrefs(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            bikeProfile = prefs?.getString("bike_profile", "fastbike") ?: "fastbike"
            autoAltCount = prefs?.getInt("auto_alt_count", 0) ?: 0
            mapType = prefs?.getString("map_type", "standard") ?: "standard"
            showWeather = prefs?.getBoolean("show_weather", true) ?: true

            colorMain = prefs?.getString("color_main", "#FF0000FF") ?: "#FF0000FF"
            colorAlt1 = prefs?.getString("color_alt1", "#FFFF00FF") ?: "#FFFF00FF"
            colorAlt2 = prefs?.getString("color_alt2", "#FF00FFFF") ?: "#FF00FFFF"
            colorAlt3 = prefs?.getString("color_alt3", "#FFFF8800") ?: "#FFFF8800"
            colorOriginal = prefs?.getString("color_original", "#99666666") ?: "#99666666"
        }
    }

    fun refresh(context: Context) {
        lastSharedText?.let {
            processSharedText(it, context)
        }
    }

    fun updateProfile(newProfile: String, context: Context) {
        bikeProfile = newProfile
        prefs?.edit()?.putString("bike_profile", newProfile)?.apply()
        
        if (waypoints.isNotEmpty()) {
            calculateRouteFromWaypoints()
        } else {
            lastSharedText?.let {
                processSharedText(it, context)
            }
        }
    }

    fun updateAutoAltCount(count: Int, context: Context) {
        autoAltCount = count
        prefs?.edit()?.putInt("auto_alt_count", count)?.apply()
        
        if (waypoints.isNotEmpty()) {
            calculateRouteFromWaypoints()
        } else {
            lastSharedText?.let {
                processSharedText(it, context)
            }
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

    fun highlightNearestPointToUser(routeIdx: Int) {
        val loc = userLocation ?: return
        val option = routeOptions.getOrNull(routeIdx) ?: return
        if (option.points.isEmpty()) return

        var minDist = Double.MAX_VALUE
        var bestIdx = 0
        
        option.points.forEachIndexed { idx, point ->
            val d = Math.pow(loc.first - point.first, 2.0) + Math.pow(loc.second - point.second, 2.0)
            if (d < minDist) {
                minDist = d
                bestIdx = idx
            }
        }
        setHighlight(routeIdx, bestIdx)
    }

    fun updateMapType(type: String) {
        mapType = type
        prefs?.edit()?.putString("map_type", type)?.apply()
    }

    fun processSharedText(text: String, context: Context) {
        lastSharedText = text
        initPrefs(context)

        val url = UrlResolver.extractUrl(text)
        if (url == null) {
            status = "Kein Link gefunden."
            return
        }

        isProcessing = true
        status = "Verarbeite Route..."
        routeOptions = emptyList()

        calculationJob?.cancel()
        calculationJob = viewModelScope.launch {
            try {
                var resolvedUrl = UrlResolver.resolveUrl(url)
                debugUrl = resolvedUrl
                var points = UrlResolver.extractAllCoordinates(resolvedUrl)
                
                if (points.isEmpty() && resolvedUrl.contains("goo.gl")) {
                    status = "Erneuter Versuch der URL-Auflösung..."
                    delay(800)
                    resolvedUrl = UrlResolver.resolveUrl(url)
                    debugUrl = resolvedUrl
                    points = UrlResolver.extractAllCoordinates(resolvedUrl)
                }

                if (points.isNotEmpty()) {
                    lastPoints = points
                    // Convert points to waypoints with addresses in background
                    status = "Suche Adressen..."
                    waypoints = withContext(Dispatchers.IO) {
                        points.map { 
                            Waypoint(it.first, it.second, LocationUtil.getFullAddress(it.first, it.second, context))
                        }
                    }
                    val options = mutableListOf<RouteOption>()
                    val isBRouter = resolvedUrl.contains("brouter.de/brouter-web")

                    val startName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(points.first().first, points.first().second, context) }
                    val endName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(points.last().first, points.last().second, context) }
                    val routeTitle = if (startName != null && endName != null) "$startName -> $endName" else null
                    
                    if (bikeProfile == "direct" || points.size < 2) {
                        val title = routeTitle ?: "Importierte Punkte"
                        val googleGpx = GpxUtil.createGpx(points, trackName = title)
                        val dist = GpxUtil.calculateDistance(points)
                        options.add(RouteOption(title, points, emptyList(), emptyList(), googleGpx, true, points, distanceMeters = dist))
                    } else if (isBRouter) {
                        val urlProfile = "profile=([^&]+)".toRegex().find(resolvedUrl)?.groupValues?.get(1)
                        val effectiveProfile = urlProfile ?: bikeProfile
                        val profileName = getProfileLabel(effectiveProfile)
                        
                        status = "Lade BRouter Route ($profileName)..."
                        val result = bRouterService.getBikeRoute(points, effectiveProfile, 0)
                        val title = routeTitle ?: "BRouter Import"
                        options.add(RouteOption(title, result.points, result.altitudes, result.distances, GpxUtil.createGpx(result.points, result.altitudes, result.segments, trackName = title), inputPoints = points, alternativeIdx = 0, distanceMeters = result.distance, elevationGain = result.elevationGain, elevationLoss = result.elevationLoss, totalTimeSeconds = result.totalTimeSeconds, segments = result.segments, surfaceSummary = bRouterService.createSurfaceSummary(result.segments)))
                    } else {
                        val profileName = getProfileLabel(bikeProfile)
                        
                        status = "Berechne $profileName..."
                        val mainResult = bRouterService.getBikeRoute(points, bikeProfile, 0)
                        val mainRoute = mainResult.points
                        val mainTitle = routeTitle ?: "Hauptroute"
                        
                        options.add(RouteOption(mainTitle, mainRoute, mainResult.altitudes, mainResult.distances, GpxUtil.createGpx(mainRoute, mainResult.altitudes, mainResult.segments, trackName = mainTitle), inputPoints = points, alternativeIdx = 0, distanceMeters = mainResult.distance, elevationGain = mainResult.elevationGain, elevationLoss = mainResult.elevationLoss, totalTimeSeconds = mainResult.totalTimeSeconds, segments = mainResult.segments, surfaceSummary = bRouterService.createSurfaceSummary(mainResult.segments)))
                        
                        for (i in 1..autoAltCount) {
                            status = "Berechne $profileName Alternative $i..."
                            val altResult = bRouterService.getBikeRoute(points, bikeProfile, i)
                            val altRoute = altResult.points
                            if (altRoute != mainRoute && altRoute != points && options.none { it.points == altRoute }) {
                                val altTitle = if (routeTitle != null) "Alt $i: $routeTitle" else "Alternative $i"
                                options.add(RouteOption(altTitle, altRoute, altResult.altitudes, altResult.distances, GpxUtil.createGpx(altRoute, altResult.altitudes, altResult.segments, trackName = altTitle), inputPoints = points, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds, segments = altResult.segments, surfaceSummary = bRouterService.createSurfaceSummary(altResult.segments)))
                            }
                        }
                    }
                    
                    routeOptions = options
                    visibleRoutes = options.indices.toSet()
                    status = "Route bereit!"
                    
                    if (showWeather) {
                        viewModelScope.launch {
                            val updatedOptions = routeOptions.map { weatherService.fetchWeatherForRoute(it, showWeather, weatherStartTime) }
                            routeOptions = updatedOptions
                        }
                    }
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

    fun processSharedUri(uri: Uri, context: Context) {
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
                    processGpxContent(gpxContent, "Importierte GPX", context)
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

    private suspend fun processGpxContent(gpxContent: String, title: String, context: Context) {
        val result = GpxUtil.parseGpx(gpxContent)
        val points = result.points
        
        if (points.isNotEmpty()) {
            lastPoints = points

            var finalTitle = title
            if (title == "Importierte GPX" || title == "BRouter Import") {
                val startName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(points.first().first, points.first().second, context) }
                val endName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(points.last().first, points.last().second, context) }
                if (startName != null && endName != null) {
                    finalTitle = "$startName -> $endName"
                }
            }

            val option = RouteOption(
                title = finalTitle,
                points = points,
                altitudes = result.altitudes,
                distances = result.distances,
                gpxContent = GpxUtil.createGpx(points, result.altitudes, result.segments, trackName = finalTitle),
                isOriginal = true,
                inputPoints = points,
                distanceMeters = result.distanceMeters,
                elevationGain = result.elevationGain,
                elevationLoss = result.elevationLoss,
                segments = result.segments,
                surfaceSummary = if (result.segments.isNotEmpty()) bRouterService.createSurfaceSummary(result.segments) else emptyMap()
            )
            routeOptions = listOf(option)
            visibleRoutes = setOf(0)
            status = "$finalTitle geladen!"
            lastSharedText = null
            
            if (showWeather) {
                viewModelScope.launch {
                    val updatedOptions = routeOptions.map { weatherService.fetchWeatherForRoute(it, showWeather, weatherStartTime) }
                    routeOptions = updatedOptions
                }
            }
        } else {
            status = "Keine Wegpunkte in GPX gefunden."
        }
    }

    fun saveBookmark(option: RouteOption, title: String, folderId: Long? = null) {
        viewModelScope.launch {
            repository.insertBookmark(
                title = title,
                gpxContent = option.gpxContent,
                folderId = folderId,
                distanceMeters = option.distanceMeters,
                elevationGain = option.elevationGain,
                elevationLoss = option.elevationLoss,
                totalTimeSeconds = option.totalTimeSeconds
            )
        }
    }

    fun deleteBookmark(bookmark: BookmarkRoute) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun addFolder(name: String) {
        viewModelScope.launch {
            repository.insertFolder(name)
        }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
        }
    }

    fun updateFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            repository.updateFolder(folder)
        }
    }

    fun updateBookmark(bookmark: BookmarkRoute) {
        viewModelScope.launch {
            repository.updateBookmark(bookmark)
        }
    }

    fun getBookmarksInFolder(folderId: Long) = repository.getBookmarksInFolder(folderId)

    fun loadBookmark(bookmark: BookmarkRoute, context: Context) {
        initPrefs(context)
        viewModelScope.launch {
            processGpxContent(bookmark.gpxContent, bookmark.title, context)
        }
    }

    private var downloadJob: Job? = null

    fun downloadMapForRoute(points: List<Pair<Double, Double>>) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            mapDownloadManager.downloadRouteTiles(points, mapType)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        mapDownloadManager.cancelDownload()
    }

    fun getCacheSize(): Long = tileCacheInterceptor.getCacheSize()
    fun clearCache() = tileCacheInterceptor.clearCache()

    fun getInterceptor(): TileCacheInterceptor = tileCacheInterceptor

    fun updateShowWeather(show: Boolean, context: Context) {
        showWeather = show
        prefs?.edit()?.putBoolean("show_weather", show)?.apply()
        refreshWeather(context)
    }

    private fun getProfileLabel(id: String): String {
        return ROUTE_PROFILES.find { it.first == id }?.second ?: id
    }

    fun fetchAlternatives(context: Context) {
        if (lastPoints.isEmpty() || isProcessing) return
        
        calculationJob?.cancel()
        isProcessing = true
        calculationJob = viewModelScope.launch {
            try {
                val currentOptions = routeOptions.toMutableList()
                val profileName = getProfileLabel(bikeProfile)
                val mainRoute = currentOptions.find { it.alternativeIdx == 0 && !it.isOriginal }?.points
                
                val startName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(lastPoints.first().first, lastPoints.first().second, context) }
                val endName = withContext(Dispatchers.IO) { LocationUtil.getPlaceName(lastPoints.last().first, lastPoints.last().second, context) }
                val routeTitle = if (startName != null && endName != null) "$startName -> $endName" else null

                for (i in 1..3) {
                    if (currentOptions.any { it.alternativeIdx == i && !it.isOriginal }) continue
                    
                    status = "Berechne $profileName Alternative $i..."
                    val altResult = bRouterService.getBikeRoute(lastPoints, bikeProfile, i)
                    val altRoute = altResult.points
                    
                    if (altRoute != lastPoints && altRoute != mainRoute && currentOptions.none { it.points == altRoute }) {
                        val altTitle = if (routeTitle != null) "Alt $i: $routeTitle" else "Alternative $i"
                        currentOptions.add(RouteOption(altTitle, altRoute, altResult.altitudes, altResult.distances, GpxUtil.createGpx(altRoute, altResult.altitudes, altResult.segments, trackName = altTitle), inputPoints = lastPoints, alternativeIdx = i, distanceMeters = altResult.distance, elevationGain = altResult.elevationGain, elevationLoss = altResult.elevationLoss, totalTimeSeconds = altResult.totalTimeSeconds, segments = altResult.segments, surfaceSummary = bRouterService.createSurfaceSummary(altResult.segments)))
                        routeOptions = currentOptions.toList()
                        visibleRoutes = currentOptions.indices.toSet()
                    }
                }
                status = "Alternativen geladen!"

                if (showWeather) {
                    val updatedOptions = routeOptions.map {
                        if (it.weatherSamples.isEmpty()) weatherService.fetchWeatherForRoute(it, showWeather, weatherStartTime) else it
                    }
                    routeOptions = updatedOptions
                }
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    suspend fun shareGpx(option: RouteOption, context: Context) {
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

    private suspend fun prepareShareUri(content: String, context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val shareDir = java.io.File(context.cacheDir, "shared_gpx")
            shareDir.mkdirs()
            val file = java.io.File(shareDir, "GoogleMapsRoute.gpx")
            file.writeText(content)
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }
}
