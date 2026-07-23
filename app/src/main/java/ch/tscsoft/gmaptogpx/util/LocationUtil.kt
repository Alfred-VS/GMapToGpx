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

    fun getFullAddress(lat: Double, lon: Double, context: Context): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val street = addr.thoroughfare ?: ""
                val houseNumber = addr.subThoroughfare ?: ""
                val city = addr.locality ?: addr.subLocality ?: ""
                
                val sb = StringBuilder()
                if (street.isNotEmpty()) {
                    sb.append(street)
                    if (houseNumber.isNotEmpty()) sb.append(" ").append(houseNumber)
                    if (city.isNotEmpty()) sb.append(", ")
                }
                if (city.isNotEmpty()) sb.append(city)
                
                if (sb.isEmpty()) addr.getAddressLine(0) else sb.toString()
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
