# Fueller laptop ingester

A small Python script that fetches station and price data from the UK
government Fuel Finder API (OAuth2) and POSTs it to the Fueller backend's
authenticated `/admin/ingest` endpoint.

Used as the data path while the deployed backend's EC2 egress IP awaits
whitelisting by the Fuel Finder support team. Runs from a host whose IP
is already on the Fuel Finder allowlist (typically the project owner's
laptop), on a schedule.

See `Fueller_LaptopIngest_Design.md` in the project workspace folder for
the architectural rationale.

## Prerequisites

- **Python 3.10 or newer.** Check with `python --version`. Install from
  python.org if needed.
- **Network access** from this host to:
  - `https://stg.fuel-finder.ics.gov.uk` (or the production base URL once
    issued) — must succeed without IP-whitelisting errors.
  - `https://<your fueller backend>` — wherever the backend lives.
- **Fuel Finder OAuth2 client credentials** issued by the Fuel Finder team.
- **Backend `INGEST_TOKEN`** — the same shared secret set on the backend
  host's environment.

## Install

From this folder:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

(On macOS/Linux, use `source .venv/bin/activate`.)

## Configure

```powershell
copy .env.example .env
notepad .env
```

Required values:

| Variable | What it is |
|---|---|
| `FUEL_FINDER_CLIENT_ID` | OAuth2 client id from Fuel Finder team |
| `FUEL_FINDER_CLIENT_SECRET` | OAuth2 client secret from Fuel Finder team |
| `BACKEND_URL` | Base URL of the deployed Fueller backend, no trailing slash |
| `INGEST_TOKEN` | Shared secret matching the backend's `INGEST_TOKEN` env var |

Optional (have sensible defaults — see `.env.example`):

`FUEL_FINDER_BASE_URL`, `LOG_DIR`, `MIN_STATIONS`, `MIN_PRICES`,
`MIN_LATLON_FRACTION`.

`.env` is gitignored. **Never commit it.**

## First run (dry mode)

Fetches and validates without POSTing. Use this to sanity-check that
Fuel Finder accepts your laptop's IP and credentials:

```powershell
python fueller_ingest.py --dry-run
```

If the validation thresholds are too strict for the live data, the script
exits 2 with a clear message; lower `MIN_STATIONS` / `MIN_LATLON_FRACTION`
in `.env` and try again.

## Real run

```powershell
python fueller_ingest.py
```

Look for `Ingest accepted` in the log. Then check the backend:

```powershell
curl https://<your backend>/api/health
```

You should see `mode: "push"`, `dataLoaded: true`, `isStale: false`, and
`stationCount` matching what was just pushed.

## Schedule on Windows (every 30 minutes)

1. Edit `fueller_ingester_task.xml`. Replace both occurrences of
   `__EDIT_ME__` with the absolute path to this folder, e.g.
   `C:\Repos\fueller-backend\tools\laptop-ingester`.
2. Import the task:
   ```powershell
   schtasks /create /tn "Fueller Ingester" /xml fueller_ingester_task.xml
   ```
3. Run it once on demand to confirm:
   ```powershell
   schtasks /run /tn "Fueller Ingester"
   ```
4. Confirm via the log file (see "Logs" below).

The task runs every 30 min while the user is logged in, only when the
network is available, and skips overlapping runs. It does **not** run
when the laptop is asleep — see "Caveats" below.

To remove: `schtasks /delete /tn "Fueller Ingester" /f`.

## Logs

Default location:

- Windows: `%LOCALAPPDATA%\Fueller\logs\fueller_ingest.log`
- Linux/macOS: `~/.local/share/fueller/logs/fueller_ingest.log`

Override with `LOG_DIR` in `.env`. Rotates at 5 MiB, keeps 10 backups.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success (or 409 stale-skip — backend already has equal-or-newer data) |
| `1` | Fetch from Fuel Finder failed (network, auth, or upstream error) |
| `2` | Validation of fetched data failed (too few stations, missing geos, etc.) |
| `3` | POST to backend failed (network or 4xx/5xx other than 409) |

Task Scheduler reports these as the task's "Last Run Result".

## Token rotation

When `INGEST_TOKEN` rotates on the backend:

1. Update the new value on the backend host and restart the backend.
2. Update `INGEST_TOKEN` in this folder's `.env`.
3. Run `python fueller_ingest.py --dry-run` (won't POST so token isn't checked) and then a real run to verify.

## Troubleshooting

**`401 Unauthorized` from `/admin/ingest`**
The `INGEST_TOKEN` in your `.env` does not match the value the backend was
started with. Check both sides.

**`409 Conflict` from `/admin/ingest`**
The backend already has data with an equal or newer `fetched_at`. Usually
benign — happens if a previous run somehow landed twice. The script
treats this as a soft success and exits 0.

**`503 Service Unavailable` "ingest disabled in pull mode"**
The backend was started with `FUEL_FINDER_MODE=pull`. Restart it with
`FUEL_FINDER_MODE=push` and `INGEST_TOKEN` set.

**OAuth `401` from Fuel Finder**
Either bad credentials, or your laptop's IP is not on the Fuel Finder
allowlist. Check with the Fuel Finder support team.

**Validation fails with too few stations**
Real Fuel Finder data may be smaller than the default thresholds during
quiet periods. Lower `MIN_STATIONS` / `MIN_PRICES` / `MIN_LATLON_FRACTION`
in `.env`.

## Caveats

- The laptop must be on, awake, and connected for the schedule to run.
  This is acceptable for pre-alpha. For production, this script should
  move to GitHub Actions on a schedule with a static egress IP or a
  small VPS.
- The `.bat` wrapper assumes `python` resolves to a Python 3.10+
  interpreter on PATH. If you use a venv, edit the `PYTHON` variable in
  `run_ingester.bat` to point at `.venv\Scripts\python.exe`.
- Long-term, the right answer is for Fuel Finder to whitelist the EC2
  egress IP and switch the backend to `mode=pull`. This script is the
  bridge until that's done.
