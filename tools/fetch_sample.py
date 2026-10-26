"""Pull real sample data from the user's own Garmin account into tools/out/sample/ (gitignored).

Run: ~/.local/bin/uv run --python 3.12 --no-project python -I tools/fetch_sample.py
Reads API_KEY from ~/.config/garmin/.env. Never prints the key or the JWT.
Stops (exit 2) on any 429 or unexpected status rather than hammering the API.
"""
import io
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import date, timedelta
from pathlib import Path

BASE = "https://personal.connectapi.garmin.com"
OUT = Path(__file__).resolve().parent / "out" / "sample"
START, END = date(2025, 8, 30), date(2026, 10, 23)
DAY = END.isoformat()
FIT_ACCEPT = "application/zip, application/x-zip-compressed, application/octet-stream"
# Output label -> activity typeKey. Strength is pinned to a known activity id.
FIT_PICKS = {"run": "running", "road_ride": "road_biking", "virtual_ride": "virtual_ride",
             "indoor_ride": "indoor_cycling", "swim": "lap_swimming"}
STRENGTH_ID = 22311621604
SPACING_S = 1.0

requests_made = 0
last_request = 0.0
token = ""


def die(msg):
    print("STOP:", msg, file=sys.stderr)
    sys.exit(2)


def read_key():
    for line in Path("~/.config/garmin/.env").expanduser().read_text().splitlines():
        if line.strip().startswith("API_KEY="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    die("API_KEY missing in ~/.config/garmin/.env")


def http(req):
    """One spaced request. Returns (status, body); only 2xx/4xx bodies are returned, 429 stops the run."""
    global requests_made, last_request
    wait = SPACING_S - (time.monotonic() - last_request)
    if wait > 0:
        time.sleep(wait)
    last_request = time.monotonic()
    requests_made += 1
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        if e.code == 429:
            die("HTTP 429 rate limited")
        return e.code, e.read()


def mint():
    global token
    status, body = http(urllib.request.Request(
        BASE + "/personalapitoken-service/personal-api/personal-api-key/token", method="POST",
        headers={"Accept": "*/*", "Authorization": f"ApiKey {read_key()}"}))
    if status != 200:
        die(f"token mint returned {status}")
    token = body.decode().strip()


def get(path, params=None, accept="application/json"):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    for attempt in (1, 2):
        status, body = http(urllib.request.Request(
            url, headers={"Accept": accept, "Authorization": f"Bearer {token}"}))
        if status == 401 and attempt == 1:
            mint()
            continue
        return status, body


def save(name, path, params=None):
    """Fetch JSON, write it raw. 200 and 204 are fine; anything else stops the run."""
    status, body = get(path, params)
    if status not in (200, 204):
        die(f"{status} for {name}")
    (OUT / f"{name}.json").write_bytes(body or b"")
    print(f"{status} {name} ({len(body or b'')} bytes)")
    return json.loads(body) if status == 200 and body else None


def windows(days):
    s = START
    while s <= END:
        e = min(s + timedelta(days=days - 1), END)
        yield s.isoformat(), e.isoformat()
        s = e + timedelta(days=1)


def bb_chunks():
    """Range check for Body Battery chunks=288: one 28-day window plus the single day 2026-10-23.

    Prints per-day sample counts; saves the day item to tools/out/sample/bodybattery_chunks_day.json.
    """
    OUT.mkdir(parents=True, exist_ok=True)
    mint()
    path = "/wellness-service/wellness/bodyBattery/reports/daily"
    for a, b, name in [("2026-09-26", "2026-10-23", "bodybattery_chunks_28d"), ("2026-10-23", "2026-10-23", "bodybattery_chunks_day")]:
        data = save(name, path, {"startDate": a, "endDate": b, "chunks": 288})
        for item in data or []:
            rows = item.get("bodyBatteryValuesArray") or []
            ts = [r[0] for r in rows]
            print(item["date"], "samples:", len(rows), "unique ts:", len(set(ts)),
                  "non-null levels:", sum(1 for r in rows if r[1] is not None))


def main():
    if "--bb-chunks" in sys.argv:
        return bb_chunks()
    OUT.mkdir(parents=True, exist_ok=True)
    t0 = time.monotonic()
    mint()

    save("profile", "/userprofile-service/userprofile/settings")
    save("hr_zones", "/biometric-service/heartRateZones/v2")

    # Range endpoints in max-size windows: (name, days, path template, query-style?)
    for a, b in windows(180):
        save(f"range_dailysummaries_{a}", "/usersummary-service/v2/dailysummaries", {"startDate": a, "endDate": b})
        save(f"range_hrv_{a}", f"/hrv-service/hrv/daily/{a}/{b}")
    for a, b in windows(28):
        save(f"range_sleep_summaries_{a}", "/sleep-service/sleep/dailySleepSummariesByDate", {"startDate": a, "endDate": b})
        save(f"range_bodybattery_{a}", "/wellness-service/wellness/bodyBattery/reports/daily", {"startDate": a, "endDate": b})
    for a, b in windows(7):
        save(f"range_stress_{a}", f"/usersummary-service/stats/stress/daily/{a}/{b}")
        save(f"range_steps_{a}", f"/usersummary-service/stats/steps/daily/{a}/{b}")

    save("sleep_day", "/sleep-service/sleep/dailySleep", {"date": DAY})
    save("hrv_day", f"/hrv-service/hrv/{DAY}")
    save("heartrate_day", "/wellness-service/wellness/dailyHeartRate", {"date": DAY})
    save("spo2_day", f"/wellness-service/wellness/daily/spo2acclimation/{DAY}")

    acts, start = [], 0
    while True:
        page = save(f"activities_p{start // 50}", "/activitylist-service/activities/search/activities",
                    {"start": start, "limit": 50})
        if not page:
            break
        acts += page
        if len(page) < 50:
            break
        start += 50
    (OUT / "activities_all.json").write_text(json.dumps(acts))
    print("activities total:", len(acts))

    picks = {"strength": STRENGTH_ID}
    for label, type_key in FIT_PICKS.items():
        cands = [a for a in acts if a["activityType"]["typeKey"] == type_key]
        if not cands:
            die(f"no activity of type {type_key}")
        picks[label] = max(cands, key=lambda a: a["startTimeGMT"])["activityId"]
    if not any(a["activityId"] == STRENGTH_ID for a in acts):
        die("strength activity 22311621604 not in activity list")
    for label, aid in picks.items():
        status, body = get(f"/download-service/files/activity/{aid}", accept=FIT_ACCEPT)
        if status != 200:
            die(f"{status} for FIT {label}")
        zf = zipfile.ZipFile(io.BytesIO(body))
        fits = [n for n in zf.namelist() if n.lower().endswith(".fit")]
        if len(fits) != 1:
            die(f"FIT zip for {label} has {len(fits)} .fit files")
        (OUT / f"fit_{label}.fit").write_bytes(zf.read(fits[0]))
        print(f"200 fit_{label} (activity {aid})")
    (OUT / "fit_picks.json").write_text(json.dumps(picks))

    print(f"done: {requests_made} requests in {time.monotonic() - t0:.0f}s")


if __name__ == "__main__":
    main()
