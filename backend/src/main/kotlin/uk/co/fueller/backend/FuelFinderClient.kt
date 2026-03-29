package uk.co.fueller.backend

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class FuelFinderClient(
    private val baseUrl: String,
    private val clientId: String,
    private val clientSecret: String
) {
    private val log = LoggerFactory.getLogger(FuelFinderClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 30_000
        }
    }

    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0

    private suspend fun ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return

        log.info("Requesting OAuth access token")
        val response = httpClient.submitForm(
            url = "$baseUrl/api/v1/oauth/generate_access_token",
            formParameters = parameters {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("scope", "fuelfinder.read")
            }
        )

        val rawBody = response.bodyAsText()
        log.info("Token response status: ${response.status}, body: $rawBody")

        if (!response.status.isSuccess()) {
            log.error("Token request failed: ${response.status} - $rawBody")
            throw RuntimeException("Failed to obtain access token: ${response.status}")
        }

        val wrapper = json.decodeFromString<FuelFinderTokenWrapper>(rawBody)
        val token = wrapper.data
        accessToken = token.accessToken
        tokenExpiresAt = System.currentTimeMillis() + (token.expiresIn * 1000)
        log.info("Token obtained, expires in ${token.expiresIn}s")
    }

    /** Fetch all stations (paginated, 500 per batch). */
    suspend fun fetchAllStations(): List<FuelFinderStation> {
        ensureToken()
        val allStations = mutableListOf<FuelFinderStation>()
        var batch = 1

        while (true) {
            log.info("Fetching stations batch $batch")
            val response = httpClient.get("$baseUrl/api/v1/pfs") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("batch-number", batch)
            }

            if (!response.status.isSuccess()) {
                if (response.status == HttpStatusCode.TooManyRequests) {
                    log.warn("Rate limited fetching stations, waiting 60s")
                    kotlinx.coroutines.delay(60_000)
                    continue
                }
                log.error("Failed to fetch stations batch $batch: ${response.status}")
                break
            }

            val rawBody = response.bodyAsText()
            val batch_data = json.decodeFromString<List<FuelFinderStation>>(rawBody)
            if (batch_data.isEmpty()) break

            allStations.addAll(batch_data)
            log.info("Fetched ${batch_data.size} stations (total: ${allStations.size})")
            batch++
        }

        return allStations
    }

    /** Fetch all fuel prices (paginated, 500 per batch). */
    suspend fun fetchAllPrices(since: String? = null): List<FuelFinderPriceRecord> {
        ensureToken()
        val allPrices = mutableListOf<FuelFinderPriceRecord>()
        var batch = 1

        while (true) {
            log.info("Fetching prices batch $batch")
            val response = httpClient.get("$baseUrl/api/v1/pfs/fuel-prices") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("batch-number", batch)
                if (since != null) {
                    parameter("effective-start-timestamp", since)
                }
            }

            if (!response.status.isSuccess()) {
                if (response.status == HttpStatusCode.TooManyRequests) {
                    log.warn("Rate limited fetching prices, waiting 60s")
                    kotlinx.coroutines.delay(60_000)
                    continue
                }
                log.error("Failed to fetch prices batch $batch: ${response.status}")
                break
            }

            val rawBody = response.bodyAsText()
            val batch_data = json.decodeFromString<List<FuelFinderPriceRecord>>(rawBody)
            if (batch_data.isEmpty()) break

            allPrices.addAll(batch_data)
            log.info("Fetched ${batch_data.size} price records (total: ${allPrices.size})")
            batch++
        }

        return allPrices
    }

    /** Geocode a UK postcode using postcodes.io (free, no API key needed). */
    suspend fun geocodePostcode(postcode: String): Pair<Double, Double>? {
        val encoded = postcode.trim().replace(" ", "%20")
        val response = httpClient.get("https://api.postcodes.io/postcodes/$encoded")

        if (!response.status.isSuccess()) {
            log.warn("Postcode lookup failed for '$postcode': ${response.status}")
            return null
        }

        val result = response.body<PostcodeResponse>()
        return result.result?.let { it.latitude to it.longitude }
    }
}
