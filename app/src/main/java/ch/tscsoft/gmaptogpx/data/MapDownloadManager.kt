package ch.tscsoft.gmaptogpx.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

data class DownloadProgress(
    val total: Int,
    val current: Int,
    val isDownloading: Boolean = false,
    val error: String? = null
)

class MapDownloadManager(private val context: Context, private val interceptor: TileCacheInterceptor) {

    private val _progress = MutableStateFlow(DownloadProgress(0, 0))
    val progress: StateFlow<DownloadProgress> = _progress
    
    private var downloadJob: Job? = null

    suspend fun downloadRouteTiles(points: List<Pair<Double, Double>>, mapType: String) = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }

        // Padding
        val pad = 0.01 // approx 1km
        val bounds = DoubleArray(4).apply {
            this[0] = minLat - pad // south
            this[1] = minLon - pad // west
            this[2] = maxLat + pad // north
            this[3] = maxLon + pad // east
        }

        val zoomLevels = 8..16
        val allTiles = mutableListOf<Triple<Int, Int, Int>>()

        for (z in zoomLevels) {
            val minTileX = lonToTileX(bounds[1], z)
            val maxTileX = lonToTileX(bounds[3], z)
            val minTileY = latToTileY(bounds[2], z) // North is smaller Y
            val maxTileY = latToTileY(bounds[0], z)

            for (x in minTileX..maxTileX) {
                for (y in minTileY..maxTileY) {
                    allTiles.add(Triple(z, x, y))
                }
            }
        }

        val total = allTiles.size
        _progress.value = DownloadProgress(total, 0, true)

        var current = 0
        for (tile in allTiles) {
            if (!isActive) break
            
            val url = getTileUrl(tile.first, tile.second, tile.third, mapType)
            if (url != null) {
                interceptor.handleRequest(url)
            }
            current++
            if (current % 10 == 0 || current == total) {
                _progress.value = DownloadProgress(total, current, true)
            }
        }

        _progress.value = DownloadProgress(total, total, false)
    }

    fun cancelDownload() {
        _progress.value = DownloadProgress(0, 0, false)
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        return floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        return floor((1.0 - ln(tan(Math.toRadians(lat)) + 1.0 / cos(Math.toRadians(lat))) / PI) / 2.0 * (1 shl z)).toInt()
    }

    private fun getTileUrl(z: Int, x: Int, y: Int, mapType: String): String? {
        val s = listOf("a", "b", "c").random()
        return when (mapType) {
            "standard" -> "https://$s.tile.openstreetmap.org/$z/$x/$y.png"
            "topo" -> "https://$s.tile.opentopomap.org/$z/$x/$y.png"
            "cycle" -> "https://$s.tile-cyclosm.openstreetmap.fr/cyclosm/$z/$x/$y.png"
            "satellite" -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            else -> null
        }
    }
}
