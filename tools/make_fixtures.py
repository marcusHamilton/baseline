"""Turn tools/out/sample/ (from fetch_sample.py) into anonymized JSON fixtures (see anonymize.py).

FIT files are not handled here: run each real .fit through anonymize_fit.py into fixtures/fit/.

Run: ~/.local/bin/uv run --python 3.12 --no-project python -I tools/make_fixtures.py tools/out/sample
Writes app/src/test/resources/fixtures/.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from anonymize import Anonymizer

SRC = Path(sys.argv[1])
DEST = Path(__file__).resolve().parent.parent / "app/src/test/resources/fixtures"
ANON = Anonymizer()


def load(path):
    text = path.read_text()
    return json.loads(text) if text.strip() else None


def chunks(prefix):
    return [load(p) for p in sorted(SRC.glob(f"range_{prefix}_*.json"))]


def write(name, obj):
    (DEST / name).write_text(json.dumps(ANON.scrub(obj), separators=(",", ":")))


def flat(prefix):
    return [item for c in chunks(prefix) if c for item in c]


def main():
    DEST.mkdir(parents=True, exist_ok=True)  # leaves fit/ (generated, not derived from real data) alone

    write("dailysummaries_history.json",
          {"data": [i for c in chunks("dailysummaries") if c for i in c.get("data", [])]})
    write("hrv_history.json",
          {"hrvSummaries": [i for c in chunks("hrv") if c for i in c.get("hrvSummaries", [])]})
    write("sleep_summaries_history.json", flat("sleep_summaries"))
    write("bodybattery_history.json", flat("bodybattery"))
    write("stress_history.json", flat("stress"))
    write("steps_history.json", flat("steps"))

    # From `fetch_sample.py --bb-chunks`: the single 2026-10-23 item (chunks=288).
    write("bodybattery_chunks_day.json", load(SRC / "bodybattery_chunks_day.json")[0])

    for name in ["sleep_day", "hrv_day", "heartrate_day", "spo2_day", "hr_zones", "profile", "activities_all"]:
        write(f"{name}.json", load(SRC / f"{name}.json"))

    for p in sorted(DEST.rglob("*")):
        if p.is_file():
            print(f"{p.relative_to(DEST)}  {p.stat().st_size}")


if __name__ == "__main__":
    main()
