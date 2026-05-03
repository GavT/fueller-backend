package uk.co.fueller.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Fuel Finder API response models ---

@Serializable
data class FuelFinderTokenWrapper(
    val success: Boolean,
    val data: FuelFinderTokenData,
    val message: String = ""
)

@Serializable
data class FuelFinderTokenData(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String = ""
)

@Serializable
data class StationLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val postcode: String? = null,
    @SerialName("address_line_1") val addressLine1: String? = null,
    @SerialName("address_line_2") val addressLine2: String? = null,
    val city: String? = null,
    val county: String? = null,
    val country: String? = null
)

@Serializable
data class FuelFinderStation(
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("trading_name") val tradingName: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    val location: StationLocation? = null,
    @SerialName("is_motorway_service_station") val isMotorway: Boolean? = false,
    @SerialName("is_supermarket_service_station") val isSupermarket: Boolean? = false,
    @SerialName("temporary_closure") val temporaryClosure: Boolean? = false,
    @SerialName("permanent_closure") val permanentClosure: Boolean? = false
)

@Serializable
data class FuelPrice(
    @SerialName("fuel_type") val fuelType: String,
    val price: Double? = null,
    @SerialName("price_last_updated") val priceLastUpdated: String? = null
)

@Serializable
data class FuelFinderPriceRecord(
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("fuel_prices") val fuelPrices: List<FuelPrice> = emptyList()
)

@Serializable
data class FuelFinderStationsResponse(
    val data: List<FuelFinderStation> = emptyList()
)

@Serializable
data class FuelFinderPricesResponse(
    val data: List<FuelFinderPriceRecord> = emptyList()
)

// --- postcodes.io models ---

@Serializable
data class PostcodeResult(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class PostcodeResponse(
    val status: Int,
    val result: PostcodeResult? = null
)

// --- API response models (served to Android app) ---

@Serializable
data class StationWithPrices(
    val nodeId: String,
    val name: String,
    val brand: String?,
    val address: String,
    val postcode: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMiles: Double,
    val isMotorway: Boolean,
    val isSupermarket: Boolean,
    val prices: List<FuelPriceInfo>,
    val temporaryClosure: Boolean
)

@Serializable
data class FuelPriceInfo(
    val fuelType: String,
    val fuelLabel: String,
    val pencePerLitre: Double,
    val lastUpdated: String?,
    val nextRefresh: String
)

@Serializable
data class SearchResponse(
    val postcode: String,
    val radiusMiles: Double,
    val stations: List<StationWithPrices>,
    val dataLastRefreshed: String,
    val nextDataRefresh: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val mode: String,
    val dataLoaded: Boolean,
    val isStale: Boolean,
    val lastPriceRefresh: String,
    val nextPriceRefresh: String,
    val stationCount: Int,
    val priceCount: Int,
    val ingestSource: String,
    val ingesterVersion: String? = null
)

// --- Laptop-ingest models (push mode) ---

/**
 * Payload accepted by POST /admin/ingest.
 *
 * `stations` and `prices` are passthroughs of the upstream Fuel Finder responses
 * (see FuelFinderClient.fetchAllStations / fetchAllPrices).
 */
@Serializable
data class IngestRequest(
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("ingester_version") val ingesterVersion: String? = null,
    val stations: List<FuelFinderStation> = emptyList(),
    val prices: List<FuelFinderPriceRecord> = emptyList()
)

@Serializable
data class IngestResponse(
    val stations: Int,
    val prices: Int,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("previous_fetched_at") val previousFetchedAt: String? = null,
    @SerialName("ingester_version") val ingesterVersion: String? = null
)

@Serializable
data class StaleIngestResponse(
    val error: String,
    @SerialName("current_fetched_at") val currentFetchedAt: String
)
