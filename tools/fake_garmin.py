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
  - Activities move with the history, and the trailing days are filled from the week before too. FIT downloads are zipped from fixtures/fit/ by activity type, with the
    fixtures' own timestamps (other types get a 404, which the app records as a failed task). Stdlib only.

With --release-apk it also fakes GitHub's latest release for the in-app updater (no auth, like the real API):
GET /repos/marcusHamilton/baseline/releases/latest and the APK download it points to, with the real size and SHA-256.
Build the app with -PupdatesUrl=http://127.0.0.1:8080/repos/marcusHamilton/baseline/releases/latest (CLAUDE.md has the
whole update test).
"""
import argparse
import base64
import hashlib
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
CLONE_ID_OFFSET = 1_000_000_000  # activity ids of days filled from the week before: unique, and clear of the fixtures' ids
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
        history = {name: load(name) for _, name, _, _ in RANGED_ROUTES}
        anchor = max(row_date(r) for _, name, key, _ in RANGED_ROUTES for r in rows_of(history[name], key))
        # Whole weeks, rounded down, so the newest rows never land after today.
        self.delta = (today - anchor).days // 7 * 7
        self.last = last = anchor + timedelta(days=self.delta)  # the newest shifted history day; days after it are filled
        self.rows = {}  # fixture -> [(date, row)] in fixture order, shifted, up to today
        for _, name, key, _ in RANGED_ROUTES:
            # A list, not a dict by date: Garmin's all-null sleep placeholders share a day with the next real row.
            rows = [(row_date(r) + timedelta(days=self.delta), shift(r, self.delta)) for r in rows_of(history[name], key)]
            rows = [(d, r) for d, r in rows if d <= today]
            by_date = dict(rows)  # the real row wins over a placeholder on the same day
            # Days between the last shifted row and today repeat the same weekday of the week before.
            d = last + timedelta(days=1)
            while d <= today:
                src = by_date.get(d - timedelta(days=7))
                if src is not None:
                    rows.append((d, shift(src, 7)))
                d += timedelta(days=1)
            self.rows[name] = sorted(rows, key=lambda dr: dr[0])
        # Activities move with the history and fill the trailing days the same way. A copy needs its own id.
        shifted = [shift(a, self.delta) for a in load("activities_all.json")]
        self.activities = [a for a in shifted if a["startTimeLocal"][:10] <= today.isoformat()]
        d = last + timedelta(days=1)
        while d <= today:
            src = d - timedelta(days=7)
            for a in shifted:
                if a["startTimeLocal"][:10] == src.isoformat():
                    self.activities.append({**shift(a, 7), "activityId": a["activityId"] + CLONE_ID_OFFSET})
            d += timedelta(days=1)
        self.activities.sort(key=lambda a: a["startTimeLocal"], reverse=True)
        self.day_fixtures = {fixture: load(fixture) for _, fixture in DAY_ROUTES}

    def window(self, name: str, key, start: str, end: str):
        rows = [r for d, r in self.rows[name] if start <= d.isoformat() <= end]
        return {key: rows} if key else rows

    def day(self, fixture: str, requested: date):
        base = self.day_fixtures[fixture]
        own = base.get("calendarDate") or base["hrvSummary"]["calendarDate"]
        return shift(base, (requested - date.fromisoformat(own)).days)


def b64url(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def fake_jwt() -> str:
    """Only the shape and a numeric `exp` matter to TokenProvider; the signature is never checked."""
    return f"{b64url({'alg': 'none', 'typ': 'JWT'})}.{b64url({'exp': int(time.time()) + JWT_LIFETIME_S})}.ZmFrZQ"


RELEASE_PATH = "/repos/marcusHamilton/baseline/releases/latest"


def fake_release(apk: Path, tag: str) -> dict:
    """The fields of GitHub's release JSON the app reads; the download URL is filled in per request (it needs the Host)."""
    body = apk.read_bytes()
    return {"tag": tag, "name": f"Baseline-{tag.removeprefix('v')}.apk", "bytes": body, "sha256": hashlib.sha256(body).hexdigest()}


def make_handler(key: str, data: Dataset, release: dict | None = None):
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
            if path == RELEASE_PATH or path.startswith("/releases/download/"):
                return self.release(path)
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
                    start, end = (query.get("startDate"), query.get("endDate")) if where == "query" else path.split("/")[-2:]
                    if not (start and end):
                        return self.reply(400)  # a malformed app request should be loud, not an empty 200
                    return self.reply(200, json.dumps(data.window(fixture, rows_key, start, end)).encode())
            for prefix, fixture in DAY_ROUTES:
                if path.startswith(prefix):
                    try:
                        day = date.fromisoformat(query.get("date") or path.rsplit("/", 1)[1])
                    except ValueError:
                        return self.reply(400)
                    return self.reply(200, json.dumps(data.day(fixture, day)).encode())
            self.reply(404)

        def release(self, path: str):
            """GitHub's side of the in-app updater: public, no token, ETag'd like the real API."""
            if release is None:
                return self.reply(404)
            if path != RELEASE_PATH:
                return self.reply(200, release["bytes"], "application/vnd.android.package-archive") \
                    if path == f"/releases/download/{release['tag']}/{release['name']}" else self.reply(404)
            etag = f'"{release["sha256"][:16]}"'
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.end_headers()
                return
            host = self.headers.get("Host") or "127.0.0.1"
            doc = {
                "tag_name": release["tag"], "name": f"Baseline {release['tag'].removeprefix('v')} (fake)", "draft": False,
                "prerelease": False, "html_url": f"http://{host}/releases/tag/{release['tag']}",
                "body": "## Fake release\n- Served by tools/fake_garmin.py --release-apk",
                "assets": [{"name": release["name"], "size": len(release["bytes"]), "digest": f"sha256:{release['sha256']}",
                            "browser_download_url": f"http://{host}/releases/download/{release['tag']}/{release['name']}"}],
            }
            body = json.dumps(doc).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", etag)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}\n")

    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--key", default="fake-garmin-key", help="the only API key the fake mint accepts")
    p.add_argument("--today", type=date.fromisoformat, default=date.today(),
                   help="YYYY-MM-DD the newest data lands on; default is the host's date. Set it to the emulator's date if its clock is pinned")
    p.add_argument("--release-apk", type=Path, help="also fake GitHub's latest release, offering this APK (in-app update testing)")
    p.add_argument("--release-tag", default="v9.99", help="the tag of that release, e.g. v0.05; it must be newer than the installed build")
    args = p.parse_args()
    data = Dataset(args.today)
    release = fake_release(args.release_apk, args.release_tag) if args.release_apk else None
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(args.key, data, release))
    if release:
        print(f"fake release {release['tag']}: {release['name']} ({len(release['bytes'])} bytes, sha256 {release['sha256'][:12]}…)", flush=True)
    print(f"fake Garmin API on :{args.port} ({len(data.activities)} activities); today: {args.today} "
          f"(fixtures shifted {data.delta} days); key: {args.key}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
