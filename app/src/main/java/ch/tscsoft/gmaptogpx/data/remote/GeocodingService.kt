package ch.tscsoft.gmaptogpx.data.remote

import ch.tscsoft.gmaptogpx.data.models.SearchSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeocodingService @Inject constructor() {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        if (query.length < 3) return@withContext emptyList()
        
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&addressdetails=1"
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "GMapToGpx/1.0 (Android App)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode != 200) return@withContext emptyList()

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = jsonParser.parseToJsonElement(responseText).jsonArray
            
            jsonArray.map { element ->
                val obj = element.jsonObject
                val displayName = obj["display_name"]?.jsonPrimitive?.content ?: ""
                val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                
                val parts = displayName.split(",")
                val name = parts.firstOrNull()?.trim() ?: "Unbekannt"
                val desc = parts.drop(1).joinToString(",").trim()

                SearchSuggestion(name, desc, lat, lon)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
