# Fueller backend

A Ktor server that serves UK petrol-station and fuel-price data to the Fueller Android app.

## Deployment modes

The backend can populate its in-memory cache in one of two ways. The `/api/search` contract served to mobile clients is identical in both.

### Pull mode (default)

The backend periodically calls the UK government Fuel Finder API directly, using OAuth2 client credentials. Used in development and once the deployed backend's egress IP is whitelisted by the Fuel Finder team.

Required configuration:

- `FUEL_FINDER_MODE=pull` (or unset — pull is the default)
- `FUEL_FINDER_CLIENT_ID`
- `FUEL_FINDER_CLIENT_SECRET`

Optional:

- `fuelfinder.baseUrl` (default: `https://stg.fuel-finder.ics.gov.uk`)
- `fuelfinder.priceRefreshMinutes` (default: 30)
- `fuelfinder.stationRefreshHours` (default: 24)
- `fuelfinder.staleAfterMinutes` (default: 90)

### Push mode

A separate ingester (typically a script on a whitelisted laptop or a CI job) fetches data from Fuel Finder and POSTs it to the backend via `POST /admin/ingest`. The backend stores no Fuel Finder credentials in this mode.

Required configuration:

- `FUEL_FINDER_MODE=push`
- `INGEST_TOKEN` (32+ bytes random; shared with the ingester)

Optional:

- `ingest.maxBodyBytes` (default: 33,554,432 — 32 MiB)
- `fuelfinder.staleAfterMinutes` (default: 90)

The first `/api/search` request after startup returns 503 until the first ingest lands. If no ingest arrives for longer than the stale threshold, `/api/search` returns 503 again until the cache is refreshed.

## Endpoints

### `GET /api/search?postcode=SW1A1AA&radius=5`

Public endpoint consumed by the Android app. Identical contract in both modes.

Returns 503 if the cache is empty (`dataLoaded = false`) or stale (`isStale = true`).

### `GET /api/health`

```json
{
  "status": "ok",
  "mode": "push",
  "dataLoaded": true,
  "isStale": false,
  "lastPriceRefresh": "2026-04-29T20:00:00Z",
  "nextPriceRefresh": "2026-04-29T20:30:00Z",
  "stationCount": 8421,
  "priceCount": 8237,
  "ingestSource": "laptop-push",
  "ingesterVersion": "1.0.0"
}
```

External monitoring should poll this endpoint and alert when `isStale: true` or non-200.

### `POST /admin/ingest` (push mode only)

```
POST /admin/ingest
Content-Type: application/json
X-Ingest-Token: <secret>

{
  "fetched_at": "2026-04-29T20:00:00Z",
  "ingester_version": "1.0.0",
  "stations": [ <Fuel Finder station objects, passthrough> ],
  "prices":   [ <Fuel Finder price-record objects, passthrough> ]
}
```

| Status | Meaning |
|---|---|
| 200 | Accepted, cache swapped atomically |
| 400 | Malformed JSON, missing fields, empty arrays, or unparseable `fetched_at` |
| 401 | Missing or wrong `X-Ingest-Token` |
| 409 | `fetched_at` is older than the currently cached snapshot (out-of-order) |
| 413 | Payload exceeds `ingest.maxBodyBytes` |
| 503 | Server is in pull mode |

## Building

```
cd backend
./gradlew build               # compile + tests
./gradlew shadowJar           # produces backend/build/libs/fueller-backend.jar
```

## Running locally

Pull mode (development):

```
export FUEL_FINDER_MODE=pull
export FUEL_FINDER_CLIENT_ID=...
export FUEL_FINDER_CLIENT_SECRET=...
java -jar backend/build/libs/fueller-backend.jar
```

Push mode:

```
export FUEL_FINDER_MODE=push
export INGEST_TOKEN=$(openssl rand -base64 32 | tr -d '=' | tr '/+' '_-')
java -jar backend/build/libs/fueller-backend.jar
```

See `Fueller_LaptopIngest_Design.md` in the workspace folder for the full design rationale and the laptop-side ingester spec.
