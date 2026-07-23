package ch.tscsoft.gmaptogpx.data.remote

import ch.tscsoft.gmaptogpx.data.models.Poi
import ch.tscsoft.gmaptogpx.data.models.PoiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiService @Inject constructor() {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun getPois(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        types: Set<PoiType>
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext emptyList()
        
        try {
            val bbox = "$south,$west,$north,$east"
            val queries = types.joinToString("") { "${it.query}($bbox);" }
            val overpassQuery = "[out:json][timeout:25];($queries);out body;>;out skel qt;"
            
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "GMapToGpx/1.0 (Android App)")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            connection.outputStream.use { it.write("data=$overpassQuery".toByteArray()) }
            
            if (connection.responseCode != 200) return@withContext emptyList()

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = jsonParser.parseToJsonElement(responseText).jsonObject
            val elements = json["elements"]?.jsonArray ?: return@withContext emptyList()
            
            elements.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.long ?: return@mapNotNull null
                val lat = obj["lat"]?.jsonPrimitive?.double ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.double ?: return@mapNotNull null
                val tags = obj["tags"]?.jsonObject
                
                val name = tags?.get("name")?.jsonPrimitive?.content ?: ""
                
                // Determine type from tags
                val type = types.find { type ->
                    // This is a bit simplified, ideally we'd check against the specific query key/value
                    val key = type.query.substringAfter("[\"").substringBefore("\"")
                    val value = type.query.substringAfter("=\"").substringBefore("\"]")
                    tags?.get(key)?.jsonPrimitive?.content == value
                } ?: return@mapNotNull null

                Poi(id, if (name.isEmpty()) type.label else name, type, lat, lon)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
