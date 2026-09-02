#!/usr/bin/env python3
"""Merge watch upload parts into the two researcher-facing files per day folder.

Watches upload small resumable slices into /data1/wearables/<pid>/<YYYY-MM-DD>/parts/.
This script (cron, every 15 min on MISR) folds them into:

    sensors_<pid>_continuous.json   hr + ppg + accel + skin_temp for the whole day
    sensors_<pid>_ondemand.json     the day's measurement round (a newer round replaces it)

and deletes the consumed parts, so a day folder normally shows just the two files.
Writes are atomic (tmp + rename); a part that fails to parse is an in-flight upload and
is left for the next run.
"""
import glob
import json
import os
import tempfile

BASE = "/data1/wearables"
CONTINUOUS_SENSORS = {"ppg", "accel", "skin_temp"}


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def pretty(obj):
    """Researcher-readable JSON: scalars up top, then one record per line inside each
    list — scannable and greppable without the file exploding into millions of lines."""
    scalar_parts = []
    list_parts = []
    for k, v in obj.items():
        if isinstance(v, list):
            if v:
                body = ",\n".join("    " + json.dumps(r) for r in v)
                list_parts.append('  %s: [\n%s\n  ]' % (json.dumps(k), body))
            else:
                list_parts.append('  %s: []' % json.dumps(k))
        else:
            scalar_parts.append('  %s: %s' % (json.dumps(k), json.dumps(v)))
    return "{\n" + ",\n".join(scalar_parts + list_parts) + "\n}\n"


def write_atomic(path, obj):
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w") as f:
        f.write(pretty(obj))
        f.flush()
        os.fsync(f.fileno())
    os.rename(tmp, path)


def day_dirs():
    for pid in sorted(os.listdir(BASE)):
        pdir = os.path.join(BASE, pid)
        if not os.path.isdir(pdir) or pid.startswith("old"):
            continue
        for day in sorted(os.listdir(pdir)):
            ddir = os.path.join(pdir, day)
            if os.path.isdir(ddir) and len(day) == 10:
                yield pid, day, ddir


def consolidate(pid, day, ddir):
    parts_dir = os.path.join(ddir, "parts")
    if not os.path.isdir(parts_dir):
        return
    parts = sorted(glob.glob(os.path.join(parts_dir, "*.json")))
    if not parts:
        return

    cont_path = os.path.join(ddir, "sensors_%s_continuous.json" % pid)
    od_path = os.path.join(ddir, "sensors_%s_ondemand.json" % pid)
    cont = load(cont_path) or {
        "participant_id": pid, "date": day, "is_test": True,
        "heart_rate": [], "batches": [],
    }
    new_od_batches = []
    consumed = []

    for p in parts:
        data = load(p)
        if data is None:  # in-flight or truncated upload; retry next run
            continue
        for s in data.get("samples", []):  # hr_* parts
            cont["heart_rate"].append(
                {"time": s.get("time"), "bpm": s.get("bpm"), "status": s.get("status")})
        for b in data.get("batches", []):  # sensors_* parts
            if b.get("sensor") in CONTINUOUS_SENSORS:
                cont["batches"].append(b)
            else:
                new_od_batches.append(b)
        consumed.append(p)

    if not consumed:
        return
    write_atomic(cont_path, cont)
    if new_od_batches:
        # A newly arrived round replaces the day's on-demand file: the watch only ever
        # ships the surviving round of a day, so last-arrived == latest.
        write_atomic(od_path, {
            "participant_id": pid, "date": day, "is_test": True,
            "round_completed": new_od_batches[-1].get("time"),
            "batches": new_od_batches,
        })
    for p in consumed:
        os.remove(p)
    try:
        os.rmdir(parts_dir)  # tidy when empty; harmless if new parts just landed
    except OSError:
        pass
    print("consolidated %d parts -> %s/%s" % (len(consumed), pid, day))


def main():
    for pid, day, ddir in day_dirs():
        consolidate(pid, day, ddir)


if __name__ == "__main__":
    main()
