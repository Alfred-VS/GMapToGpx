package ch.tscsoft.gmaptogpx.ui.components

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.tscsoft.gmaptogpx.data.TileCacheInterceptor
import ch.tscsoft.gmaptogpx.data.models.RouteOption
import ch.tscsoft.gmaptogpx.data.models.Poi
import kotlinx.serialization.json.*
import java.util.*

@Composable
fun MapPreview(
    options: List<RouteOption>,
    visibleRoutes: Set<Int>,
    colors: List<String>,
    interceptor: TileCacheInterceptor,
    mapType: String = "standard",
    showWeather: Boolean = true,
    selectedRouteIndex: Int = -1,
    highlightedRouteIndex: Int? = null,
    highlightedPointIndex: Int? = null,
    userLocation: Pair<Double, Double>? = null,
    recordedPath: List<Pair<Double, Double>> = emptyList(),
    centerOnUserRequested: Boolean = false,
    onCenterOnUserHandled: () -> Unit = {},
    onRouteSelected: (Int) -> Unit,
    onUserPositionSelected: () -> Unit = {},
    onPointSelected: (Int, Int) -> Unit = { _, _ -> },
    waypoints: List<ch.tscsoft.gmaptogpx.data.models.Waypoint> = emptyList(),
    pois: List<Poi> = emptyList(),
    onMapLongClick: (Double, Double) -> Unit = { _, _ -> },
    onPoiClick: (Poi) -> Unit = {},
    onMapBoundsChanged: (Double, Double, Double, Double) -> Unit = { _, _, _, _ -> },
    onWaypointMoved: (Int, Double, Double) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val jsonParser = Json { ignoreUnknownKeys = true }
    
    val htmlContent = remember(options, visibleRoutes, colors, showWeather, mapType) {
        val jsonString = options.mapIndexed { index, opt ->
            val isVisible = visibleRoutes.contains(index)
            val coords = if (isVisible) opt.points.joinToString(",") { "[${it.first},${it.second}]" } else ""
            
            val argbHex = when {
                opt.isOriginal -> colors[4]
                opt.alternativeIdx == 0 -> colors[0]
                opt.alternativeIdx == 1 -> colors[1]
                opt.alternativeIdx == 2 -> colors[2]
                opt.alternativeIdx == 3 -> colors[3]
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
            
            val weatherJson = if (showWeather) opt.weatherSamples.joinToString(",") { 
                """{"lat":${it.lat},"lon":${it.lon},"icon":"${it.icon}","temp":"${it.temp.toInt()}°C","wind":"${it.windSpeed.toInt()}","dir":${it.windDirection}}"""
            } else ""
            
            """{"index":$index, "km":"$km", "time":"$timeText", "color":"$rgbHex", "opacity":$opacity, "points":[$coords], "isVisible":$isVisible,"isOriginal":${opt.isOriginal},"weather":[$weatherJson]}"""
        }.joinToString(",", prefix = "[", postfix = "]")

        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                #map { height: 100%; width: 100%; position: absolute; top: 0; bottom: 0; left: 0; right: 0; touch-action: none; }
                body { margin: 0; padding: 0; overflow: hidden; }
                .leaflet-interactive { cursor: pointer; }
                .route-label { background: transparent !important; border: none !important; box-shadow: none !important; padding: 0 !important; pointer-events: auto !important; }
                .label-inner { border: 1px solid #333; border-radius: 3px; padding: 1px 2px; font-size: 12px; line-height: 1.1; font-weight: bold; text-align: center; box-shadow: 0 1px 2px rgba(0,0,0,0.2); white-space: nowrap; }
                .weather-icon { font-size: 16px; text-shadow: 0 0 3px white; background: rgba(255,255,255,0.7); border-radius: 4px; padding: 2px; text-align: center; border: 1px solid #999; display: flex; flex-direction: column; align-items: center; min-width: 24px; }
                .poi-icon { font-size: 18px; text-shadow: 0 0 2px white; }
                .wind-info { font-size: 9px; font-weight: bold; display: flex; align-items: center; }
                .wind-arrow { display: inline-block; font-size: 12px; line-height: 1; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map').setView([0, 0], 2);
                
                var osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OSM' });
                var topo = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', { maxZoom: 17, attribution: 'Map data: &copy; OpenStreetMap contributors, SRTM | Map style: &copy; OpenTopoMap (CC-BY-SA)' });
                var cycle = L.tileLayer('https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png', { maxZoom: 20, attribution: 'CyclOSM | Map data: &copy; OSM' });
                var satellite = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { attribution: 'Esri' });

                window.setMapType = function(type) {
                    map.eachLayer(function(layer) { if (layer instanceof L.TileLayer) map.removeLayer(layer); });
                    if (type === 'satellite') satellite.addTo(map);
                    else if (type === 'topo') topo.addTo(map);
                    else if (type === 'cycle') cycle.addTo(map);
                    else osm.addTo(map);
                };
                window.setMapType('$mapType');

                var routesData = $jsonString;
                var routeLayers = []; var labelLayers = []; var weatherMarkers = []; var poiMarkers = []; var waypointMarkers = [];
                var highlightMarker = null; var userMarker = null;
                var group = new L.featureGroup();
                L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(map);

                map.on('contextmenu', function(e) { if (window.Android) window.Android.onMapLongClick(e.latlng.lat, e.latlng.lng); });
                map.on('moveend', function() { var b = map.getBounds(); if (window.Android) window.Android.onMapBoundsChanged(b.getSouth(), b.getWest(), b.getNorth(), b.getEast()); });

                window.setPois = function(poisJson) {
                    poiMarkers.forEach(function(m) { map.removeLayer(m); }); poiMarkers = [];
                    poisJson.forEach(function(p) {
                        var icon = L.divIcon({ html: '<div class="poi-icon">' + p.icon + '</div>', className: 'poi-marker', iconSize: [24, 24] });
                        var m = L.marker([p.lat, p.lon], { icon: icon }).addTo(map);
                        m.on('click', function() { if (window.Android) window.Android.onPoiClick(JSON.stringify(p)); });
                        poiMarkers.push(m);
                    });
                };

                window.setWaypoints = function(waypointsJson) {
                    waypointMarkers.forEach(function(m) { map.removeLayer(m); }); waypointMarkers = [];
                    waypointsJson.forEach(function(w, i) {
                        var m = L.marker([w[0], w[1]], { draggable: true }).addTo(map);
                        m.on('dragend', function(e) {
                            var pos = e.target.getLatLng();
                            if (window.Android) window.Android.moveWaypoint(i, pos.lat, pos.lng);
                        });
                        waypointMarkers.push(m);
                    });
                };

                window.setUserLocation = function(lat, lng, center) {
                    if (lat !== null && lng !== null) {
                        var pos = [lat, lng];
                        if (userMarker) userMarker.setLatLng(pos);
                        else {
                            userMarker = L.circleMarker(pos, { radius: 7, color: 'white', weight: 2, opacity: 1, fillColor: '#3880ff', fillOpacity: 1, interactive: true }).addTo(map);
                            userMarker.on('click', function() { if (window.Android) window.Android.selectUserPosition(); });
                        }
                        userMarker.bringToFront(); 
                        if (center) {
                            map.setView(pos, 15);
                        }
                    } else if (userMarker) { map.removeLayer(userMarker); userMarker = null; }
                };

                var recordedLayer = null;
                window.setRecordedPath = function(points) {
                    if (!points || points.length < 2) { if (recordedLayer) { map.removeLayer(recordedLayer); recordedLayer = null; } return; }
                    if (recordedLayer) recordedLayer.setLatLngs(points);
                    else recordedLayer = L.polyline(points, { color: '#FF5722', weight: 5, opacity: 0.8, lineCap: 'round', lineJoin: 'round' }).addTo(map);
                };

                function highlightRoute(selectedIndex) {
                    weatherMarkers.forEach(function(m) { map.removeLayer(m); }); weatherMarkers = [];
                    routeLayers.forEach(function(layer, index) {
                        var route = routesData[index]; if (!route.isVisible || !layer) return;
                        var isSelected = (index === selectedIndex);
                        layer.setStyle({ opacity: isSelected ? 1.0 : (route.opacity * 0.7), weight: isSelected ? 5 : ((index === 0 && !route.isOriginal) ? 4 : 2.5) });
                        if (isSelected) {
                            layer.bringToFront();
                            if (route.weather && route.weather.length > 0) {
                                route.weather.forEach(function(w) {
                                    var content = '<div class="weather-icon"><span>' + w.icon + ' ' + w.temp + '</span><div class="wind-info"><span class="wind-arrow" style="transform: rotate(' + w.dir + 'deg);">↑</span><span> ' + w.wind + '</span></div></div>';
                                    var icon = L.divIcon({ html: content, className: 'weather-label', iconSize: [40, 32], iconAnchor: [20, 16] });
                                    var m = L.marker([w.lat, w.lon], { icon: icon, interactive: false }).addTo(map); weatherMarkers.push(m);
                                });
                            }
                        }
                        var label = labelLayers[index];
                        if (label) {
                            var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + (isSelected ? 'black' : route.color) + '; border-width:' + (isSelected ? '2px' : '1px') + '; color:white; opacity:' + (isSelected ? 1.0 : (route.opacity * 0.8)) + ';">' + route.km + '</div>';
                            label.setContent(content); if (isSelected) label.bringToFront();
                        }
                    });
                    if (highlightMarker) highlightMarker.bringToFront();
                }

                function setHighlightMarker(routeIdx, pointIdx) {
                    if (routeIdx === null || pointIdx === null) { if (highlightMarker) { map.removeLayer(highlightMarker); highlightMarker = null; } return; }
                    var route = routesData[routeIdx]; if (!route || !route.isVisible || !route.points[pointIdx]) return;
                    var pos = route.points[pointIdx];
                    if (highlightMarker) highlightMarker.setLatLng(pos);
                    else highlightMarker = L.circleMarker(pos, { radius: 8, color: 'white', weight: 2, opacity: 1, fillColor: 'red', fillOpacity: 1, interactive: false }).addTo(map);
                    highlightMarker.bringToFront(); map.panTo(pos);
                }

                routesData.forEach(function(route, index) {
                    if (!route.isVisible || route.points.length === 0) { routeLayers.push(null); labelLayers.push(null); return; }
                    var polyline = L.polyline(route.points, { color: route.color, opacity: route.opacity * 0.7, weight: (index === 0 && !route.isOriginal) ? 4 : 2.5, interactive: true }).addTo(map);
                    polyline.on('click', function(e) {
                        if (window.Android) {
                            var p = e.latlng; var minD = Infinity; var bestIdx = 0;
                            for (var i=0; i<route.points.length; i++) {
                                var rp = route.points[i]; var d = Math.pow(p.lat - rp[0], 2) + Math.pow(p.lng - rp[1], 2);
                                if (d < minD) { minD = d; bestIdx = i; }
                            }
                            window.Android.selectPoint(route.index, bestIdx);
                        }
                    });
                    var pos = route.points[Math.floor(route.points.length * 0.5)];
                    var content = '<div class="label-inner" style="background:' + route.color + '; border-color:' + route.color + '; color:white; opacity:' + (route.opacity * 0.8) + ';">' + route.km + '</div>';
                    var tooltip = L.tooltip({ permanent: true, direction: 'top', className: 'route-label', interactive: true, offset: [0, -5] }).setLatLng(pos).setContent(content).addTo(map);
                    tooltip.on('click', function() { if (window.Android) window.Android.selectRoute(route.index); });
                    routeLayers.push(polyline); labelLayers.push(tooltip); group.addLayer(polyline);
                });

                if (group.getLayers().length > 0) {
                    setTimeout(function() { 
                        map.invalidateSize(); 
                        map.fitBounds(group.getBounds().pad(0.1)); 
                        highlightRoute($selectedRouteIndex);
                    }, 200);
                } else {
                    setTimeout(function() { map.invalidateSize(); }, 200);
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
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun selectRoute(index: Int) { onRouteSelected(index) }
                    @android.webkit.JavascriptInterface
                    fun selectPoint(routeIdx: Int, pointIdx: Int) { onPointSelected(routeIdx, pointIdx) }
                    @android.webkit.JavascriptInterface
                    fun selectUserPosition() { onUserPositionSelected() }
                    @android.webkit.JavascriptInterface
                    fun onMapLongClick(lat: Double, lon: Double) { onMapLongClick(lat, lon) }
                    @android.webkit.JavascriptInterface
                    fun onMapBoundsChanged(s: Double, w: Double, n: Double, e: Double) { onMapBoundsChanged(s, w, n, e) }
                    @android.webkit.JavascriptInterface
                    fun moveWaypoint(index: Int, lat: Double, lon: Double) { onWaypointMoved(index, lat, lon) }
                    @android.webkit.JavascriptInterface
                    fun onPoiClick(poiJson: String) { 
                        try {
                            val element = jsonParser.parseToJsonElement(poiJson).jsonObject
                            // Need way to convert back to Poi, or just pass id/name/etc.
                            // Simplified for now: find in list by ID
                            val id = element["id"]?.jsonPrimitive?.longOrNull
                            pois.find { it.id == id }?.let { onPoiClick(it) }
                        } catch (e: Exception) {}
                    }
                }, "Android")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        return request?.url?.toString()?.let { interceptor.handleRequest(it) }
                    }
                }
                setOnTouchListener { v, event ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        },
        update = { webView ->
            val lastState = webView.tag as? MapState ?: MapState()
            
            if (lastState.html != htmlContent) {
                val onlyMapTypeChanged = lastState.html.isNotEmpty() && 
                    lastState.showWeather == showWeather && 
                    lastState.mapType != mapType &&
                    lastState.html.replace("window.setMapType('$mapType');", "window.setMapType('${lastState.mapType}');") == htmlContent

                if (onlyMapTypeChanged) {
                    webView.evaluateJavascript("window.setMapType('$mapType')", null)
                } else {
                    webView.loadDataWithBaseURL("https://brouter.de", htmlContent, "text/html", "UTF-8", null)
                }
            } else {
                if (lastState.selectedRouteIndex != selectedRouteIndex) webView.evaluateJavascript("highlightRoute($selectedRouteIndex)", null)
                val lat = userLocation?.first; val lng = userLocation?.second
                if (lastState.userLat != lat || lastState.userLng != lng || centerOnUserRequested) webView.evaluateJavascript("setUserLocation(${lat ?: "null"}, ${lng ?: "null"}, $centerOnUserRequested)", null)
                if (lastState.highlightedRouteIndex != highlightedRouteIndex || lastState.highlightedPointIndex != highlightedPointIndex) webView.evaluateJavascript("setHighlightMarker($highlightedRouteIndex, $highlightedPointIndex)", null)
                if (lastState.recordedPath != recordedPath) {
                    val pointsJson = recordedPath.joinToString(",", prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
                    webView.evaluateJavascript("setRecordedPath($pointsJson)", null)
                }
                
                if (lastState.pois != pois) {
                    val poisJson = pois.joinToString(",", prefix = "[", postfix = "]") { 
                        """{"id":${it.id},"lat":${it.lat},"lon":${it.lon},"name":"${it.name.replace("\"", "\\\"")}","icon":"${it.type.icon}"}"""
                    }
                    webView.evaluateJavascript("setPois($poisJson)", null)
                }
                if (lastState.waypoints != waypoints) {
                    val wptsJson = waypoints.joinToString(",", prefix = "[", postfix = "]") { "[${it.lat},${it.lon}]" }
                    webView.evaluateJavascript("setWaypoints($wptsJson)", null)
                }
            }

            webView.tag = MapState(
                html = htmlContent, mapType = mapType, showWeather = showWeather,
                selectedRouteIndex = selectedRouteIndex, highlightedRouteIndex = highlightedRouteIndex,
                highlightedPointIndex = highlightedPointIndex, userLat = userLocation?.first,
                userLng = userLocation?.second, recordedPath = recordedPath,
                waypoints = waypoints, pois = pois
            )
            if (centerOnUserRequested) onCenterOnUserHandled()
        }
    )
}

private data class MapState(
    val html: String = "",
    val mapType: String = "",
    val showWeather: Boolean = true,
    val selectedRouteIndex: Int = -1,
    val highlightedRouteIndex: Int? = null,
    val highlightedPointIndex: Int? = null,
    val userLat: Double? = null,
    val userLng: Double? = null,
    val recordedPath: List<Pair<Double, Double>> = emptyList(),
    val waypoints: List<ch.tscsoft.gmaptogpx.data.models.Waypoint> = emptyList(),
    val pois: List<Poi> = emptyList()
)

@Composable
fun MapLayerSelector(
    currentType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val layers = listOf(
        "standard" to ("Standard" to Icons.Default.Layers),
        "topo" to ("OpenTopoMap" to Icons.Default.Terrain),
        "cycle" to ("CyclOSM" to Icons.Default.PedalBike),
        "satellite" to ("Satellite" to Icons.Default.Satellite)
    )

    Box(modifier = modifier) {
        SmallFloatingActionButton(
            onClick = { expanded = true },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ) {
            Icon(
                when (currentType) {
                    "standard" -> Icons.Default.Layers
                    "topo" -> Icons.Default.Terrain
                    "cycle" -> Icons.Default.PedalBike
                    "satellite" -> Icons.Default.Satellite
                    else -> Icons.Default.Map
                },
                contentDescription = "Kartentyp auswählen"
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            layers.forEach { (id, data) ->
                val (label, icon) = data
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, null) },
                    trailingIcon = { if (currentType == id) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) },
                    onClick = { onTypeSelected(id); expanded = false }
                )
            }
        }
    }
}

@Composable
fun DownloadProgressOverlay(progress: ch.tscsoft.gmaptogpx.data.DownloadProgress, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
        ElevatedCard(modifier = Modifier.width(280.dp).padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(progress = { if (progress.total > 0) progress.current.toFloat() / progress.total else 0f }, modifier = Modifier.size(64.dp))
                Text("Karten werden geladen...", style = MaterialTheme.typography.titleMedium)
                Text("${progress.current} / ${progress.total} Kacheln", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text("Abbrechen", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
