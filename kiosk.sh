#!/usr/bin/env bash
# Remote kiosk control over ADB-WiFi (no touching the watch):
#
#   watch-kiosk on      [<ip:port>]   re-arm the kiosk and pin the study app
#   watch-kiosk off     [<ip:port>]   passcode-exit the kiosk (screen unpins)
#   watch-kiosk status  [<ip:port>]   deviceOwner / enabled / lockTask state
#
# With no <ip:port>, auto-discovers when exactly one watch is on the network.
set -uo pipefail

PKG="com.example.testwatch"
RECEIVER="$PKG/com.example.testwatch.kiosk.KioskControlReceiver"
PASSCODE='MIS123!'

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

CMD="${1:-}"; ADDR="${2:-}"
[[ "$CMD" =~ ^(on|off|status)$ ]] || die "usage: watch-kiosk on|off|status [<ip:port>]"

command -v adb >/dev/null || die "adb not found"
if [ -z "$ADDR" ]; then
  CANDIDATES=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3}' | sort -u)
  COUNT=$(echo "$CANDIDATES" | grep -c . || true)
  [ "$COUNT" -ge 1 ] || die "no watch found — pass <ip:port>"
  [ "$COUNT" -eq 1 ] || die "multiple watches online — pass the right <ip:port>:
$CANDIDATES"
  ADDR="$CANDIDATES"
fi
adb connect "$ADDR" >/dev/null 2>&1
W=(-s "$ADDR")
adb "${W[@]}" shell echo ok >/dev/null 2>&1 || die "cannot reach $ADDR"

lock_state() {
  adb "${W[@]}" shell "dumpsys activity 2>/dev/null | grep -m1 mLockTaskModeState" 2>/dev/null | tr -d '\r '
}

case "$CMD" in
  on)
    adb "${W[@]}" shell am broadcast -a com.example.testwatch.KIOSK_ENABLE -n "$RECEIVER" >/dev/null
    adb "${W[@]}" shell input keyevent KEYCODE_WAKEUP
    adb "${W[@]}" shell am start "$PKG/.presentation.MainActivity" >/dev/null 2>&1
    sleep 4
    case "$(lock_state)" in
      *LOCKED*) echo "kiosk ON — lock task LOCKED" ;;
      *) echo "WARNING: broadcast sent but lock state is '$(lock_state)' — is the app installed and the screen on?" ;;
    esac
    ;;
  off)
    adb "${W[@]}" shell am broadcast -a com.example.testwatch.KIOSK_EXIT -n "$RECEIVER" --es passcode "$PASSCODE" >/dev/null
    sleep 3
    case "$(lock_state)" in
      *NONE*) echo "kiosk OFF — watch unpinned (re-arm with: watch-kiosk on $ADDR)" ;;
      *) echo "WARNING: lock state is '$(lock_state)' — check: watch-kiosk status $ADDR" ;;
    esac
    ;;
  status)
    adb "${W[@]}" logcat -c 2>/dev/null || true
    adb "${W[@]}" shell am broadcast -a com.example.testwatch.KIOSK_STATUS -n "$RECEIVER" >/dev/null
    sleep 2
    LINE=$(adb "${W[@]}" logcat -d -s KioskControl:V 2>/dev/null | tail -1 | sed 's/.*KioskControl: //')
    echo "${LINE:-<no response — is the app installed?>}"
    echo "os lock task: $(lock_state)"
    ;;
esac
