package ch.tscsoft.gmaptogpx.data.models

val ROUTE_PROFILES = listOf(
    "fastbike" to "Rennrad",
    "trekking" to "Trekking",
    "mtb" to "Mountainbike",
    "shortest" to "Kürzeste",
    "safety" to "Sicherste",
    "hiking-mountain" to "Bergwandern",
    "hiking-soft" to "Wandern",
    "vm-forum" to "Velomobil",
    "moped" to "Mofa/Moped",
    "car-test" to "PKW"
)

data class RouteSegment(
    val surface: String,
    val highway: String,
    val gradient: Double, // in %
    val distance: Double, // in meters
    val startIndex: Int,
    val endIndex: Int
)

data class RouteOption(
    val title: String,
    val points: List<Pair<Double, Double>>,
    val altitudes: List<Double> = emptyList(),
    val distances: List<Double> = emptyList(),
    val gpxContent: String,
    val isOriginal: Boolean = false,
    val inputPoints: List<Pair<Double, Double>> = emptyList(),
    val alternativeIdx: Int = 0,
    val distanceMeters: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0,
    val segments: List<RouteSegment> = emptyList(),
    val surfaceSummary: Map<String, Double> = emptyMap(),
    val weatherSamples: List<WeatherSample> = emptyList()
)

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val address: String? = null
)

data class SearchSuggestion(
    val name: String,
    val description: String,
    val lat: Double,
    val lon: Double
)

data class Poi(
    val id: Long,
    val name: String,
    val type: PoiType,
    val lat: Double,
    val lon: Double
)

enum class PoiType(val label: String, val icon: String, val query: String) {
    DRINKING_WATER("Trinkwasser", "🚰", "node[\"amenity\"=\"drinking_water\"]"),
    BIKE_SHOP("Fahrradladen", "🚲", "node[\"shop\"=\"bicycle\"]"),
    SHELTER("Unterstand", "🏠", "node[\"amenity\"=\"shelter\"]"),
    BENCH("Bank", "🪑", "node[\"amenity\"=\"bench\"]"),
    TOILETS("WC", "🚻", "node[\"amenity\"=\"toilets\"]")
}

data class WeatherSample(
    val lat: Double,
    val lon: Double,
    val distanceIdx: Int,
    val timeOffsetSeconds: Int,
    val temp: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val windDirection: Double = 0.0,
    val description: String,
    val icon: String // Emoji or icon name
)

data class BRouterResult(
    val points: List<Pair<Double, Double>>,
    val altitudes: List<Double> = emptyList(),
    val distances: List<Double> = emptyList(),
    val distance: Double = 0.0,
    val elevationGain: Int = 0,
    val elevationLoss: Int = 0,
    val totalTimeSeconds: Int = 0,
    val segments: List<RouteSegment> = emptyList()
)
