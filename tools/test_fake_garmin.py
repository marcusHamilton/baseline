"""Tests for the date shifting in fake_garmin.py.

Run:  ~/.local/bin/uv run --python 3.12 --no-project python -I tools/test_fake_garmin.py
"""
import importlib.util
import json
import threading
import unittest
import urllib.error
import urllib.request
from datetime import date, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

_spec = importlib.util.spec_from_file_location("fake_garmin", Path(__file__).resolve().parent / "fake_garmin.py")
fg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fg)

KEY = "test-key"


class Server:
    def __init__(self, today: date):
        self.data = fg.Dataset(today)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), fg.make_handler(KEY, self.data))
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        mint = urllib.request.Request(self.base + fg.MINT_PATH, method="POST", headers={"Authorization": f"ApiKey {KEY}"})
        self.token = urllib.request.urlopen(mint).read().decode()

    def get(self, path: str):
        req = urllib.request.Request(self.base + path, headers={"Authorization": f"Bearer {self.token}"})
        return json.loads(urllib.request.urlopen(req).read())

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class FakeGarminDates(unittest.TestCase):
    def check_today(self, today: date):
        s = Server(today)
        self.addCleanup(s.close)
        d = today.isoformat()
        week = f"{(today - timedelta(days=6)).isoformat()}"
        self.assertEqual(s.data.delta % 7, 0)
        # Every history endpoint ends on today, with no hole in the trailing week.
        steps = s.get(f"/usersummary-service/stats/steps/daily/{week}/{d}")
        self.assertEqual([r["calendarDate"] for r in steps][-1], d)
        summaries = s.get(f"/usersummary-service/v2/dailysummaries?startDate={week}&endDate={d}")["data"]
        self.assertEqual(summaries[-1]["calendarDate"], d)
        sleep = s.get(f"/sleep-service/sleep/dailySleepSummariesByDate?startDate={week}&endDate={d}")
        self.assertEqual(sleep[-1]["calendarDate"], d)
        battery = s.get(f"/wellness-service/wellness/bodyBattery/reports/daily?startDate={week}&endDate={d}&chunks=288")
        self.assertEqual(battery[-1]["date"], d)
        hrv = s.get(f"/hrv-service/hrv/daily/{week}/{d}")["hrvSummaries"]
        # Only <= today: the HRV fixture has gaps (no reading on many nights), so today itself may be missing.
        self.assertLessEqual(hrv[-1]["calendarDate"], d)
        # The window is respected on both ends.
        self.assertTrue(all(week <= r["calendarDate"] <= d for r in steps))
        # Nothing is dated after today.
        everything = s.get("/usersummary-service/stats/stress/daily/2020-01-01/2099-01-01")
        self.assertEqual(max(r["calendarDate"] for r in everything), d)

    def test_today_real_date(self):
        self.check_today(date(2026, 10, 26))

    def test_today_in_the_past_and_future(self):
        self.check_today(date(2026, 3, 4))
        self.check_today(date(2027, 1, 1))

    def test_weekdays_preserved(self):
        s = Server(date(2027, 5, 19))
        self.addCleanup(s.close)
        original = {r["calendarDate"]: r for r in fg.load("steps_history.json")}
        shifted = s.get("/usersummary-service/stats/steps/daily/2027-05-01/2027-05-19")
        for r in shifted:
            src = (date.fromisoformat(r["calendarDate"]) - timedelta(days=s.data.delta)).isoformat()
            if src in original:
                self.assertEqual(r["totalSteps"], original[src]["totalSteps"])
                self.assertEqual(date.fromisoformat(src).weekday(), date.fromisoformat(r["calendarDate"]).weekday())

    def test_per_day_endpoints_carry_the_requested_date(self):
        s = Server(date(2027, 2, 9))
        self.addCleanup(s.close)
        for d in ("2027-02-09", "2027-02-08"):
            self.assertEqual(s.get(f"/sleep-service/sleep/dailySleep?date={d}&nonSleepBufferMinutes=60")["calendarDate"], d)
            self.assertEqual(s.get(f"/wellness-service/wellness/dailyHeartRate?date={d}")["calendarDate"], d)
            self.assertEqual(s.get(f"/hrv-service/hrv/{d}")["hrvSummary"]["calendarDate"], d)
            self.assertEqual(s.get(f"/wellness-service/wellness/daily/spo2acclimation/{d}")["calendarDate"], d)

    def test_epoch_fields_move_with_the_dates(self):
        s = Server(date(2027, 2, 9))
        self.addCleanup(s.close)
        day = s.get("/sleep-service/sleep/dailySleep?date=2027-02-09")
        start = date.fromtimestamp(day["sleepStartTimestampGMT"] / 1000)
        self.assertLessEqual(abs((start - date(2027, 2, 9)).days), 1)

    def test_activities_move_stay_before_today_and_keep_unique_ids(self):
        # Every offset of (today - anchor) % 7, so the filled trailing days are covered too.
        for today in (date(2027, 2, 9) + timedelta(days=i) for i in range(7)):
            s = Server(today)
            self.addCleanup(s.close)
            acts = s.get(f"{fg.ACTIVITY_SEARCH}?start=0&limit=500")
            self.assertLessEqual(max(a["startTimeLocal"][:10] for a in acts), today.isoformat())
            ids = [a["activityId"] for a in acts]
            self.assertEqual(len(ids), len(set(ids)))
            window = s.get(f"{fg.ACTIVITY_SEARCH}?startDate={today - timedelta(days=6)}&endDate={today}&limit=50&start=0")
            self.assertTrue(all(str(today - timedelta(days=6)) <= a["startTimeLocal"][:10] <= str(today) for a in window))
            self.assertEqual(s.get(f"/activity-service/activity/{ids[0]}")["activityId"], ids[0])

    def test_trailing_days_repeat_the_previous_weeks_activities(self):
        today = date(2027, 2, 9)
        s = Server(today)
        self.addCleanup(s.close)
        days = {}
        for a in s.data.activities:
            days.setdefault(a["startTimeLocal"][:10], []).append(a["activityName"])
        for i in range(1, (today - s.data.last).days + 1):
            d = s.data.last + timedelta(days=i)
            self.assertEqual(days.get(d.isoformat()), days.get((d - timedelta(days=7)).isoformat()))

    def test_sleep_placeholders_are_kept(self):
        # today = anchor + 2 weeks: nothing to fill, so the response must have exactly the fixture's rows.
        s = Server(date(2026, 11, 6))
        self.addCleanup(s.close)
        served = s.get("/sleep-service/sleep/dailySleepSummariesByDate?startDate=2020-01-01&endDate=2099-01-01")
        fixture = fg.load("sleep_summaries_history.json")
        self.assertEqual(len(served), len(fixture))
        self.assertEqual(sum(r["id"] is None for r in served), sum(r["id"] is None for r in fixture))

    def test_ranged_request_without_a_window_is_rejected(self):
        s = Server(date(2026, 10, 26))
        self.addCleanup(s.close)
        with self.assertRaises(urllib.error.HTTPError) as e:
            s.get("/usersummary-service/v2/dailysummaries")
        self.assertEqual(e.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
