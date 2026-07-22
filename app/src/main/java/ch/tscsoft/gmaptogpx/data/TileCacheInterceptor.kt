package ch.tscsoft.gmaptogpx.data

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TileCacheInterceptor(private val context: Context) {

    private val cacheDir = File(context.filesDir, "map_tiles")

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    fun handleRequest(url: String): WebResourceResponse? {
        if (!isTileRequest(url)) return null

        val cacheFile = getCacheFile(url)
        if (cacheFile.exists()) {
            return try {
                WebResourceResponse(
                    "image/png",
                    "UTF-8",
                    FileInputStream(cacheFile)
                )
            } catch (e: Exception) {
                null
            }
        }

        // Not in cache, fetch and store
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 GMapToGpx/1.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val data = inputStream.readBytes()
                
                // Save to cache
                saveToCache(cacheFile, data)
                
                WebResourceResponse(
                    "image/png",
                    "UTF-8",
                    data.inputStream()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isTileRequest(url: String): Boolean {
        return url.contains("tile.openstreetmap.org") ||
                url.contains("tile.opentopomap.org") ||
                url.contains("tile-cyclosm.openstreetmap.fr") ||
                url.contains("arcgisonline.com/ArcGIS/rest/services/World_Imagery")
    }

    private fun getCacheFile(url: String): File {
        val provider = when {
            url.contains("tile.openstreetmap.org") -> "osm"
            url.contains("tile.opentopomap.org") -> "topo"
            url.contains("tile-cyclosm.openstreetmap.fr") -> "cycle"
            url.contains("arcgisonline.com/ArcGIS/rest/services/World_Imagery") -> "satellite"
            else -> "unknown"
        }

        // Extract Z/X/Y from URL
        // Patterns:
        // OSM/Topo/Cycle: .../{z}/{x}/{y}.png
        // Satellite: .../tile/{z}/{y}/{x}
        
        val segments = url.split("/")
        val z: String
        val x: String
        val y: String

        if (provider == "satellite") {
            // .../tile/{z}/{y}/{x}
            z = segments.getOrNull(segments.size - 3) ?: "0"
            y = segments.getOrNull(segments.size - 2) ?: "0"
            x = segments.getOrNull(segments.size - 1) ?: "0"
        } else {
            // .../{z}/{x}/{y}.png
            z = segments.getOrNull(segments.size - 3) ?: "0"
            x = segments.getOrNull(segments.size - 2) ?: "0"
            y = segments.getOrNull(segments.size - 1)?.substringBefore(".") ?: "0"
        }

        val dir = File(cacheDir, "$provider/$z/$x")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$y.png")
    }

    private fun saveToCache(file: File, data: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(data) }
        } catch (e: Exception) {
            // Ignore cache save errors
        }
    }
    
    fun getCacheSize(): Long {
        return cacheDir.walkBottomUp().fold(0L) { acc, file -> acc + file.length() }
    }
    
    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
