#!/usr/bin/env bash
# Force an immediate data upload from a watch to MISR (dev/testing tool):
#
#   drain.sh [<ip:port>] [--wait <seconds>]
#
#   drain.sh                     # auto-discover the watch on the network
#   drain.sh 172.20.10.7:40911   # explicit ADB address (multiple watches online)
#
# The normal pipeline uploads once a day, only while charging on unmetered WiFi.
# This script fakes the charger over ADB (dumpsys battery set ac 1) so the same
# UploadWorker runs right now, streams its progress here, then restores the real
# battery state. Nothing app-side is bypassed — it is the production upload path,
# just triggered early.
set -uo pipefail

PKG="com.example.testwatch"
DRAIN_RECEIVER="$PKG/.admin.DrainNowReceiver"
SERVER="edward@misr.sauder.ubc.ca"
SSH_PORT=16800

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ok()  { printf '    OK: %s\n' "$*"; }

# ---------- args ----------
ADDR="" WAIT=300
while [ $# -gt 0 ]; do
  case "$1" in
    --wait) WAIT="${2:-}"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *:*) ADDR="$1" ;;
    *) die "unknown argument '$1' (usage: drain.sh [<ip:port>] [--wait <seconds>])" ;;
  esac
  shift
done

# ---------- find the watch ----------
command -v adb >/dev/null || die "adb not found"
if [ -z "$ADDR" ]; then
  CANDIDATES=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3}' | sort -u)
  COUNT=$(echo "$CANDIDATES" | grep -c . || true)
  [ "$COUNT" -ge 1 ] || die "no watch found — pass <ip:port> (watch: Wireless debugging screen shows it)"
  [ "$COUNT" -eq 1 ] || die "multiple watches online — pass the right <ip:port>:
$CANDIDATES"
  ADDR="$CANDIDATES"
fi
adb connect "$ADDR" >/dev/null 2>&1
W=(-s "$ADDR")
adb "${W[@]}" shell echo ok >/dev/null 2>&1 || die "cannot reach $ADDR — check Wireless debugging and same-WiFi"
echo "==> Draining watch at $ADDR"

# ---------- make the upload constraints pass right now ----------
# Real state is restored on ANY exit (success, failure, Ctrl-C).
restore() { adb "${W[@]}" shell dumpsys battery reset >/dev/null 2>&1 || true; }
trap restore EXIT

SSID=$(adb "${W[@]}" shell dumpsys wifi | sed -n 's/^mWifiInfo SSID: "\([^"]*\)".*/\1/p' | head -1)
if [ -n "$SSID" ]; then
  adb "${W[@]}" shell "cmd netpolicy set metered-network \"$SSID\" false" >/dev/null 2>&1 || true
  ok "WiFi \"$SSID\" unmetered"
else
  echo "    WARNING: could not read SSID — is the watch on WiFi? Upload needs it."
fi
adb "${W[@]}" shell dumpsys battery set ac 1 >/dev/null || die "could not fake charging state"
ok "charger faked (restored automatically when done)"

# ---------- trigger and watch the upload ----------
adb "${W[@]}" logcat -c 2>/dev/null || true
adb "${W[@]}" shell am broadcast -a com.example.testwatch.DRAIN_NOW -n "$DRAIN_RECEIVER" >/dev/null \
  || die "DRAIN_NOW broadcast failed — is the app installed?"
ok "DRAIN_NOW sent; waiting for UploadWorker (up to ${WAIT}s)"

PUTS=0 RESULT=""
DEADLINE=$(( $(date +%s) + WAIT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  sleep 3
  LOGS=$(adb "${W[@]}" logcat -d -s UploadWorker:V DrainNow:V 2>/dev/null)
  NEWPUTS=$(echo "$LOGS" | grep -c "SFTP put" || true)
  if [ "$NEWPUTS" -gt "$PUTS" ]; then
    echo "$LOGS" | grep "SFTP put" | tail -n $((NEWPUTS - PUTS)) | sed 's/.*UploadWorker: /    /'
    PUTS=$NEWPUTS
  fi
  if echo "$LOGS" | grep -q "upload pass done"; then RESULT="done"; break; fi
  if echo "$LOGS" | grep -q "upload failed"; then RESULT="failed"; break; fi
done

# ---------- report ----------
LOGS=$(adb "${W[@]}" logcat -d -s UploadWorker:V 2>/dev/null)
case "$RESULT" in
  done)
    REMAIN=$(echo "$LOGS" | grep "upload pass done" | tail -1 | sed 's/.*done; //')
    if [ "$PUTS" -eq 0 ]; then
      echo "==> DONE — nothing to drain (all data already synced; $REMAIN)"
    else
      echo "==> DONE — $PUTS file(s) uploaded ($REMAIN)"
    fi
    # Researchers must only ever see the merged per-day files, never raw parts/
    # slices — consolidate immediately instead of waiting for the 15-min cron.
    echo "==> Merging parts into researcher files on the server"
    if ssh -p "$SSH_PORT" "$SERVER" 'python3 ~/consolidate.py' | sed 's/^/    /'; then
      ok "day folders show only sensors_<pid>_continuous.json + sensors_<pid>_ondemand.json"
    else
      echo "    WARNING: consolidate over SSH failed — the server cron merges within 15 min"
    fi
    PID_DIR=$(echo "$LOGS" | grep "SFTP put" | tail -1 | sed -n 's|.*SFTP put /data1/wearables/\([^/]*\)/.*|\1|p')
    [ -n "$PID_DIR" ] && echo "    verify: ssh -p $SSH_PORT $SERVER 'ls -laR /data1/wearables/$PID_DIR/'"
    ;;
  failed)
    echo "$LOGS" | grep "upload failed" | tail -1 | sed 's/.*UploadWorker: /    /'
    die "upload FAILED — full log: adb -s $ADDR logcat -d -s UploadWorker:V"
    ;;
  *)
    die "timed out after ${WAIT}s with no upload result — check: adb -s $ADDR logcat -d -s UploadWorker:V DrainNow:V (WorkManager may still be waiting on constraints; is WiFi up?)"
    ;;
esac
