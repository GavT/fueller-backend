package uk.co.fueller.app.data

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val postcode: String,
    val radiusMiles: Double,
    val stations: List<StationWithPrices>,
    val dataLastRefreshed: String,
    val nextDataRefresh: String
)

@Serializable
data class StationWithPrices(
    val nodeId: String,
    val name: String,
    val brand: String? = null,
    val address: String,
    val postcode: String? = null,
    val latitude: Double,
    val longitude: Double,
    val distanceMiles: Double,
    val isMotorway: Boolean = false,
    val isSupermarket: Boolean = false,
    val prices: List<FuelPriceInfo>,
    val temporaryClosure: Boolean = false
)

@Serializable
data class FuelPriceInfo(
    val fuelType: String,
    val fuelLabel: String,
    val pencePerLitre: Double,
    val lastUpdated: String? = null,
    val nextRefresh: String
)

@Serializable
data class ErrorResponse(
    val error: String
)
