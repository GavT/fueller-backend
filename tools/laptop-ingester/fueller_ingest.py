#!/usr/bin/env python3
"""Fueller laptop ingester.

Fetches station and price data from the UK government Fuel Finder API
(OAuth2) and POSTs it to the Fueller backend's /admin/ingest endpoint.
Runs from a host whose IP is whitelisted by Fuel Finder (typically the
project owner's laptop) while the deployed backend's IP awaits whitelisting.

Designed to be invoked on a schedule (Windows Task Scheduler, cron).

Configuration: environment variables, with a .env file in the script
directory loaded automatically if present. See .env.example for the full
list. Required:

    FUEL_FINDER_CLIENT_ID
    FUEL_FINDER_CLIENT_SECRET
    BACKEND_URL                 (e.g. https://api.fueller.example.com)
    INGEST_TOKEN                (matches the backend's INGEST_TOKEN env var)

Optional:

    FUEL_FINDER_BASE_URL        default: https://stg.fuel-finder.ics.gov.uk
    LOG_DIR                     default: %LOCALAPPDATA%\\Fueller\\logs (Win)
                                         ~/.local/share/fueller/logs    (nix)
    MIN_STATIONS                default: 1000
    MIN_PRICES                  default: 1000
    MIN_LATLON_FRACTION         default: 0.99 (0.0..1.0)

Exit codes:
    0   success (or 409 stale-skip — backend already had newer data)
    1   fetch from Fuel Finder failed
    2   validation of fetched data failed
    3   POST to backend failed
"""
from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

INGESTER_VERSION = "1.0.0"

EXIT_OK = 0
EXIT_FETCH_FAILURE = 1
EXIT_VALIDATION_FAILURE = 2
EXIT_INGEST_FAILURE = 3


# ---------------------------- config / env ---------------------------- #

def load_env_file(path: Path) -> None:
    """Load KEY=VALUE pairs from a .env file into os.environ.

    Lines starting with # are comments. Surrounding single/double quotes
    on values are stripped. Existing environment variables are NOT
    overwritten (so explicit env vars from Task Scheduler still win).
    """
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if (value.startswith('"') and value.endswith('"')) or \
           (value.startswith("'") and value.endswith("'")):
            value = value[1:-1]
        os.environ.setdefault(key, value)


def get_env(name: str, default: str | None = None, required: bool = True) -> str:
    value = os.environ.get(name, default)
    if required and not value:
        raise ValueError(f"required environment variable {name} is not set")
    return value or ""


def default_log_dir() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local")))
        return base / "Fueller" / "logs"
    return Path.home() / ".local" / "share" / "fueller" / "logs"


def setup_logging(log_dir: Path, level: str = "INFO") -> logging.Logger:
    log_dir.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("fueller_ingest")
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))
    logger.handlers.clear()

    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")

    fh = logging.handlers.RotatingFileHandler(
        log_dir / "fueller_ingest.log",
        maxBytes=5 * 1024 * 1024,
        backupCount=10,
        encoding="utf-8",
    )
    fh.setFormatter(fmt)
    logger.addHandler(fh)

    ch = logging.StreamHandler()
    ch.setFormatter(fmt)
    logger.addHandler(ch)

    return logger


# ---------------------------- Fuel Finder ---------------------------- #

def get_oauth_token(base_url: str, client_id: str, client_secret: str, log: logging.Logger) -> str:
    log.info("Requesting Fuel Finder OAuth2 access token")
    r = requests.post(
        f"{base_url}/api/v1/oauth/generate_access_token",
        data={
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": client_secret,
            "scope": "fuelfinder.read",
        },
        timeout=30,
    )
    if not r.ok:
        log.error("Token request failed: %s — %s", r.status_code, r.text[:500])
        raise RuntimeError(f"token request failed: HTTP {r.status_code}")
    body = r.json()
    if not body.get("success"):
        raise RuntimeError(f"token response indicates failure: {body}")
    token = body["data"]["access_token"]
    expires = body["data"].get("expires_in")
    log.info("OAuth2 token obtained (expires in %ss)", expires)
    return token


def fetch_paginated(
    url: str,
    token: str,
    log: logging.Logger,
    what: str,
    extra_params: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Fetch all pages of a Fuel Finder list endpoint. Stops when an empty
    page is returned (matches the existing backend's pagination contract).
    Retries with backoff on HTTP 429.
    """
    results: list[dict[str, Any]] = []
    batch = 1
    headers = {"Authorization": f"Bearer {token}"}
    while True:
        params: dict[str, Any] = {"batch-number": batch}
        if extra_params:
            params.update(extra_params)
        log.info("Fetching %s batch %d", what, batch)
        r = requests.get(url, headers=headers, params=params, timeout=60)

        if r.status_code == 429:
            log.warning("Rate limited fetching %s batch %d, waiting 60s", what, batch)
            time.sleep(60)
            continue
        if not r.ok:
            log.error("Fetch %s batch %d failed: %s — %s", what, batch, r.status_code, r.text[:300])
            raise RuntimeError(f"fetch {what} batch {batch} failed: HTTP {r.status_code}")

        body = r.json()
        if not body:
            break
        if not isinstance(body, list):
            raise RuntimeError(f"expected list response for {what} batch {batch}, got {type(body).__name__}")
        results.extend(body)
        log.info("Fetched %d %s (cumulative %d)", len(body), what, len(results))
        batch += 1
    return results


# ---------------------------- validation ---------------------------- #

def validate(
    stations: list[dict[str, Any]],
    prices: list[dict[str, Any]],
    log: logging.Logger,
    min_stations: int,
    min_prices: int,
    min_latlon_fraction: float,
) -> None:
    if len(stations) < min_stations:
        raise RuntimeError(
            f"only {len(stations)} stations (expected >= {min_stations}). "
            "If this is the first real run and the threshold is too high, "
            "lower MIN_STATIONS in your environment."
        )
    if len(prices) < min_prices:
        raise RuntimeError(f"only {len(prices)} price records (expected >= {min_prices})")

    no_node_id = sum(1 for s in stations if not s.get("node_id"))
    if no_node_id:
        raise RuntimeError(f"{no_node_id} station(s) missing node_id")

    with_latlon = sum(
        1 for s in stations
        if (s.get("location") or {}).get("latitude") is not None
        and (s.get("location") or {}).get("longitude") is not None
    )
    fraction = with_latlon / len(stations)
    if fraction < min_latlon_fraction:
        raise RuntimeError(
            f"only {fraction:.2%} of stations have lat/lon (need >= {min_latlon_fraction:.2%}). "
            "Lower MIN_LATLON_FRACTION if this is acceptable for the current data."
        )

    no_price_node_id = sum(1 for p in prices if not p.get("node_id"))
    if no_price_node_id:
        raise RuntimeError(f"{no_price_node_id} price record(s) missing node_id")

    log.info(
        "Validation OK: %d stations (%d/%d, %.2f%% with lat/lon), %d price records",
        len(stations), with_latlon, len(stations), fraction * 100, len(prices),
    )


# ---------------------------- ingest ---------------------------- #

def push_to_backend(
    backend_url: str,
    ingest_token: str,
    stations: list[dict[str, Any]],
    prices: list[dict[str, Any]],
    fetched_at: str,
    log: logging.Logger,
    timeout: int = 120,
) -> tuple[int, dict[str, Any]]:
    payload = {
        "fetched_at": fetched_at,
        "ingester_version": INGESTER_VERSION,
        "stations": stations,
        "prices": prices,
    }
    body = json.dumps(payload).encode("utf-8")
    log.info("POST %s/admin/ingest (%.2f MiB)", backend_url, len(body) / 1024 / 1024)

    r = requests.post(
        f"{backend_url}/admin/ingest",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Ingest-Token": ingest_token,
        },
        timeout=timeout,
    )

    try:
        response_body = r.json()
    except ValueError:
        response_body = {"_raw": r.text[:500]}

    if r.status_code == 409:
        log.warning("Ingest stale (409) — backend already has equal-or-newer data: %s", response_body)
        return r.status_code, response_body
    if not r.ok:
        log.error("Ingest failed: %s — %s", r.status_code, response_body)
        raise RuntimeError(f"ingest failed: HTTP {r.status_code}")

    log.info("Ingest accepted: %s", response_body)
    return r.status_code, response_body


# ---------------------------- main ---------------------------- #

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Fueller laptop ingester")
    p.add_argument("--dry-run", action="store_true",
                   help="Fetch and validate but do not POST to the backend.")
    p.add_argument("--log-level", default=os.environ.get("LOG_LEVEL", "INFO"),
                   help="Logging level (DEBUG, INFO, WARNING). Default INFO.")
    return p.parse_args()


def main() -> int:
    args = parse_args()

    # Load .env from the script's directory if present (does not override real env vars).
    script_dir = Path(__file__).parent.resolve()
    load_env_file(script_dir / ".env")

    log_dir = Path(os.environ.get("LOG_DIR") or str(default_log_dir()))
    log = setup_logging(log_dir, level=args.log_level)
    log.info("=== Fueller ingester %s starting (dry_run=%s, log_dir=%s) ===",
             INGESTER_VERSION, args.dry_run, log_dir)

    try:
        client_id = get_env("FUEL_FINDER_CLIENT_ID")
        client_secret = get_env("FUEL_FINDER_CLIENT_SECRET")
        ff_base_url = get_env("FUEL_FINDER_BASE_URL", "https://stg.fuel-finder.ics.gov.uk", required=False).rstrip("/")
        backend_url = get_env("BACKEND_URL").rstrip("/") if not args.dry_run else os.environ.get("BACKEND_URL", "").rstrip("/")
        ingest_token = get_env("INGEST_TOKEN") if not args.dry_run else os.environ.get("INGEST_TOKEN", "")

        min_stations = int(os.environ.get("MIN_STATIONS", "1000"))
        min_prices = int(os.environ.get("MIN_PRICES", "1000"))
        min_latlon_fraction = float(os.environ.get("MIN_LATLON_FRACTION", "0.99"))
    except (ValueError, TypeError) as e:
        log.error("config error: %s", e)
        return EXIT_VALIDATION_FAILURE

    fetched_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # --- Fetch ---
    try:
        token = get_oauth_token(ff_base_url, client_id, client_secret, log)
        stations = fetch_paginated(f"{ff_base_url}/api/v1/pfs", token, log, "stations")
        prices = fetch_paginated(f"{ff_base_url}/api/v1/pfs/fuel-prices", token, log, "prices")
    except Exception as e:
        log.exception("fetch failed: %s", e)
        return EXIT_FETCH_FAILURE

    # --- Validate ---
    try:
        validate(stations, prices, log, min_stations, min_prices, min_latlon_fraction)
    except Exception as e:
        log.error("validation failed: %s", e)
        return EXIT_VALIDATION_FAILURE

    if args.dry_run:
        log.info("=== Dry run — skipping POST to backend ===")
        return EXIT_OK

    # --- Push ---
    try:
        status, body = push_to_backend(backend_url, ingest_token, stations, prices, fetched_at, log)
        if status == 409:
            log.info("=== Stale ingest skipped — exiting OK ===")
            return EXIT_OK
    except Exception as e:
        log.exception("ingest failed: %s", e)
        return EXIT_INGEST_FAILURE

    log.info("=== Fueller ingester completed successfully ===")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
