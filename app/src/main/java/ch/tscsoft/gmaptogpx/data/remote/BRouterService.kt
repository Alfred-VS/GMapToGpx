package ch.tscsoft.gmaptogpx.data.remote

import ch.tscsoft.gmaptogpx.data.models.BRouterResult
import ch.tscsoft.gmaptogpx.data.models.RouteSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class BRouterService @Inject constructor() {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun getBikeRoute(points: List<Pair<Double, Double>>, profile: String, altIdx: Int = 0): BRouterResult = withContext(Dispatchers.IO) {
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

    fun createSurfaceSummary(segments: List<RouteSegment>): Map<String, Double> {
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

    private fun calculateDistanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
