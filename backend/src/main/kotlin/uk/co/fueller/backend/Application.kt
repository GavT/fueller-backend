package uk.co.fueller.backend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.time.Duration

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val config = environment.config

    // Mode: "pull" (existing scheduled fetch from Fuel Finder) or "push"
    // (laptop ingester POSTs to /admin/ingest). Defaults to "pull" for backwards compat.
    val mode = (config.propertyOrNull("fuelfinder.mode")?.getString()
        ?: System.getenv("FUEL_FINDER_MODE")
        ?: "pull").lowercase()

    require(mode == "pull" || mode == "push") {
        "fuelfinder.mode must be 'pull' or 'push' (got '$mode')"
    }

    val baseUrl = config.propertyOrNull("fuelfinder.baseUrl")?.getString()
        ?: "https://www.fuel-finder.service.gov.uk"

    val priceRefreshMinutes = config.propertyOrNull("fuelfinder.priceRefreshMinutes")?.getString()?.toLongOrNull()
        ?: 30L

    val stationRefreshHours = config.propertyOrNull("fuelfinder.stationRefreshHours")?.getString()?.toLongOrNull()
        ?: 24L

    val staleAfterMinutes = config.propertyOrNull("fuelfinder.staleAfterMinutes")?.getString()?.toLongOrNull()
        ?: 90L

    // Pull-mode credentials. Optional in push mode (the laptop holds them).
    val clientId = config.propertyOrNull("fuelfinder.clientId")?.getString()
        ?: System.getenv("FUEL_FINDER_CLIENT_ID")

    val clientSecret = config.propertyOrNull("fuelfinder.clientSecret")?.getString()
        ?: System.getenv("FUEL_FINDER_CLIENT_SECRET")

    // Push-mode shared secret. Optional in pull mode.
    val ingestToken = config.propertyOrNull("ingest.token")?.getString()
        ?: System.getenv("INGEST_TOKEN")

    val maxIngestBytes = config.propertyOrNull("ingest.maxBodyBytes")?.getString()?.toLongOrNull()
        ?: 33_554_432L  // 32 MiB

    // Per-mode validation: fail fast at startup if required config is missing.
    when (mode) {
        "pull" -> {
            require(!clientId.isNullOrBlank()) { "FUEL_FINDER_CLIENT_ID is required when mode = 'pull'" }
            require(!clientSecret.isNullOrBlank()) { "FUEL_FINDER_CLIENT_SECRET is required when mode = 'pull'" }
        }
        "push" -> {
            require(!ingestToken.isNullOrBlank()) { "INGEST_TOKEN is required when mode = 'push'" }
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Ingest-Token")
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal error"))
        }
    }

    // The FuelFinderClient is constructed in both modes:
    //   - pull mode: used for OAuth2 + station/price fetches AND postcode geocoding.
    //   - push mode: used only for postcode geocoding via postcodes.io (no OAuth needed).
    val client = FuelFinderClient(
        baseUrl = baseUrl,
        clientId = clientId ?: "",
        clientSecret = clientSecret ?: ""
    )

    val cache = DataCache(
        client = client,
        priceRefreshMinutes = priceRefreshMinutes,
        stationRefreshHours = stationRefreshHours,
        staleAfter = Duration.ofMinutes(staleAfterMinutes)
    )

    if (mode == "pull") {
        val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cache.start(cacheScope)
        environment.monitor.subscribe(ApplicationStopped) { cache.stop() }
        log.info(
            "Started in PULL mode (price refresh ${priceRefreshMinutes}m, station refresh ${stationRefreshHours}h, " +
                "stale threshold ${staleAfterMinutes}m)"
        )
    } else {
        log.info(
            "Started in PUSH mode — cache populated by POST /admin/ingest. " +
                "Stale threshold ${staleAfterMinutes}m."
        )
    }

    configureRoutes(
        cache = cache,
        client = client,
        mode = mode,
        ingestToken = ingestToken,
        maxIngestBytes = maxIngestBytes
    )

    log.info("Fueller backend started on port ${config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
}
