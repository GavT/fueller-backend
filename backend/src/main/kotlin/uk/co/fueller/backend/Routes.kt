package uk.co.fueller.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.format.DateTimeFormatter

fun Application.configureRoutes(cache: DataCache, client: FuelFinderClient) {
    routing {
        get("/api/search") {
            val postcode = call.parameters["postcode"]
            if (postcode.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("postcode parameter is required"))
                return@get
            }

            val radius = call.parameters["radius"]?.toDoubleOrNull() ?: 5.0
            if (radius <= 0 || radius > 50) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("radius must be between 0 and 50 miles"))
                return@get
            }

            if (!cache.isLoaded) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Data is still loading, please try again shortly"))
                return@get
            }

            val coords = client.geocodePostcode(postcode)
            if (coords == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not find postcode: $postcode"))
                return@get
            }

            val (lat, lon) = coords
            val stations = cache.findNearby(lat, lon, radius)
            val formatter = DateTimeFormatter.ISO_INSTANT

            call.respond(
                SearchResponse(
                    postcode = postcode.trim().uppercase(),
                    radiusMiles = radius,
                    stations = stations,
                    dataLastRefreshed = formatter.format(cache.lastPriceRefresh),
                    nextDataRefresh = formatter.format(cache.nextPriceRefresh)
                )
            )
        }

        get("/api/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    dataLoaded = cache.isLoaded,
                    lastPriceRefresh = DateTimeFormatter.ISO_INSTANT.format(cache.lastPriceRefresh),
                    nextPriceRefresh = DateTimeFormatter.ISO_INSTANT.format(cache.nextPriceRefresh)
                )
            )
        }
    }
}
