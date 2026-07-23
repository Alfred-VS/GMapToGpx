package ch.tscsoft.gmaptogpx.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object UrlResolver {

    fun extractUrl(text: String): String? {
        val regex = "(https?://\\S+)".toRegex()
        return regex.find(text)?.value
    }

    suspend fun resolveUrl(shortUrl: String): String = withContext(Dispatchers.IO) {
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

    fun extractAllCoordinates(url: String): List<Pair<Double, Double>> {
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
}
