#!/usr/bin/env python3
"""Merge watch upload parts into the two researcher-facing files per day folder.

Watches upload small resumable slices into
/data1/wearables/<study>/<pid>/<YYYY-MM-DD>/parts/ (study = "pilot" for now).
This script (cron, every 15 min on MISR) folds them into:

    sensors_<pid>_continuous.json   heart_rate / ppg / accel / skin_temp sections
    sensors_<pid>_ondemand.json     ecg / spo2 / bia / mf_bia sections (newest round wins)

and deletes the consumed parts, so a day folder normally shows just the two files.

File format is built for scrolling humans: one section per sensor, one reading per
line, identical keys on every line of a section. The SDK's raw epoch `ts` field is
dropped; each reading carries its batch's human-readable `time`. Writes are atomic
(tmp + rename); a part that fails to parse is an in-flight upload, left for next run.
"""
import glob
import json
import os
import re
import tempfile

BASE = "/data1/wearables"
DEFAULT_STUDY = "pilot"
PID_RE = re.compile(r"^\d+[A-Z]$")
CONTINUOUS_SENSORS = ["ppg", "accel", "skin_temp"]
ONDEMAND_SENSORS = ["ecg", "spo2", "bia", "mf_bia"]


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def pretty(obj):
    """Scalars up top, then one reading per line inside each list section."""
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


def readings(batch):
    """Flatten a batch into per-reading rows: batch time first, then the values,
    minus the raw epoch ts."""
    t = batch.get("time")
    for p in batch.get("points", []):
        row = {"time": t}
        for k, v in p.items():
            if k != "ts":
                row[k] = v
        yield row


def ordered(doc, sensor_order):
    """Stable section order regardless of arrival order."""
    out = {}
    for k in ("participant_id", "date", "is_test", "round_completed"):
        if k in doc:
            out[k] = doc[k]
    if "heart_rate" in doc:
        out["heart_rate"] = doc["heart_rate"]
    for s in sensor_order:
        if s in doc:
            out[s] = doc[s]
    return out


def migrate(doc):
    """Fold any old-format 'batches' array into per-sensor sections."""
    for b in doc.pop("batches", []):
        doc.setdefault(b.get("sensor", "unknown"), []).extend(readings(b))
    return doc


def migrate_stray_pids():
    """A watch still on a pre-study APK uploads flat to BASE/<pid>/ — fold those
    into the default study folder so researchers only ever browse BASE/<study>/."""
    for name in sorted(os.listdir(BASE)):
        src = os.path.join(BASE, name)
        if not (os.path.isdir(src) and PID_RE.match(name)):
            continue
        dst_root = os.path.join(BASE, DEFAULT_STUDY, name)
        os.makedirs(dst_root, exist_ok=True)
        for day in sorted(os.listdir(src)):
            s = os.path.join(src, day)
            d = os.path.join(dst_root, day)
            if not os.path.isdir(s):
                continue
            if not os.path.exists(d):
                os.rename(s, d)
            else:  # same day exists in both trees: move the slices, merge next pass
                os.makedirs(os.path.join(d, "parts"), exist_ok=True)
                for p in glob.glob(os.path.join(s, "parts", "*.json")):
                    os.rename(p, os.path.join(d, "parts", os.path.basename(p)))
                for leftover in (os.path.join(s, "parts"), s):
                    try:
                        os.rmdir(leftover)
                    except OSError:
                        print("stray %s not empty after merge; left in place" % leftover)
        try:
            os.rmdir(src)
            print("migrated stray %s -> %s/%s" % (name, DEFAULT_STUDY, name))
        except OSError:
            pass


def day_dirs():
    for study in sorted(os.listdir(BASE)):
        sdir = os.path.join(BASE, study)
        if not os.path.isdir(sdir) or study.startswith("old"):
            continue
        for pid in sorted(os.listdir(sdir)):
            pdir = os.path.join(sdir, pid)
            if not os.path.isdir(pdir):
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
    cont = migrate(load(cont_path) or {
        "participant_id": pid, "date": day, "is_test": True, "heart_rate": [],
    })
    new_od = {}
    round_time = None
    consumed = []

    for p in parts:
        data = load(p)
        if data is None:  # in-flight or truncated upload; retry next run
            continue
        for s in data.get("samples", []):  # hr_* parts
            cont["heart_rate"].append(
                {"time": s.get("time"), "bpm": s.get("bpm"), "status": s.get("status")})
        for b in data.get("batches", []):  # sensors_* parts
            sensor = b.get("sensor", "unknown")
            if sensor in CONTINUOUS_SENSORS:
                cont.setdefault(sensor, []).extend(readings(b))
            else:
                new_od.setdefault(sensor, []).extend(readings(b))
                round_time = b.get("time") or round_time
        consumed.append(p)

    if not consumed:
        return
    write_atomic(cont_path, ordered(cont, CONTINUOUS_SENSORS))
    if new_od:
        # A newly arrived round replaces the day's on-demand file: the watch only ever
        # ships the surviving round of a day, so last-arrived == latest.
        od = {"participant_id": pid, "date": day, "is_test": True,
              "round_completed": round_time}
        od.update(new_od)
        write_atomic(od_path, ordered(od, ONDEMAND_SENSORS))
    for p in consumed:
        os.remove(p)
    try:
        os.rmdir(parts_dir)  # tidy when empty; harmless if new parts just landed
    except OSError:
        pass
    print("consolidated %d parts -> %s/%s" % (len(consumed), pid, day))


def main():
    migrate_stray_pids()
    for pid, day, ddir in day_dirs():
        consolidate(pid, day, ddir)


if __name__ == "__main__":
    main()
