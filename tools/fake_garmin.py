"""A fake Garmin Personal API that serves the scrubbed fixtures, for emulator testing without a real key.

Run:  ~/.local/bin/uv run --python 3.12 --no-project python -I tools/fake_garmin.py [--port 8080] [--key fake-garmin-key]
Build: ./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080   (after: adb reverse tcp:8080 tcp:8080)
Then paste the fake key ("fake-garmin-key" by default) in onboarding or Settings. Any other key gets a 401, so the
invalid-key path can be tested too. A build made with -PgarminBaseUrl allows cleartext and is named "…-fake": never ship one.

Every endpoint in GarminEndpoint.kt is answered from app/src/test/resources/fixtures/. Ranged endpoints return the
same history fixture whatever range is asked for, and per-day endpoints return the same day for every date, so a
backfill finishes quickly with the fixtures' own dates. FIT downloads are zipped from fixtures/fit/ by activity type
(other types get a 404, which the app records as a failed task). Stdlib only.
"""
import argparse
import base64
import io
import json
import sys
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

FIXTURES = Path(__file__).resolve().parent.parent / "app" / "src" / "test" / "resources" / "fixtures"
MINT_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
ACTIVITY_SEARCH = "/activitylist-service/activities/search/activities"
# Path prefix -> fixture, first match wins (so the more specific prefixes come first).
JSON_ROUTES = [
    ("/userprofile-service/userprofile/settings", "profile.json"),
    ("/biometric-service/heartRateZones", "hr_zones.json"),
    ("/usersummary-service/v2/dailysummaries", "dailysummaries_history.json"),
    ("/hrv-service/hrv/daily/", "hrv_history.json"),
    ("/hrv-service/hrv/", "hrv_day.json"),
    ("/sleep-service/sleep/dailySleepSummariesByDate", "sleep_summaries_history.json"),
    ("/sleep-service/sleep/dailySleep", "sleep_day.json"),
    ("/wellness-service/wellness/bodyBattery/reports/daily", "bodybattery_history.json"),
    ("/usersummary-service/stats/stress/daily/", "stress_history.json"),
    ("/usersummary-service/stats/steps/daily/", "steps_history.json"),
    ("/wellness-service/wellness/dailyHeartRate", "heartrate_day.json"),
    ("/wellness-service/wellness/daily/spo2acclimation/", "spo2_day.json"),
]
# Activity typeKey -> fixtures/fit/<name>.fit
FIT_FOR = {"running": "run", "road_biking": "road_ride", "virtual_ride": "virtual_ride",
           "indoor_cycling": "indoor_ride", "lap_swimming": "swim", "strength_training": "strength"}
JWT_LIFETIME_S = 24 * 3600


def b64url(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def fake_jwt() -> str:
    """Only the shape and a numeric `exp` matter to TokenProvider; the signature is never checked."""
    return f"{b64url({'alg': 'none', 'typ': 'JWT'})}.{b64url({'exp': int(time.time()) + JWT_LIFETIME_S})}.ZmFrZQ"


def make_handler(key: str, activities: list):
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
            for prefix, fixture in JSON_ROUTES:
                if path.startswith(prefix):
                    return self.reply(200, (FIXTURES / fixture).read_bytes())
            self.reply(404)

        def log_message(self, fmt, *args):
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}\n")

    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--key", default="fake-garmin-key", help="the only API key the fake mint accepts")
    args = p.parse_args()
    activities = json.loads((FIXTURES / "activities_all.json").read_text())
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(args.key, activities))
    print(f"fake Garmin API on :{args.port} ({len(activities)} activities); key: {args.key}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
