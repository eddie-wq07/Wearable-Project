#!/bin/bash
# Bring up the full dev pipeline in one shot:
#   1. Connect ADB to the watch + phone over WiFi (whatever is discoverable)
#   2. Launch the watch app if the watch is reachable
#   3. Open VS Code on the server upload folder (misr)
#   4. Open a Terminal window with a live feed of files landing on the server
#
# Prereqs: Mac + phone + watch on the same hotspot; SSH key auth to misr
# (already set up); watch Wireless debugging ON if you want ADB to it.

WATCH_APP="com.example.testwatch"
SERVER_DIR="/data1/wearables"

echo "── Wearable pipeline up ──"

# 1. Discover and connect ADB-WiFi devices
adb start-server >/dev/null 2>&1
adb mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $3}' | while read -r addr; do
    adb connect "$addr" >/dev/null 2>&1
done
sleep 1
echo "ADB devices:"
adb devices -l | sed 1d | grep . || echo "  (none — check hotspot + wireless debugging)"

# 2. Launch the watch app if the watch is on ADB
WATCH_SERIAL=$(adb devices -l | awk '/SM_L320/{print $1; exit}')
if [ -n "$WATCH_SERIAL" ]; then
    adb -s "$WATCH_SERIAL" shell am start -n "$WATCH_APP/.presentation.MainActivity" >/dev/null 2>&1 \
        && echo "Watch app launched."
else
    echo "Watch not on ADB — fine, data still flows via Bluetooth. (Enable Wireless debugging on the watch to control it from here.)"
fi

# 3. VS Code on the server folder
code --remote "ssh-remote+misr" "$SERVER_DIR" >/dev/null 2>&1 \
    && echo "VS Code opened on misr:$SERVER_DIR"

# 4. Live server feed in its own Terminal window
osascript >/dev/null 2>&1 <<'EOF'
tell application "Terminal"
    do script "ssh misr 'watch -n 5 \"ls -lt /data1/wearables | head -15\"'"
    activate
end tell
EOF
echo "Live upload feed opened in Terminal (newest files on top, refreshes every 5 s)."

echo "── Done. A new JSON lands ~every 30 s while the watch is awake. ──"
