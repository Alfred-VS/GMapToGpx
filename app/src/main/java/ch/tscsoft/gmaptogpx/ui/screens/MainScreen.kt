package ch.tscsoft.gmaptogpx.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ch.tscsoft.gmaptogpx.MapViewModel
import ch.tscsoft.gmaptogpx.data.models.RouteOption
import ch.tscsoft.gmaptogpx.ui.components.*
import ch.tscsoft.gmaptogpx.ui.dialogs.RouteDetailDialog
import ch.tscsoft.gmaptogpx.ui.dialogs.SaveBookmarkDialog
import ch.tscsoft.gmaptogpx.ui.dialogs.WeatherSettingsDialog
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { viewModel.routeOptions.size })

    val downloadProgress by viewModel.downloadProgress.collectAsState()

    val colors = remember(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal) {
        listOf(viewModel.colorMain, viewModel.colorAlt1, viewModel.colorAlt2, viewModel.colorAlt3, viewModel.colorOriginal)
    }

    var selectedRouteForDialog by remember { mutableStateOf<RouteOption?>(null) }
    var showWeatherSettings by remember { mutableStateOf(false) }
    var showPoiSettings by remember { mutableStateOf(false) }
    var showWaypointsSheet by remember { mutableStateOf(false) }

    if (viewModel.showRoutePlanner) {
        ch.tscsoft.gmaptogpx.ui.dialogs.RoutePlannerDialog(viewModel) { viewModel.showRoutePlanner = false }
    }

    if (showWeatherSettings) {
        WeatherSettingsDialog(viewModel) { showWeatherSettings = false }
    }


    if (showPoiSettings) {
        ch.tscsoft.gmaptogpx.ui.dialogs.PoiSettingsDialog(
            enabledTypes = viewModel.enabledPoiTypes,
            onToggleType = { viewModel.togglePoiType(it) },
            onDismiss = { showPoiSettings = false }
        )
    }

    if (showWaypointsSheet) {
        ModalBottomSheet(onDismissRequest = { showWaypointsSheet = false }) {
            WaypointList(
                waypoints = viewModel.waypoints,
                onDelete = { viewModel.removeWaypoint(it) },
                onMove = { from, to -> viewModel.moveWaypoint(from, to) },
                onClearAll = { viewModel.clearWaypoints() }
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.updateLocation(context, centerOnUser = true, highlightOnRoute = pagerState.currentPage)
        }
    }

    fun onMyLocationClick() {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fineLocation == PackageManager.PERMISSION_GRANTED || coarseLocation == PackageManager.PERMISSION_GRANTED) {
            viewModel.updateLocation(context, centerOnUser = true, highlightOnRoute = pagerState.currentPage)
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.updateLocation(context, centerOnUser = true)
    }

    LaunchedEffect(pagerState.currentPage) {
        if (viewModel.highlightedRouteIndex != pagerState.currentPage) {
            viewModel.setHighlight(null, null)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val fixedElementsHeight = 210.dp
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

        Column(modifier = Modifier.fillMaxSize()) {
            // ... the rest of the existing content but MapPreview needs updated props
        }

        // Wait, I should wrap the existing logic in the column or box.
        // Actually, the existing logic is a Column with scroll. 
        // I'll replace the whole MapPreview calls.

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
                viewModel = viewModel,
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
                        colors = colors,
                        interceptor = viewModel.getInterceptor(),
                        mapType = viewModel.mapType,
                        showWeather = viewModel.showWeather,
                        selectedRouteIndex = pagerState.currentPage,
                        highlightedRouteIndex = viewModel.highlightedRouteIndex,
                        highlightedPointIndex = viewModel.highlightedPointIndex,
                        userLocation = viewModel.userLocation,
                        recordedPath = viewModel.recordedPath,
                        centerOnUserRequested = viewModel.centerOnUserRequested,
                        onCenterOnUserHandled = { viewModel.centerOnUserRequested = false },
                        onUserPositionSelected = { viewModel.highlightNearestPointToUser(pagerState.currentPage) },
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
                        waypoints = viewModel.waypoints,
                        pois = viewModel.activePois,
                        onMapLongClick = { lat, lon -> viewModel.addWaypoint(lat, lon, context) },
                        onPoiClick = { viewModel.addWaypoint(it.lat, it.lon, context) },
                        onMapBoundsChanged = { s, w, n, e -> viewModel.refreshPois(s, w, n, e) },
                        onWaypointMoved = { index, lat, lon -> viewModel.updateWaypoint(index, lat, lon, context) },
                        modifier = Modifier.fillMaxSize()
                    )
                    Row(modifier = Modifier.padding(top = 100.dp, start = 8.dp).align(Alignment.TopStart)) {
                        if (viewModel.waypoints.isNotEmpty() || viewModel.routeOptions.isNotEmpty()) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.clearWaypoints() },
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Alles löschen", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Row(modifier = Modifier.padding(16.dp).align(Alignment.TopEnd)) {
                        MapLayerSelector(
                            currentType = viewModel.mapType,
                            onTypeSelected = { viewModel.updateMapType(it) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        SmallFloatingActionButton(
                            onClick = { 
                                if (viewModel.showWeather) {
                                    viewModel.updateShowWeather(false, context)
                                } else {
                                    viewModel.updateShowWeather(true, context)
                                    showWeatherSettings = true 
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            containerColor = if (viewModel.showWeather) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = "Wetter umschalten", tint = if (viewModel.showWeather) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        SmallFloatingActionButton(
                            onClick = { viewModel.isMapFullscreen = false },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Vollbild beenden")
                        }
                    }
                    Column(
                        modifier = Modifier.padding(16.dp).align(Alignment.CenterStart),
                        horizontalAlignment = Alignment.Start
                    ) {
                        SmallFloatingActionButton(
                            onClick = { showPoiSettings = true },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.PinDrop, contentDescription = "POI Einstellungen")
                        }
                        Spacer(Modifier.height(8.dp))
                        SmallFloatingActionButton(
                            onClick = { showWaypointsSheet = true },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.List, contentDescription = "Wegpunkte")
                        }
                    }
                Column(
                    modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd),
                    horizontalAlignment = Alignment.End
                ) {
                    val hasAllAlts = viewModel.routeOptions.count { !it.isOriginal && it.alternativeIdx > 0 } >= 3
                    val isDirect = viewModel.bikeProfile == "direct"
                    val isBRouterImport = viewModel.debugUrl?.contains("brouter.de/brouter-web") == true
                    val canFetchAlts = viewModel.routeOptions.isNotEmpty() && !viewModel.isProcessing && !hasAllAlts && !isDirect && !isBRouterImport

                    if (canFetchAlts) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.fetchAlternatives(context) },
                            modifier = Modifier.padding(bottom = 8.dp),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.Route, contentDescription = "Alternativen berechnen", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (viewModel.recordedPath.isNotEmpty()) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.clearRecordedPath() },
                            modifier = Modifier.padding(bottom = 8.dp),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Aufzeichnung löschen", tint = MaterialTheme.colorScheme.error)
                        }
                        SmallFloatingActionButton(
                            onClick = { viewModel.saveRecordedPath(context) },
                            modifier = Modifier.padding(bottom = 8.dp),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Aufzeichnung speichern", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SmallFloatingActionButton(
                        onClick = { viewModel.toggleFollowMode(context, pagerState.currentPage) },
                        modifier = Modifier.padding(bottom = 8.dp),
                        containerColor = if (viewModel.isFollowMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Icon(
                            if (viewModel.isFollowMode) Icons.Default.Navigation else Icons.Default.Explore, 
                            contentDescription = "Routen folgen",
                            tint = if (viewModel.isFollowMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = { onMyLocationClick() },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Mein Standort")
                    }
                }
            }
        } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (viewModel.routeOptions.isEmpty() || viewModel.isProcessing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = viewModel.status, style = MaterialTheme.typography.bodyMedium)
                }

                if (viewModel.isProcessing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }


                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth().height(mapHeight).clip(MaterialTheme.shapes.medium)) {
                    MapPreview(
                        options = viewModel.routeOptions,
                        visibleRoutes = viewModel.visibleRoutes,
                        colors = colors,
                        interceptor = viewModel.getInterceptor(),
                        mapType = viewModel.mapType,
                        showWeather = viewModel.showWeather,
                        selectedRouteIndex = pagerState.currentPage,
                        highlightedRouteIndex = viewModel.highlightedRouteIndex,
                        highlightedPointIndex = viewModel.highlightedPointIndex,
                        userLocation = viewModel.userLocation,
                        recordedPath = viewModel.recordedPath,
                        centerOnUserRequested = viewModel.centerOnUserRequested,
                        onCenterOnUserHandled = { viewModel.centerOnUserRequested = false },
                        onUserPositionSelected = { viewModel.highlightNearestPointToUser(pagerState.currentPage) },
                        onRouteSelected = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        onPointSelected = { rIdx, pIdx ->
                            viewModel.setHighlight(rIdx, pIdx)
                            scope.launch { pagerState.animateScrollToPage(rIdx) }
                        },
                        waypoints = viewModel.waypoints,
                        pois = viewModel.activePois,
                        onMapLongClick = { lat, lon -> viewModel.addWaypoint(lat, lon, context) },
                        onPoiClick = { viewModel.addWaypoint(it.lat, it.lon, context) },
                        onMapBoundsChanged = { s, w, n, e -> viewModel.refreshPois(s, w, n, e) },
                        onWaypointMoved = { index, lat, lon -> viewModel.updateWaypoint(index, lat, lon, context) },
                        modifier = Modifier.fillMaxSize()
                    )
                    Row(modifier = Modifier.padding(top = 100.dp, start = 8.dp).align(Alignment.TopStart)) {
                        if (viewModel.waypoints.isNotEmpty() || viewModel.routeOptions.isNotEmpty()) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.clearWaypoints() },
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Alles löschen", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Row(modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
                        MapLayerSelector(
                            currentType = viewModel.mapType,
                            onTypeSelected = { viewModel.updateMapType(it) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        SmallFloatingActionButton(
                            onClick = { 
                                if (viewModel.showWeather) {
                                    viewModel.updateShowWeather(false, context)
                                } else {
                                    viewModel.updateShowWeather(true, context)
                                    showWeatherSettings = true 
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            containerColor = if (viewModel.showWeather) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = "Wetter umschalten", tint = if (viewModel.showWeather) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        SmallFloatingActionButton(
                            onClick = { viewModel.isMapFullscreen = true },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Vollbild")
                        }
                    }
                    Column(
                        modifier = Modifier.padding(8.dp).align(Alignment.CenterStart),
                        horizontalAlignment = Alignment.Start
                    ) {
                        SmallFloatingActionButton(
                            onClick = { showPoiSettings = true },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.PinDrop, contentDescription = "POI Einstellungen")
                        }
                        Spacer(Modifier.height(8.dp))
                        SmallFloatingActionButton(
                            onClick = { showWaypointsSheet = true },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.List, contentDescription = "Wegpunkte")
                        }
                    }
                    Column(
                        modifier = Modifier.padding(8.dp).align(Alignment.BottomEnd),
                        horizontalAlignment = Alignment.End
                    ) {
                        val hasAllAlts = viewModel.routeOptions.count { !it.isOriginal && it.alternativeIdx > 0 } >= 3
                        val isDirect = viewModel.bikeProfile == "direct"
                        val isBRouterImport = viewModel.debugUrl?.contains("brouter.de/brouter-web") == true
                        val canFetchAlts = viewModel.routeOptions.isNotEmpty() && !viewModel.isProcessing && !hasAllAlts && !isDirect && !isBRouterImport

                        if (canFetchAlts) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.fetchAlternatives(context) },
                                modifier = Modifier.padding(bottom = 8.dp),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.Route, contentDescription = "Alternativen berechnen", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (viewModel.recordedPath.isNotEmpty()) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.clearRecordedPath() },
                                modifier = Modifier.padding(bottom = 8.dp),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Aufzeichnung löschen", tint = MaterialTheme.colorScheme.error)
                            }
                            SmallFloatingActionButton(
                                onClick = { viewModel.saveRecordedPath(context) },
                                modifier = Modifier.padding(bottom = 8.dp),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Aufzeichnung speichern", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        SmallFloatingActionButton(
                            onClick = { viewModel.toggleFollowMode(context, pagerState.currentPage) },
                            modifier = Modifier.padding(bottom = 8.dp),
                            containerColor = if (viewModel.isFollowMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(
                                if (viewModel.isFollowMode) Icons.Default.Navigation else Icons.Default.Explore, 
                                contentDescription = "Routen folgen",
                                tint = if (viewModel.isFollowMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = { onMyLocationClick() },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Mein Standort")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

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
                            option.alternativeIdx == 0 -> viewModel.colorMain
                            option.alternativeIdx == 1 -> viewModel.colorAlt1
                            option.alternativeIdx == 2 -> viewModel.colorAlt2
                            option.alternativeIdx == 3 -> viewModel.colorAlt3
                            else -> viewModel.colorAlt3
                        }
                        val routeColor = try { Color(android.graphics.Color.parseColor(routeColorHex)) } catch (e: Exception) { Color.Gray }

                        var showSaveDialog by remember { mutableStateOf(false) }
                        if (showSaveDialog) {
                            SaveBookmarkDialog(option, viewModel) { showSaveDialog = false }
                        }

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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedIconButton(onClick = { showSaveDialog = true }) {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Sichern")
                                    }
                                    OutlinedIconButton(onClick = { viewModel.downloadMapForRoute(option.points) }) {
                                        Icon(Icons.Default.Download, contentDescription = "Offline")
                                    }
                                    OutlinedIconButton(onClick = { onPreview(option) }) {
                                        Icon(Icons.Default.Language, contentDescription = "Edit")
                                    }
                                    FilledIconButton(onClick = { scope.launch { viewModel.shareGpx(option, context) } }) {
                                        Icon(Icons.Default.Share, contentDescription = "Teilen")
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

        if (downloadProgress.isDownloading) {
            DownloadProgressOverlay(downloadProgress, onCancel = { viewModel.cancelDownload() })
        }
    }
}
