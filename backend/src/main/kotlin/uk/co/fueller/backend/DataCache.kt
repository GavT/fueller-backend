package uk.co.fueller.backend

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class DataCache(
    private val client: FuelFinderClient,
    private val priceRefreshMinutes: Long = 30,
    private val stationRefreshHours: Long = 24
) {
    private val log = LoggerFactory.getLogger(DataCache::class.java)
    private val formatter = DateTimeFormatter.ISO_INSTANT

    private val stations = ConcurrentHashMap<String, FuelFinderStation>()
    private val prices = ConcurrentHashMap<String, List<FuelPrice>>()

    @Volatile var lastPriceRefresh: Instant = Instant.EPOCH
        private set
    @Volatile var lastStationRefresh: Instant = Instant.EPOCH
        private set

    val nextPriceRefresh: Instant
        get() = lastPriceRefresh.plusSeconds(priceRefreshMinutes * 60)

    val isLoaded: Boolean
        get() = stations.isNotEmpty()

    private var refreshJob: Job? = null

    fun start(scope: CoroutineScope) {
        refreshJob = scope.launch {
            // Initial full load
            loadStations()
            loadPrices()

            // Periodic refresh
            while (isActive) {
                delay(priceRefreshMinutes * 60 * 1000)
                try {
                    loadPrices(incremental = true)
                } catch (e: Exception) {
                    log.error("Price refresh failed", e)
                }

                if (Instant.now().isAfter(lastStationRefresh.plusSeconds(stationRefreshHours * 3600))) {
                    try {
                        loadStations()
                    } catch (e: Exception) {
                        log.error("Station refresh failed", e)
                    }
                }
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
    }

    private suspend fun loadStations() {
        log.info("Loading all stations...")
        val fetched = client.fetchAllStations()
        stations.clear()
        fetched.forEach { stations[it.nodeId] = it }
        lastStationRefresh = Instant.now()
        log.info("Loaded ${stations.size} stations")
    }

    private suspend fun loadPrices(incremental: Boolean = false) {
        val since = if (incremental && lastPriceRefresh != Instant.EPOCH) {
            formatter.format(lastPriceRefresh)
        } else null

        log.info(if (since != null) "Loading price updates since $since" else "Loading all prices...")
        val fetched = client.fetchAllPrices(since)
        fetched.forEach { record ->
            prices[record.nodeId] = record.fuelPrices
        }
        lastPriceRefresh = Instant.now()
        log.info("Loaded prices for ${fetched.size} stations (total cached: ${prices.size})")
    }

    /** Find stations within [radiusMiles] of the given coordinates, sorted by distance. */
    fun findNearby(lat: Double, lon: Double, radiusMiles: Double): List<StationWithPrices> {
        val nextRefresh = DateTimeFormatter.ISO_INSTANT.format(nextPriceRefresh)

        return stations.values
            .filter { it.location?.latitude != null && it.location.longitude != null }
            .filter { it.permanentClosure != true }
            .map { station ->
                val dist = GeoUtils.distanceMiles(
                    lat, lon,
                    station.location!!.latitude!!,
                    station.location.longitude!!
                )
                station to dist
            }
            .filter { (_, dist) -> dist <= radiusMiles }
            .sortedBy { (_, dist) -> dist }
            .map { (station, dist) ->
                val stationPrices = prices[station.nodeId] ?: emptyList()
                val loc = station.location!!

                val address = listOfNotNull(
                    loc.addressLine1?.lowercase()?.replaceFirstChar { it.uppercase() },
                    loc.addressLine2?.lowercase()?.replaceFirstChar { it.uppercase() },
                    loc.city?.lowercase()?.replaceFirstChar { it.uppercase() }
                ).joinToString(", ")

                StationWithPrices(
                    nodeId = station.nodeId,
                    name = station.tradingName ?: station.brandName ?: "Unknown",
                    brand = station.brandName,
                    address = address,
                    postcode = loc.postcode,
                    latitude = loc.latitude!!,
                    longitude = loc.longitude!!,
                    distanceMiles = (dist * 100).toLong() / 100.0, // round to 2dp
                    isMotorway = station.isMotorway == true,
                    isSupermarket = station.isSupermarket == true,
                    prices = stationPrices.filter { it.price != null }.map { fp ->
                        FuelPriceInfo(
                            fuelType = fp.fuelType,
                            fuelLabel = GeoUtils.fuelLabel(fp.fuelType),
                            pencePerLitre = fp.price!!,
                            lastUpdated = fp.priceLastUpdated,
                            nextRefresh = nextRefresh
                        )
                    },
                    temporaryClosure = station.temporaryClosure == true
                )
            }
    }
}
