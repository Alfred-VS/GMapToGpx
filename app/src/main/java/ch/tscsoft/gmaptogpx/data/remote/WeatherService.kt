package ch.tscsoft.gmaptogpx.data.remote

import ch.tscsoft.gmaptogpx.data.models.RouteOption
import ch.tscsoft.gmaptogpx.data.models.WeatherSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherService @Inject constructor() {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun getWeatherInfo(code: Int): Pair<String, String> {
        return when (code) {
            0 -> "Klarer Himmel" to "☀️"
            1, 2, 3 -> "Leicht bewölkt" to "🌤️"
            45, 48 -> "Nebel" to "🌫️"
            51, 53, 55 -> "Nieselregen" to "🌦️"
            61, 63 -> "Leichter Regen" to "🌧️"
            65 -> "Starker Regen" to "🌧️"
            71, 73, 75 -> "Schneefall" to "❄️"
            80, 81, 82 -> "Regenschauer" to "🌦️"
            95, 96, 99 -> "Gewitter" to "⛈️"
            else -> "Unbekannt" to "❓"
        }
    }

    suspend fun fetchWeatherForRoute(
        option: RouteOption,
        showWeather: Boolean,
        weatherStartTime: Long
    ): RouteOption = withContext(Dispatchers.IO) {
        if (!showWeather || option.points.isEmpty() || (option.totalTimeSeconds == 0 && !option.isOriginal)) return@withContext option
        
        val samples = mutableListOf<WeatherSample>()
        val numSamples = 4 // Start, 1/3, 2/3, Ende
        
        for (i in 0..numSamples) {
            val fraction = i.toDouble() / numSamples
            val targetIdx = (fraction * (option.points.size - 1)).toInt()
            val point = option.points[targetIdx]
            val timeOffset = (fraction * option.totalTimeSeconds).toInt()
            val absoluteTimeMillis = weatherStartTime + (timeOffset * 1000L)
            
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val targetDateStr = sdfDate.format(Date(absoluteTimeMillis))
                val targetHour = Calendar.getInstance().apply { timeInMillis = absoluteTimeMillis }.get(Calendar.HOUR_OF_DAY)

                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${point.first}&longitude=${point.second}&hourly=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m&start_date=$targetDateStr&end_date=$targetDateStr&timezone=auto")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = jsonParser.parseToJsonElement(text).jsonObject
                val hourly = json["hourly"]?.jsonObject
                
                if (hourly != null) {
                    val temps = hourly["temperature_2m"]?.jsonArray
                    val codes = hourly["weather_code"]?.jsonArray
                    val winds = hourly["wind_speed_10m"]?.jsonArray
                    val windDirs = hourly["wind_direction_10m"]?.jsonArray
                    
                    if (temps != null && targetHour < temps.size) {
                        val temp = temps[targetHour].jsonPrimitive.double
                        val code = codes?.get(targetHour)?.jsonPrimitive?.int ?: 0
                        val wind = winds?.get(targetHour)?.jsonPrimitive?.double ?: 0.0
                        val windDir = windDirs?.get(targetHour)?.jsonPrimitive?.double ?: 0.0
                        val info = getWeatherInfo(code)
                        
                        samples.add(WeatherSample(
                            lat = point.first,
                            lon = point.second,
                            distanceIdx = targetIdx,
                            timeOffsetSeconds = timeOffset,
                            temp = temp,
                            weatherCode = code,
                            windSpeed = wind,
                            windDirection = windDir,
                            description = info.first,
                            icon = info.second
                        ))
                    }
                }
            } catch (e: Exception) {
                // Ignore single point errors
            }
        }
        option.copy(weatherSamples = samples)
    }
}
