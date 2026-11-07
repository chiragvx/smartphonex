"""Reads the phone's own telemetry log and reports control-loop health for a time window.

Samples are written by the control thread at 10 Hz, so a gap here means the loop itself
stalled, whatever the network did. Usage: gaps.py <telemetry.csv> <label> <start_ms> <end_ms>
"""
import csv
import sys

path, label, start, end = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
rows = [r for r in csv.DictReader(open(path)) if start <= int(r["t"]) <= end]
if len(rows) < 2:
    print(f"{label}: NO SAMPLES in window ({len(rows)}): the loop did not run or the log rolled over")
    sys.exit(0)
ts = [int(r["t"]) for r in rows]
gaps = [b - a for a, b in zip(ts, ts[1:])]
secs = (ts[-1] - ts[0]) / 1000
hz = [float(r["loopHz"]) for r in rows]
dt = [float(r["loopDtMaxMs"]) for r in rows]
print(
    f"{label}: {secs:.0f}s, {len(rows)} samples ({len(rows) / secs:.1f}/s, expect 10) | "
    f"largest sample gap {max(gaps)} ms | gaps >250ms: {sum(g > 250 for g in gaps)} | "
    f"loop {min(hz):.1f}-{max(hz):.1f} Hz | worst dt {max(dt):.1f} ms"
)
