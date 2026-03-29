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

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val config = environment.config

    val clientId = config.propertyOrNull("fuelfinder.clientId")?.getString()
        ?: System.getenv("FUEL_FINDER_CLIENT_ID")
        ?: error("FUEL_FINDER_CLIENT_ID not set")

    val clientSecret = config.propertyOrNull("fuelfinder.clientSecret")?.getString()
        ?: System.getenv("FUEL_FINDER_CLIENT_SECRET")
        ?: error("FUEL_FINDER_CLIENT_SECRET not set")

    val baseUrl = config.propertyOrNull("fuelfinder.baseUrl")?.getString()
        ?: "https://www.fuel-finder.service.gov.uk"

    val priceRefreshMinutes = config.propertyOrNull("fuelfinder.priceRefreshMinutes")?.getString()?.toLongOrNull()
        ?: 30L

    val stationRefreshHours = config.propertyOrNull("fuelfinder.stationRefreshHours")?.getString()?.toLongOrNull()
        ?: 24L

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal error"))
        }
    }

    val client = FuelFinderClient(baseUrl, clientId, clientSecret)
    val cache = DataCache(client, priceRefreshMinutes, stationRefreshHours)

    val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    cache.start(cacheScope)

    environment.monitor.subscribe(ApplicationStopped) {
        cache.stop()
    }

    configureRoutes(cache, client)

    log.info("Fueller backend started on port ${config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
}
