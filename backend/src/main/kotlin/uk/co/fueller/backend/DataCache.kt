package uk.co.fueller.backend

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory store of stations and prices, served to /api/search.
 *
 * Two modes of update:
 *   - Pull (start()):           a coroutine periodically calls FuelFinderClient and reloads from upstream.
 *   - Push (replaceFromIngest): an external ingester (laptop or scheduled job) supplies the data via POST /admin/ingest.
 *
 * Reads ([findNearby]) operate on immutable snapshots held in AtomicReferences so writers swap state atomically.
 */
class DataCache(
    private val client: FuelFinderClient,
    private val priceRefreshMinutes: Long = 30,
    private val stationRefreshHours: Long = 24,
    private val staleAfter: Duration = Duration.ofMinutes(90)
) {
    private val log = LoggerFactory.getLogger(DataCache::class.java)
    private val formatter = DateTimeFormatter.ISO_INSTANT

    private val stations = AtomicReference<Map<String, FuelFinderStation>>(emptyMap())
    private val prices = AtomicReference<Map<String, List<FuelPrice>>>(emptyMap())

    @Volatile var lastPriceRefresh: Instant = Instant.EPOCH
        private set
    @Volatile var lastStationRefresh: Instant = Instant.EPOCH
        private set
    @Volatile var ingesterVersion: String? = null
        private set

    val nextPriceRefresh: Instant
        get() = lastPriceRefresh.plusSeconds(priceRefreshMinutes * 60)

    val isLoaded: Boolean
        get() = stations.get().isNotEmpty()

    val isStale: Boolean
        get() = !isLoaded || Instant.now().isAfter(lastPriceRefresh.plus(staleAfter))

    val stationCount: Int
        get() = stations.get().size

    val priceCount: Int
        get() = prices.get().size

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
        stations.set(fetched.mapNotNull { s -> s.nodeId?.let { id -> id to s } }.toMap())
        lastStationRefresh = Instant.now()
        log.info("Loaded ${stations.get().size} stations")
    }

    private suspend fun loadPrices(incremental: Boolean = false) {
        val since = if (incremental && lastPriceRefresh != Instant.EPOCH) {
            formatter.format(lastPriceRefresh)
        } else null

        log.info(if (since != null) "Loading price updates since $since" else "Loading all prices...")
        val fetched = client.fetchAllPrices(since)

        if (incremental && since != null) {
            // Merge incremental updates into the existing snapshot.
            val merged = prices.get().toMutableMap()
            fetched.forEach { record -> record.nodeId?.let { merged[it] = record.fuelPrices } }
            prices.set(merged)
        } else {
            prices.set(fetched.mapNotNull { r -> r.nodeId?.let { id -> id to r.fuelPrices } }.toMap())
        }
        lastPriceRefresh = Instant.now()
        log.info("Loaded prices for ${fetched.size} stations (total cached: ${prices.get().size})")
    }

    /**
     * Atomically replace the cache with data from the laptop ingester (push mode).
     * Replaces all stations and prices in a single, externally-visible step.
     *
     * @param fetchedAt the upstream fetch timestamp (becomes [lastPriceRefresh]).
     */
    fun replaceFromIngest(
        newStations: List<FuelFinderStation>,
        newPrices: List<FuelFinderPriceRecord>,
        fetchedAt: Instant,
        ingesterVersion: String? = null
    ) {
        stations.set(newStations.mapNotNull { s -> s.nodeId?.let { id -> id to s } }.toMap())
        prices.set(newPrices.mapNotNull { r -> r.nodeId?.let { id -> id to r.fuelPrices } }.toMap())
        lastPriceRefresh = fetchedAt
        lastStationRefresh = fetchedAt
        this.ingesterVersion = ingesterVersion
        log.info(
            "Ingest applied: ${newStations.size} stations, ${newPrices.size} price records, " +
                "fetched_at=$fetchedAt, ingester=${ingesterVersion ?: "(unknown)"}"
        )
    }

    /** Find stations within [radiusMiles] of the given coordinates, sorted by distance. */
    fun findNearby(lat: Double, lon: Double, radiusMiles: Double): List<StationWithPrices> {
        val nextRefresh = DateTimeFormatter.ISO_INSTANT.format(nextPriceRefresh)
        val stationSnapshot = stations.get()
        val priceSnapshot = prices.get()

        return stationSnapshot.values
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
                val stationPrices = priceSnapshot[station.nodeId] ?: emptyList()
                val loc = station.location!!

                val address = listOfNotNull(
                    loc.addressLine1?.lowercase()?.replaceFirstChar { it.uppercase() },
                    loc.addressLine2?.lowercase()?.replaceFirstChar { it.uppercase() },
                    loc.city?.lowercase()?.replaceFirstChar { it.uppercase() }
                ).joinToString(", ")

                StationWithPrices(
                    nodeId = station.nodeId!!,  // cache only contains non-null ids (filtered upstream)
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
