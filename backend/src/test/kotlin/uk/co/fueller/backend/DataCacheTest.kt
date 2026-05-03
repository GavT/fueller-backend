package uk.co.fueller.backend

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataCacheTest {

    private fun mkCache(staleAfterMin: Long = 90L) = DataCache(
        client = FuelFinderClient("https://example.invalid", "stub", "stub"),
        priceRefreshMinutes = 30,
        stationRefreshHours = 24,
        staleAfter = Duration.ofMinutes(staleAfterMin)
    )

    private fun mkStation(nodeId: String, lat: Double = 51.5, lon: Double = -0.1) = FuelFinderStation(
        nodeId = nodeId,
        tradingName = "Station $nodeId",
        brandName = "BrandX",
        location = StationLocation(latitude = lat, longitude = lon, postcode = "SW1A 1AA")
    )

    private fun mkPrice(nodeId: String, ppl: Double = 145.9) = FuelFinderPriceRecord(
        nodeId = nodeId,
        fuelPrices = listOf(
            FuelPrice(fuelType = "B7", price = ppl, priceLastUpdated = "2026-04-29T19:00:00Z")
        )
    )

    @Test
    fun `empty cache reports not loaded and stale`() {
        val cache = mkCache()
        assertFalse(cache.isLoaded)
        assertTrue(cache.isStale)
        assertEquals(0, cache.stationCount)
        assertEquals(0, cache.priceCount)
        assertNull(cache.ingesterVersion)
    }

    @Test
    fun `replaceFromIngest populates the cache`() {
        val cache = mkCache()
        val now = Instant.now()
        cache.replaceFromIngest(
            newStations = listOf(mkStation("a"), mkStation("b")),
            newPrices = listOf(mkPrice("a"), mkPrice("b")),
            fetchedAt = now,
            ingesterVersion = "1.0.0"
        )
        assertTrue(cache.isLoaded)
        assertFalse(cache.isStale)
        assertEquals(2, cache.stationCount)
        assertEquals(2, cache.priceCount)
        assertEquals(now, cache.lastPriceRefresh)
        assertEquals("1.0.0", cache.ingesterVersion)
    }

    @Test
    fun `replaceFromIngest replaces all previous data, no leftover keys`() {
        val cache = mkCache()
        cache.replaceFromIngest(
            newStations = (1..100).map { mkStation("s$it") },
            newPrices = (1..100).map { mkPrice("s$it") },
            fetchedAt = Instant.now().minusSeconds(10),
            ingesterVersion = "1.0.0"
        )
        assertEquals(100, cache.stationCount)

        // Replace with a smaller, disjoint set.
        cache.replaceFromIngest(
            newStations = (1..3).map { mkStation("t$it") },
            newPrices = (1..3).map { mkPrice("t$it") },
            fetchedAt = Instant.now(),
            ingesterVersion = "1.0.1"
        )
        assertEquals(3, cache.stationCount)
        assertEquals(3, cache.priceCount)
        assertEquals("1.0.1", cache.ingesterVersion)

        val results = cache.findNearby(51.5, -0.1, 50.0)
        assertTrue(results.isNotEmpty())
        assertTrue(
            results.all { it.nodeId.startsWith("t") },
            "expected only t* nodes after second ingest, got: ${results.map { it.nodeId }}"
        )
    }

    @Test
    fun `isStale becomes true after the configured threshold`() {
        val cache = mkCache(staleAfterMin = 90)
        cache.replaceFromIngest(
            newStations = listOf(mkStation("a")),
            newPrices = listOf(mkPrice("a")),
            fetchedAt = Instant.now().minus(Duration.ofMinutes(91)),
            ingesterVersion = "1.0.0"
        )
        assertTrue(cache.isLoaded)
        assertTrue(cache.isStale)
    }

    @Test
    fun `isStale stays false within the threshold`() {
        val cache = mkCache(staleAfterMin = 90)
        cache.replaceFromIngest(
            newStations = listOf(mkStation("a")),
            newPrices = listOf(mkPrice("a")),
            fetchedAt = Instant.now().minus(Duration.ofMinutes(60)),
            ingesterVersion = "1.0.0"
        )
        assertFalse(cache.isStale)
    }

    @Test
    fun `findNearby honours radius and sorts by distance`() {
        val cache = mkCache()
        cache.replaceFromIngest(
            newStations = listOf(
                mkStation("near", lat = 51.5001, lon = -0.1001),  // ~10m from origin
                mkStation("far",  lat = 52.0,    lon = -0.1)      // ~35 miles north
            ),
            newPrices = listOf(mkPrice("near"), mkPrice("far")),
            fetchedAt = Instant.now(),
            ingesterVersion = "1.0.0"
        )

        val small = cache.findNearby(51.5, -0.1, radiusMiles = 1.0)
        assertEquals(listOf("near"), small.map { it.nodeId })

        val wide = cache.findNearby(51.5, -0.1, radiusMiles = 50.0)
        assertEquals(listOf("near", "far"), wide.map { it.nodeId })
    }

    @Test
    fun `findNearby filters out permanently closed stations`() {
        val cache = mkCache()
        val closed = mkStation("closed").copy(permanentClosure = true)
        cache.replaceFromIngest(
            newStations = listOf(mkStation("open"), closed),
            newPrices = listOf(mkPrice("open"), mkPrice("closed")),
            fetchedAt = Instant.now(),
            ingesterVersion = "1.0.0"
        )
        val results = cache.findNearby(51.5, -0.1, 50.0)
        assertEquals(listOf("open"), results.map { it.nodeId })
    }
}
