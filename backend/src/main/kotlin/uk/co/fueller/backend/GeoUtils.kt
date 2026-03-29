package uk.co.fueller.backend

import kotlin.math.*

object GeoUtils {

    private const val EARTH_RADIUS_MILES = 3958.8

    /** Haversine distance in miles between two lat/lon points. */
    fun distanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_MILES * c
    }

    /** Fuel type code to human-readable label. */
    fun fuelLabel(code: String): String = when (code.uppercase()) {
        "E10" -> "Unleaded (E10)"
        "E5" -> "Super Unleaded (E5)"
        "B7", "B7_STANDARD", "B7P" -> "Diesel"
        "B7S", "SDV" -> "Super Diesel"
        "B10" -> "Diesel (B10)"
        "HVO" -> "HVO Diesel"
        else -> code
    }
}
