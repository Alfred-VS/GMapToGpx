package ch.tscsoft.gmaptogpx.util

import ch.tscsoft.gmaptogpx.data.models.RouteSegment
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class GpxParseResult(
    val points: List<Pair<Double, Double>>,
    val altitudes: List<Double>,
    val distances: List<Double>,
    val distanceMeters: Double,
    val elevationGain: Int,
    val elevationLoss: Int,
    val segments: List<RouteSegment>
)

object GpxUtil {

    fun createGpx(
        points: List<Pair<Double, Double>>,
        altitudes: List<Double>? = null,
        segments: List<RouteSegment>? = null,
        trackName: String? = null
    ): String {
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
            sb.append("    <name>${trackName ?: "Google Maps Ort"}</name>\n")
            if (altitudes != null && altitudes.isNotEmpty()) {
                sb.append("    <ele>${altitudes[0]}</ele>\n")
            }
            if (segments != null && segments.isNotEmpty()) {
                val seg = segments.find { 0 in it.startIndex..it.endIndex }
                if (seg != null) {
                    sb.append("    <extensions>\n")
                    sb.append("      <surface>${seg.surface}</surface>\n")
                    sb.append("      <highway>${seg.highway}</highway>\n")
                    sb.append("    </extensions>\n")
                }
            }
            sb.append("    <time>$time</time>\n")
            sb.append("  </wpt>\n")
        } else {
            sb.append("  <trk>\n")
            sb.append("    <name>${trackName ?: "Google Maps Route"}</name>\n")
            sb.append("    <trkseg>\n")
            for (i in points.indices) {
                val p = points[i]
                val lat = String.format(Locale.US, "%.6f", p.first)
                val lon = String.format(Locale.US, "%.6f", p.second)
                sb.append("      <trkpt lat=\"$lat\" lon=\"$lon\">\n")
                if (altitudes != null && i < altitudes.size) {
                    sb.append("        <ele>${altitudes[i]}</ele>\n")
                }
                if (segments != null && segments.isNotEmpty()) {
                    val seg = segments.find { i in it.startIndex..it.endIndex }
                    if (seg != null) {
                        sb.append("        <extensions>\n")
                        sb.append("          <surface>${seg.surface}</surface>\n")
                        sb.append("          <highway>${seg.highway}</highway>\n")
                        sb.append("        </extensions>\n")
                    }
                }
                sb.append("        <time>$time</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>")
        return sb.toString()
    }

    fun parseGpx(gpxContent: String): GpxParseResult {
        val points = mutableListOf<Pair<Double, Double>>()
        val alts = mutableListOf<Double>()
        val surfaces = mutableListOf<String>()
        val highways = mutableListOf<String>()
        
        val trkptBlockRegex = """<trkpt\s+lat=["']([-+]?\d+\.\d+)["']\s+lon=["']([-+]?\d+\.\d+)["']>(.*?)</trkpt>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        trkptBlockRegex.findAll(gpxContent).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            val content = match.groupValues[3]
            val ele = """<ele>([-+]?\d+(\.\d+)?)</ele>""".toRegex().find(content)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val surface = """<surface>(.*?)</surface>""".toRegex().find(content)?.groupValues?.get(1) ?: ""
            val highway = """<highway>(.*?)</highway>""".toRegex().find(content)?.groupValues?.get(1) ?: ""
            
            if (lat != null && lon != null) {
                points.add(lat to lon)
                alts.add(ele)
                surfaces.add(surface)
                highways.add(highway)
            }
        }

        if (points.isEmpty()) {
            val wptBlockRegex = """<wpt\s+lat=["']([-+]?\d+\.\d+)["']\s+lon=["']([-+]?\d+\.\d+)["']>(.*?)</wpt>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            wptBlockRegex.findAll(gpxContent).forEach { match ->
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                val content = match.groupValues[3]
                val ele = """<ele>([-+]?\d+(\.\d+)?)</ele>""".toRegex().find(content)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val surface = """<surface>(.*?)</surface>""".toRegex().find(content)?.groupValues?.get(1) ?: ""
                val highway = """<highway>(.*?)</highway>""".toRegex().find(content)?.groupValues?.get(1) ?: ""

                if (lat != null && lon != null) {
                    points.add(lat to lon)
                    alts.add(ele)
                    surfaces.add(surface)
                    highways.add(highway)
                }
            }
        }

        val dist = calculateDistance(points)
        
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

        val resSegments = mutableListOf<RouteSegment>()
        if (surfaces.any { it.isNotEmpty() } || highways.any { it.isNotEmpty() }) {
            var lastS = surfaces.getOrNull(0) ?: ""
            var lastH = highways.getOrNull(0) ?: ""
            var startIndex = 0
            var segmentDist = 0.0
            var startAlt = alts.getOrNull(0) ?: 0.0

            for (i in 1 until points.size) {
                val s = surfaces.getOrNull(i) ?: ""
                val h = highways.getOrNull(i) ?: ""
                val d = calculateDistanceBetween(points[i-1].first, points[i-1].second, points[i].first, points[i].second)
                
                if (s != lastS || h != lastH || i == points.size - 1) {
                    val endIndex = i
                    if (i == points.size - 1) segmentDist += d
                    
                    val elevDiff = (alts.getOrNull(endIndex) ?: 0.0) - startAlt
                    val gradient = if (segmentDist > 0) (elevDiff / segmentDist) * 100.0 else 0.0
                    resSegments.add(RouteSegment(lastS, lastH, gradient, segmentDist, startIndex, endIndex))
                    
                    lastS = s
                    lastH = h
                    startIndex = i
                    segmentDist = d
                    startAlt = alts.getOrNull(i) ?: 0.0
                } else {
                    segmentDist += d
                }
            }
        }

        return GpxParseResult(points, alts, distances, dist, gain.toInt(), loss.toInt(), resSegments)
    }

    fun calculateDistance(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i+1]
            total += calculateDistanceBetween(p1.first, p1.second, p2.first, p2.second)
        }
        return total
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
