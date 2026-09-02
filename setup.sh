#!/usr/bin/env bash
# One-command watch provisioning: everything after the watch is physically prepared.
#
#   setup.sh <PARTICIPANT_ID> [<watch-ip:port>] [--pair] [--no-build] [--yes]
#
#   setup.sh 2A                      # auto-discover the watch on the network
#   setup.sh 2A 172.20.10.7:40911    # explicit ADB address
#   setup.sh 2A --pair               # brand-new watch: prompts for the pairing code
#
# Prerequisites on the watch (2 minutes, by hand — see docs/watch-provisioning.md):
#   1. Paired via Galaxy Wearable, NO accounts signed in (skip Samsung/Google;
#      if one snuck on: watch Settings > Account and backup > sign out)
#   2. On WiFi. Developer mode + ADB debugging + Wireless debugging ON
#   3. Samsung Health dev mode ON (Samsung Health > Settings > About > tap version 10x)
#
# What this script does: connect ADB -> verify no accounts -> build+install APK ->
# grant permissions -> kiosk device-owner -> set participant ID -> register the
# watch's SSH key on MISR -> mark WiFi unmetered -> launch pinned -> verify
# tracking is recording and lock task is LOCKED.
set -uo pipefail

# Resolve through symlinks (the global `watch-setup` command is a symlink to this file).
SRC="${BASH_SOURCE[0]}"
while [ -L "$SRC" ]; do
  DIR="$(cd -P "$(dirname "$SRC")" && pwd)"
  SRC="$(readlink "$SRC")"
  [[ "$SRC" != /* ]] && SRC="$DIR/$SRC"
done
REPO="$(cd -P "$(dirname "$SRC")" && pwd)"
APK="$REPO/watch/build/outputs/apk/debug/watch-debug.apk"
SERVER="edward@misr.sauder.ubc.ca"
SSH_PORT=16800
PKG="com.example.testwatch"
KIOSK_RECEIVER="$PKG/com.example.testwatch.kiosk.KioskControlReceiver"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '    OK: %s\n' "$*"; }
die()  { printf '\n\033[1mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- args ----------
PID="" ADDR="" DO_PAIR=0 NO_BUILD=0 ASSUME_YES=0
for a in "$@"; do
  case "$a" in
    --pair) DO_PAIR=1 ;;
    --no-build) NO_BUILD=1 ;;
    --yes) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *:*) ADDR="$a" ;;
    *) PID="$a" ;;
  esac
done
[ -n "$PID" ] || die "usage: setup.sh <PARTICIPANT_ID> [<ip:port>] [--pair] [--no-build] [--yes]"
[[ "$PID" =~ ^[0-9]+[A-Z]$ ]] || echo "WARNING: '$PID' does not match the <watch#><cycle letter> pattern (e.g. 2A)"

command -v adb >/dev/null || die "adb not found — install Android platform-tools"

# ---------- pairing (brand-new watch) ----------
if [ "$DO_PAIR" = 1 ]; then
  step "ADB pairing (watch: Developer options > Wireless debugging > Pair new device)"
  read -r -p "    Pairing address shown on watch (ip:port): " PAIR_ADDR
  adb pair "$PAIR_ADDR" || die "pairing failed — re-open 'Pair new device' on the watch and retry"
fi

# ---------- find the watch ----------
step "Locating watch"
if [ -z "$ADDR" ]; then
  CANDIDATES=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3}' | sort -u)
  COUNT=$(echo "$CANDIDATES" | grep -c . || true)
  [ "$COUNT" -ge 1 ] || die "no watch found on the network — pass <ip:port> (watch: Wireless debugging screen shows it)"
  [ "$COUNT" -eq 1 ] || die "multiple watches on the network — pass the right <ip:port> explicitly:
$CANDIDATES"
  ADDR="$CANDIDATES"
  echo "    auto-discovered: $ADDR"
fi
adb connect "$ADDR" >/dev/null 2>&1
W=(-s "$ADDR")
adb "${W[@]}" shell echo ok >/dev/null 2>&1 || die "cannot talk to $ADDR — check Wireless debugging and same-WiFi"
MODEL=$(adb "${W[@]}" shell getprop ro.product.model | tr -d '\r')
ok "connected to $MODEL at $ADDR"

# ---------- preflight: accounts ----------
step "Checking for accounts (kiosk requires zero)"
ACCOUNTS=$(adb "${W[@]}" shell dumpsys account | grep -c "Account {" || true)
if [ "${ACCOUNTS:-0}" -ne 0 ]; then
  adb "${W[@]}" shell dumpsys account | grep "Account {" | head -3
  die "account(s) on the watch. Sign out: watch Settings > Account and backup > Samsung account > Sign out, then re-run"
fi
ok "no accounts"

# ---------- preflight: Samsung Health dev mode ----------
if [ "$ASSUME_YES" != 1 ]; then
  step "Samsung Health developer mode"
  echo "    Without it the sensors record NOTHING (silently)."
  echo "    Watch: Samsung Health > Settings > About Samsung Health > tap version ~10x"
  read -r -p "    Is it enabled on this watch? [y/N] " R
  [[ "$R" =~ ^[Yy] ]] || die "enable Samsung Health dev mode first, then re-run"
fi

# ---------- build + install ----------
if [ "$NO_BUILD" != 1 ]; then
  step "Building watch APK"
  (cd "$REPO" && ./gradlew -q :watch:assembleDebug) || die "gradle build failed"
fi
[ -f "$APK" ] || die "APK not found at $APK (run without --no-build)"
step "Installing app"
adb "${W[@]}" install -r "$APK" >/dev/null || die "install failed"
ok "installed"

step "Granting permissions"
for P in android.permission.BODY_SENSORS android.permission.ACTIVITY_RECOGNITION \
         android.permission.POST_NOTIFICATIONS android.permission.health.READ_HEART_RATE \
         android.permission.health.READ_OXYGEN_SATURATION android.permission.health.READ_SKIN_TEMPERATURE \
         com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA; do
  adb "${W[@]}" shell pm grant "$PKG" "$P" >/dev/null 2>&1 || true
done
adb "${W[@]}" shell dumpsys package "$PKG" | grep -q "android.permission.BODY_SENSORS: granted=true" \
  || die "BODY_SENSORS grant failed"
ok "permissions granted"

# ---------- kiosk device owner ----------
step "Kiosk: setting device owner"
DPM=$(adb "${W[@]}" shell dpm set-device-owner "$PKG/com.example.testwatch.kiosk.KioskDeviceAdminReceiver" 2>&1 || true)
if echo "$DPM" | grep -q "Success"; then ok "device owner set"
elif echo "$DPM" | grep -qi "already.*device owner\|already set"; then ok "already device owner"
elif echo "$DPM" | grep -qi "accounts"; then die "OS refused: accounts appeared on the watch — sign out and re-run"
else die "dpm failed: $(echo "$DPM" | head -2)"
fi

# ---------- participant ID ----------
step "Setting participant ID: $PID"
adb "${W[@]}" shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
  -n "$PKG/.admin.SetParticipantIdReceiver" --es participant_id "$PID" >/dev/null || die "broadcast failed"
ok "participant ID set"

# ---------- SSH key registration ----------
step "Registering watch SSH key on MISR"
KEYLINE=$(adb "${W[@]}" shell am broadcast -a com.example.testwatch.GET_PUBKEY \
  -n "$PKG/.admin.GetPublicKeyReceiver" 2>/dev/null | sed -n 's/.*data="\(.*\)".*/\1/p')
[ -n "$KEYLINE" ] || die "could not read the watch public key (GET_PUBKEY broadcast returned nothing)"
KEYB64=$(echo "$KEYLINE" | awk '{print $2}')
ssh -o BatchMode=yes -p "$SSH_PORT" "$SERVER" \
  "grep -qF '$KEYB64' ~/.ssh/authorized_keys 2>/dev/null && echo DUP || { echo '$KEYLINE' >> ~/.ssh/authorized_keys && echo ADDED; }" \
  | grep -qE "DUP|ADDED" || die "could not update authorized_keys on MISR (passwordless SSH required from this machine)"
ok "key registered (comment: $(echo "$KEYLINE" | awk '{print $3}'))"

# ---------- WiFi metered override ----------
step "Marking watch WiFi as unmetered (uploads require unmetered)"
SSID=$(adb "${W[@]}" shell dumpsys wifi | sed -n 's/^mWifiInfo SSID: "\([^"]*\)".*/\1/p' | head -1)
if [ -n "$SSID" ]; then
  adb "${W[@]}" shell "cmd netpolicy set metered-network \"$SSID\" false" >/dev/null 2>&1 || true
  ok "network \"$SSID\" forced unmetered"
else
  echo "    WARNING: could not read SSID — if uploads never run, the network may be flagged metered"
fi

# ---------- launch, pin, verify ----------
step "Launching app and arming kiosk"
adb "${W[@]}" logcat -c 2>/dev/null || true
adb "${W[@]}" shell am broadcast -a com.example.testwatch.KIOSK_ENABLE -n "$KIOSK_RECEIVER" >/dev/null
adb "${W[@]}" shell input keyevent KEYCODE_WAKEUP
adb "${W[@]}" shell am start "$PKG/.presentation.MainActivity" >/dev/null 2>&1
sleep 8

LOCK=$(adb "${W[@]}" shell "dumpsys activity 2>/dev/null | grep -m1 mLockTaskModeState" 2>/dev/null | tr -d '\r ')
LOGS=$(adb "${W[@]}" logcat -d -s ConnectionManager:V SensorEngine:V HrTrackingService:E 2>/dev/null)
echo "$LOGS" | grep -q "Connected" || { adb "${W[@]}" logcat -d | grep -qi "SDK_POLICY" \
  && die "Samsung SDK refused (SDK_POLICY_ERROR) — Samsung Health dev mode is NOT on; enable it and re-run" \
  || echo "    WARNING: no SDK connection seen yet — check: adb ${W[*]} logcat -s ConnectionManager:V"; }
echo "$LOGS" | grep -q "continuous started" && ok "tracking: continuous sensors recording"
case "$LOCK" in
  *LOCKED*) ok "kiosk: lock task LOCKED" ;;
  *) echo "    WARNING: lock task state is '$LOCK' — screen may be off; check the watch" ;;
esac

# ---------- summary ----------
step "DONE — watch provisioned as $PID"
cat <<EOF
    drain now : adb -s $ADDR shell am broadcast -a com.example.testwatch.DRAIN_NOW -n $PKG/.admin.DrainNowReceiver
    kiosk     : watch-kiosk on|off|status $ADDR
    server    : ssh -p $SSH_PORT $SERVER 'ls -laR /data1/wearables/$PID/'
EOF
