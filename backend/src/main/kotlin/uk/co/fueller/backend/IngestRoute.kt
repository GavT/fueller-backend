package uk.co.fueller.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("IngestRoute")

/**
 * Registers POST /admin/ingest. Active only in push mode; in pull mode the route
 * is still registered but always returns 503 so misrouted requests are visible
 * rather than 404'd.
 */
fun Routing.ingestRoutes(
    cache: DataCache,
    mode: String,
    ingestToken: String?,
    maxIngestBytes: Long
) {
    post("/admin/ingest") {
        // 1. Mode check.
        if (mode != "push") {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("ingest disabled in pull mode"))
            return@post
        }

        // 2. Token check (constant-time). In push mode the startup validation in
        //    Application.module() guarantees ingestToken is non-blank.
        val provided = call.request.header("X-Ingest-Token")
        val expected = ingestToken
        if (expected.isNullOrBlank() || !tokensMatch(provided, expected)) {
            log.warn("Rejected ingest from ${call.request.local.remoteHost}: bad/missing token")
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            return@post
        }

        // 3. Body size guard. Content-Length is advisory, but rejecting an
        //    oversized declared length avoids buffering a huge body before parsing.
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > maxIngestBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload too large"))
            return@post
        }

        // 4. Parse and validate.
        val request: IngestRequest = try {
            call.receive()
        } catch (e: Exception) {
            log.warn("Malformed ingest payload", e)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed payload: ${e.message}"))
            return@post
        }

        val fetchedAt = try {
            Instant.parse(request.fetchedAt)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("fetched_at must be ISO-8601 UTC (got '${request.fetchedAt}')")
            )
            return@post
        }

        if (request.stations.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("stations array must not be empty"))
            return@post
        }
        if (request.prices.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("prices array must not be empty"))
            return@post
        }

        // 5. Out-of-order check: refuse a payload whose fetched_at is older than
        //    or equal to the currently cached one. The first ever ingest passes
        //    this check because lastPriceRefresh starts at Instant.EPOCH.
        val previousFetchedAt = cache.lastPriceRefresh
        if (previousFetchedAt != Instant.EPOCH && !fetchedAt.isAfter(previousFetchedAt)) {
            call.respond(
                HttpStatusCode.Conflict,
                StaleIngestResponse(
                    error = "stale ingest",
                    currentFetchedAt = DateTimeFormatter.ISO_INSTANT.format(previousFetchedAt)
                )
            )
            return@post
        }

        // 6. Apply atomically.
        cache.replaceFromIngest(
            newStations = request.stations,
            newPrices = request.prices,
            fetchedAt = fetchedAt,
            ingesterVersion = request.ingesterVersion
        )

        call.respond(
            IngestResponse(
                stations = request.stations.size,
                prices = request.prices.size,
                fetchedAt = DateTimeFormatter.ISO_INSTANT.format(fetchedAt),
                previousFetchedAt = if (previousFetchedAt != Instant.EPOCH)
                    DateTimeFormatter.ISO_INSTANT.format(previousFetchedAt) else null,
                ingesterVersion = request.ingesterVersion
            )
        )
    }
}

/** Constant-time string comparison; defeats timing-side-channel attacks on the ingest token. */
private fun tokensMatch(provided: String?, expected: String): Boolean {
    if (provided == null) return false
    val a = provided.toByteArray(Charsets.UTF_8)
    val b = expected.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(a, b)
}
