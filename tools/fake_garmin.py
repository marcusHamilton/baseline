"""A fake Garmin Personal API that serves the scrubbed fixtures, for emulator testing without a real key.

Run:  ~/.local/bin/uv run --python 3.12 --no-project python -I tools/fake_garmin.py [--port 8080] [--key fake-garmin-key] [--today YYYY-MM-DD]
Build: ./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080   (after: adb reverse tcp:8080 tcp:8080)
Then paste the fake key ("fake-garmin-key" by default) in onboarding or Settings. Any other key gets a 401, so the
invalid-key path can be tested too. A build made with -PgarminBaseUrl allows cleartext and is named "…-fake": never ship one.

Every endpoint in GarminEndpoint.kt is answered from app/src/test/resources/fixtures/, with the dates moved forward
so the newest data lands on "today" (the host's date, or --today YYYY-MM-DD for an emulator whose clock is pinned).
The fixture files are never touched: dates are shifted in memory as each response is built.
  - History fixtures move by a whole number of weeks (weekdays and weekly aggregates stay put), so they end 0-6 days
    short of today; those trailing days are filled by repeating the same weekday of the week before.
  - Ranged endpoints return only the rows inside the requested window.
  - Per-day endpoints (sleep, heart rate, HRV, SpO2) return the one-day fixture re-dated to the requested date.
  - Activities move with the history. FIT downloads are zipped from fixtures/fit/ by activity type, with the
    fixtures' own timestamps (other types get a 404, which the app records as a failed task). Stdlib only.
"""
import argparse
import base64
import io
import json
import re
import sys
import time
import zipfile
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

FIXTURES = Path(__file__).resolve().parent.parent / "app" / "src" / "test" / "resources" / "fixtures"
MINT_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
ACTIVITY_SEARCH = "/activitylist-service/activities/search/activities"
# Ranged endpoints: (path prefix, fixture, key of the row list or None if the file is a bare list, where the window is).
# First match wins, so the more specific prefixes come first. The window is the startDate/endDate query or the last two
# path segments.
RANGED_ROUTES = [
    ("/usersummary-service/v2/dailysummaries", "dailysummaries_history.json", "data", "query"),
    ("/hrv-service/hrv/daily/", "hrv_history.json", "hrvSummaries", "path"),
    ("/sleep-service/sleep/dailySleepSummariesByDate", "sleep_summaries_history.json", None, "query"),
    ("/wellness-service/wellness/bodyBattery/reports/daily", "bodybattery_history.json", None, "query"),
    ("/usersummary-service/stats/stress/daily/", "stress_history.json", None, "path"),
    ("/usersummary-service/stats/steps/daily/", "steps_history.json", None, "path"),
]
# Per-day endpoints: (path prefix, fixture). The day is the `date` query or the last path segment.
DAY_ROUTES = [
    ("/sleep-service/sleep/dailySleep", "sleep_day.json"),
    ("/hrv-service/hrv/", "hrv_day.json"),
    ("/wellness-service/wellness/dailyHeartRate", "heartrate_day.json"),
    ("/wellness-service/wellness/daily/spo2acclimation/", "spo2_day.json"),
]
# Not date-bearing: served as they are.
STATIC_ROUTES = [
    ("/userprofile-service/userprofile/settings", "profile.json"),
    ("/biometric-service/heartRateZones", "hr_zones.json"),
]
# Activity typeKey -> fixtures/fit/<name>.fit
FIT_FOR = {"running": "run", "road_biking": "road_ride", "virtual_ride": "virtual_ride",
           "indoor_cycling": "indoor_ride", "lap_swimming": "swim", "strength_training": "strength"}
JWT_LIFETIME_S = 24 * 3600


ISO_DATE = re.compile(r"\d{4}-\d\d-\d\d")
MS_PER_DAY = 86_400_000
EPOCH_MS = range(1_500_000_000_000, 2_000_000_000_000)  # 2017-2033: every epoch-millisecond field in the fixtures


def shift(obj, days: int):
    """A copy of [obj] with every date moved by [days]: YYYY-MM-DD inside strings, and epoch-millisecond ints."""
    if days == 0:
        return obj
    if isinstance(obj, dict):
        return {k: shift(v, days) for k, v in obj.items()}
    if isinstance(obj, list):
        return [shift(v, days) for v in obj]
    if isinstance(obj, str):
        return ISO_DATE.sub(lambda m: (date.fromisoformat(m.group()) + timedelta(days=days)).isoformat(), obj)
    if isinstance(obj, int) and not isinstance(obj, bool) and obj in EPOCH_MS:
        return obj + days * MS_PER_DAY
    return obj


def row_date(row) -> date:
    """The day a history row is for. Garmin's all-null sleep placeholders have no calendarDate of their own, so the
    first date anywhere in the row (their nested sleepNeed.calendarDate) stands in."""
    own = row.get("calendarDate") or row.get("date")
    return date.fromisoformat(own or ISO_DATE.search(json.dumps(row)).group())


def load(name: str):
    return json.loads((FIXTURES / name).read_text())


def rows_of(fixture, key):
    return fixture[key] if key else fixture


class Dataset:
    """The fixtures moved forward so the newest data is [today], built once at startup."""

    def __init__(self, today: date):
        self.today = today
        self.history = {name: load(name) for _, name, _, _ in RANGED_ROUTES}
        anchor = max(row_date(r) for _, name, key, _ in RANGED_ROUTES for r in rows_of(self.history[name], key))
        # Whole weeks, rounded down, so the newest rows never land after today.
        self.delta = (today - anchor).days // 7 * 7
        self.rows = {}  # fixture -> {date -> row}, shifted, up to today
        for _, name, key, _ in RANGED_ROUTES:
            shifted = {}
            for r in rows_of(self.history[name], key):
                r = shift(r, self.delta)
                if row_date(r) <= today:
                    shifted[row_date(r)] = r
            # Days between the last shifted row and today repeat the same weekday of the week before.
            d = max(shifted) + timedelta(days=1)
            while d <= today:
                if d - timedelta(days=7) in shifted:
                    shifted[d] = shift(shifted[d - timedelta(days=7)], 7)
                d += timedelta(days=1)
            self.rows[name] = shifted
        self.activities = [a for a in (shift(a, self.delta) for a in load("activities_all.json"))
                           if a["startTimeLocal"][:10] <= today.isoformat()]

    def window(self, name: str, key, start: str, end: str):
        rows = [r for d, r in sorted(self.rows[name].items()) if start <= d.isoformat() <= end]
        return {key: rows} if key else rows

    def day(self, fixture: str, requested: date):
        base = load(fixture)
        own = base.get("calendarDate") or base["hrvSummary"]["calendarDate"]
        return shift(base, (requested - date.fromisoformat(own)).days)


def b64url(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def fake_jwt() -> str:
    """Only the shape and a numeric `exp` matter to TokenProvider; the signature is never checked."""
    return f"{b64url({'alg': 'none', 'typ': 'JWT'})}.{b64url({'exp': int(time.time()) + JWT_LIFETIME_S})}.ZmFrZQ"


def make_handler(key: str, data: Dataset):
    activities = data.activities
    by_id = {a["activityId"]: a for a in activities}
    tokens: set[str] = set()

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code: int, body: bytes = b"", ctype: str = "application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if urlparse(self.path).path != MINT_PATH:
                return self.reply(404)
            if self.headers.get("Authorization") != f"ApiKey {key}":
                return self.reply(401)
            token = fake_jwt()
            tokens.add(token)
            self.reply(200, token.encode(), "text/plain")

        def do_GET(self):
            url = urlparse(self.path)
            path, query = url.path, {k: v[0] for k, v in parse_qs(url.query).items()}
            auth = self.headers.get("Authorization") or ""
            if not auth.startswith("Bearer ") or auth.removeprefix("Bearer ") not in tokens:
                return self.reply(401)
            if path == ACTIVITY_SEARCH:
                if "startDate" in query:  # ACTIVITY_RANGE: activities whose local start date is in the window
                    hits = [a for a in activities if query["startDate"] <= a["startTimeLocal"][:10] <= query["endDate"]]
                else:  # ACTIVITY_PAGE
                    start, limit = int(query.get("start", 0)), int(query.get("limit", 50))
                    hits = activities[start:start + limit]
                return self.reply(200, json.dumps(hits).encode())
            if path.startswith("/activity-service/activity/"):
                a = by_id.get(int(path.rsplit("/", 1)[1]))
                return self.reply(200, json.dumps(a).encode()) if a else self.reply(404)
            if path.startswith("/download-service/files/activity/"):
                aid = int(path.rsplit("/", 1)[1])
                name = FIT_FOR.get(by_id.get(aid, {}).get("activityType", {}).get("typeKey"))
                if not name:
                    return self.reply(404)
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, "w") as z:
                    z.writestr(f"{aid}_ACTIVITY.fit", (FIXTURES / "fit" / f"{name}.fit").read_bytes())
                return self.reply(200, buf.getvalue(), "application/zip")
            for prefix, fixture in STATIC_ROUTES:
                if path.startswith(prefix):
                    return self.reply(200, (FIXTURES / fixture).read_bytes())
            for prefix, fixture, rows_key, where in RANGED_ROUTES:
                if path.startswith(prefix):
                    start, end = (query.get("startDate", ""), query.get("endDate", "")) if where == "query" else path.split("/")[-2:]
                    return self.reply(200, json.dumps(data.window(fixture, rows_key, start, end)).encode())
            for prefix, fixture in DAY_ROUTES:
                if path.startswith(prefix):
                    try:
                        day = date.fromisoformat(query.get("date") or path.rsplit("/", 1)[1])
                    except ValueError:
                        return self.reply(400)
                    return self.reply(200, json.dumps(data.day(fixture, day)).encode())
            self.reply(404)

        def log_message(self, fmt, *args):
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}\n")

    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--key", default="fake-garmin-key", help="the only API key the fake mint accepts")
    p.add_argument("--today", type=date.fromisoformat, default=date.today(),
                   help="YYYY-MM-DD the newest data lands on; default is the host's date. Set it to the emulator's date if its clock is pinned")
    args = p.parse_args()
    data = Dataset(args.today)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(args.key, data))
    print(f"fake Garmin API on :{args.port} ({len(data.activities)} activities); today: {args.today} "
          f"(fixtures shifted {data.delta} days); key: {args.key}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
