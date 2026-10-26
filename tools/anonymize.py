"""Strip identity, devices and places from Garmin JSON so it is safe to commit as a test fixture.

As a library (used by make_fixtures.py): `Anonymizer().scrub(obj)` per file, one instance for the whole fixture set so
the same real ID always maps to the same fake one.
As a CLI, rewrites every *.json under a directory in place:
    uv run --python 3.12 --no-project python -I tools/anonymize.py app/src/test/resources/fixtures

What it changes: user/owner/device IDs, activity IDs and UUIDs, GPS coordinates (moved to a fake area), place names
(locationName, activityName, jetLag.tripName) and the profile time zone. Dates and physiological values are kept, since
the parsers and score tests depend on them; they carry no identity once the above is gone.
"""
import hashlib
import json
import sys
import uuid
from pathlib import Path

USER_KEYS = {"ownerId", "userProfilePk", "userProfilePK", "userId"}
DEVICE_KEYS = {"deviceId"}
ACTIVITY_KEYS = {"activityId", "parentId"}
UUID_KEYS = {"uuid", "activityUUID"}
DROP_KEYS = {"ownerFullName", "ownerDisplayName", "displayName", "fullName"}
DROP_PREFIX = "ownerProfileImageUrl"
LAT_KEYS = {"startLatitude", "endLatitude"}
LNG_KEYS = {"startLongitude", "endLongitude"}
FAKE_PLACES = ["Sample Town", "Test Valley", "Example Ridge", "Mock Harbour", "Demo Flats", "Placeholder Hills"]
FAKE_ORIGIN = (38.50, -98.50)  # open farmland in the central US; nothing here is a real route
FAKE_TIME_ZONE = "America/Chicago"


def _h(s):
    return int(hashlib.sha256(str(s).encode()).hexdigest(), 16)


class Anonymizer:
    def __init__(self):
        self.ids = {}

    def _num(self, kind, base, v):
        return self.ids.setdefault((kind, v), base + len([k for k in self.ids if k[0] == kind]))

    def _coord(self, v, origin):
        if not v:  # 0.0 / None placeholders mean "no GPS"
            return v
        # Same real point -> same fake point, spread over ~10 km so routes stay distinct.
        return round(origin + ((_h(v) % 20000) - 10000) / 100000.0, 6)

    def scrub(self, o, key=None):
        if isinstance(o, dict):
            out = {}
            for k, v in o.items():
                if k in DROP_KEYS or k.startswith(DROP_PREFIX):
                    continue
                out[k] = self.scrub(v, k)
            if "activityType" in o and "activityName" in out:
                kind = (o["activityType"] or {}).get("typeKey", "activity").replace("_", " ")
                out["activityName"] = f"{FAKE_PLACES[_h(o.get('activityId')) % len(FAKE_PLACES)]} {kind.title()}"
            return out
        if isinstance(o, list):
            return [self.scrub(v, key) for v in o]
        if o is None:
            return o
        if key in USER_KEYS and isinstance(o, int):
            return self._num("user", 100000000, o)
        if key in DEVICE_KEYS and isinstance(o, int):
            return self._num("device", 200000000, o)
        if key in ACTIVITY_KEYS and isinstance(o, int):
            return self._num("activity", 900000000, o)
        if key in UUID_KEYS and isinstance(o, str):
            return str(uuid.UUID(hashlib.sha256(o.encode()).hexdigest()[:32]))
        if key in LAT_KEYS and isinstance(o, float):
            return self._coord(o, FAKE_ORIGIN[0])
        if key in LNG_KEYS and isinstance(o, float):
            return self._coord(o, FAKE_ORIGIN[1])
        if key == "locationName" and isinstance(o, str):
            return FAKE_PLACES[_h(o) % len(FAKE_PLACES)]
        if key == "tripName" and isinstance(o, str):
            return "Home - Away"
        if key == "timeZone" and isinstance(o, str):
            return FAKE_TIME_ZONE
        return o


def main(root):
    a = Anonymizer()
    for p in sorted(Path(root).rglob("*.json")):
        p.write_text(json.dumps(a.scrub(json.loads(p.read_text())), separators=(",", ":")))
        print(p)


if __name__ == "__main__":
    main(sys.argv[1])
