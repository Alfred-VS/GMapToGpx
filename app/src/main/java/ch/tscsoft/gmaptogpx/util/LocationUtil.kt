package ch.tscsoft.gmaptogpx.util

import android.content.Context
import android.location.Geocoder
import java.util.*

object LocationUtil {

    fun getPlaceName(lat: Double, lon: Double, context: Context): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                addr.locality ?: addr.subLocality ?: addr.adminArea ?: addr.thoroughfare
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
