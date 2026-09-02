# Per-watch provisioning playbook

The complete sequence to take any watch — factory-fresh or previously used — to a fully
operational, kiosk-locked study watch. Repeat identically for every watch. Roles: **[you]**
= physical steps on phone/watch; **[mac]** = ADB/server steps run from the provisioning
machine.

Prereqs (once per session): `watch-debug.apk` built (`./gradlew :watch:assembleDebug`),
server port reachable (`nc -z misr.sauder.ubc.ca 16800`), phone with Galaxy Wearable on
the lab WiFi.

## Phase 0 — clean slate

- **Previously used watch** (has accounts / old app): factory reset from the watch itself:
  Settings → General → Reset → Reset. Do NOT unpair from Galaxy Wearable (leave the stale
  entry; removing it while the watch is connected also wipes it — reset from the watch is
  the controlled path). Old buffered data dies here — drain it first if it matters.
- **Factory-fresh watch**: nothing to do.

## Phase 1 — pairing [you]

1. Galaxy Wearable → **Add new device** → pair the watch. The other watches' entries stay;
   only one connects at a time and disconnected watches keep running standalone.
2. **Skip every account sign-in** (Samsung and Google). This is load-bearing: device-owner
   provisioning (kiosk) refuses if ANY account exists on the watch. Dev mode already
   bypasses the Samsung partner gate for the sensor SDK — no account needed.
3. Decline restore/copy — set up as new. Join the **lab WiFi** (same network as the mac).
4. If a system update is forced, let it finish (this is the slowest part of the whole flow).

## Phase 2 — debugging bridge [you → mac]

5. On watch: Settings → About watch → Software info → tap **Software version** 5× → dev mode.
6. Settings → Developer options → enable **ADB debugging** + **Wireless debugging** →
   **Pair new device** → read the IP:port + 6-digit code to the operator.
7. [mac] `adb pair <ip>:<pair-port>` (enter code) → `adb connect <ip>:<port>` →
   `adb devices -l` shows the watch.

## Phase 3 — install + kiosk device-owner [mac]

Order matters: device owner BEFORE launching anything account-touching.

```bash
adb -s <watch> install -r watch/build/outputs/apk/debug/watch-debug.apk
adb -s <watch> shell pm grant com.example.testwatch android.permission.BODY_SENSORS
adb -s <watch> shell pm grant com.example.testwatch android.permission.health.READ_HEART_RATE
adb -s <watch> shell pm grant com.example.testwatch android.permission.POST_NOTIFICATIONS
adb -s <watch> shell dpm set-device-owner com.example.testwatch/com.example.testwatch.kiosk.KioskDeviceAdminReceiver
# expect: Success: Device owner set to package com.example.testwatch
```

If dpm refuses with "already some accounts" → an account slipped in during setup; remove it
in watch settings (or reset and redo Phase 1, skipping sign-ins). On Samsung-account
watches the usual culprit shows as `type=com.samsung.android.wearable.samsungaccount`;
sign out via watch Settings → Account and backup → Samsung account.

## Phase 3.5 — Samsung Health developer mode [you] — DO THIS BEFORE LAUNCHING THE APP

Factory reset wipes this toggle; without it the sensor SDK refuses to connect
(`SDK_POLICY_ERROR`, handoff §7) and the watch records **nothing — silently**. On the
watch:

1. Open **Samsung Health** → **Settings** → **About Samsung Health**.
2. Rapid-tap the version number ~10 times → "Developer mode enabled" toast.

Do it now, while the app hasn't pinned the screen yet — once kiosk is armed you'd have
to passcode-exit first.

## Phase 4 — identity + SSH key [mac]

```bash
# Participant ID: <watch#><cycle letter> — e.g. watch 1, first cycle = 1A
adb -s <watch> shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
    -n com.example.testwatch/.admin.SetParticipantIdReceiver --es participant_id "1A"

# This watch's SSH pubkey (authorized_keys line printed in logcat, tag GetPublicKey)
adb -s <watch> shell am broadcast -a com.example.testwatch.GET_PUBKEY \
    -n com.example.testwatch/.admin.GetPublicKeyReceiver
adb -s <watch> logcat -d -s GetPublicKey

# Register it on the server (per watch; revoke later by deleting the line)
ssh -p 16800 edward@misr.sauder.ubc.ca "echo '<the line>' >> ~/.ssh/authorized_keys"
```

A factory-reset watch has a NEW key — re-register even if the watch was provisioned before
(delete its old line).

## Phase 5 — launch + kiosk verification

```bash
adb -s <watch> shell am start com.example.testwatch/.presentation.MainActivity
adb -s <watch> shell am broadcast -a com.example.testwatch.KIOSK_STATUS
adb -s <watch> logcat -d -s KioskControl KioskManager
# expect: deviceOwner=true enabled=true lockTask=locked
```

On-watch checks [you]:
- Live BPM shows; app is pinned — home button does nothing, no swipe-down shade.
- **Exit test**: 5 quick taps on the invisible top-center hotspot → passcode `MIS123!` →
  unpins. Re-arm: `adb shell am broadcast -a com.example.testwatch.KIOSK_ENABLE`.
- **Reboot test**: hold side button → power off → on. Watch must boot straight back into
  the pinned app with tracking running (BootReceiver + HOME pin).
- **On-demand test** (on wrist): tap **Measure now** → 4-sensor round runs with prompts.

## Phase 6 — upload proof (charger + WiFi)

Let it buffer ≥5 min of data, then put the watch on its charger [you] and force a drain:

```bash
adb -s <watch> shell am broadcast -a com.example.testwatch.DRAIN_NOW \
    -n com.example.testwatch/.admin.DrainNowReceiver
adb -s <watch> logcat -s UploadWorker:V | grep -E "SFTP|auth|upload"
```

Success: `SFTP put /data1/wearables/<pid>/<YYYY-MM-DD>/hr_<pid>_<HHMMSS>_001.json` lines,
then verify server-side:

```bash
ssh -p 16800 edward@misr.sauder.ubc.ca "ls -laR /data1/wearables/<pid>/"
```

Failure mode to watch for on first-ever run: publickey auth exception = the
Keystore-through-sshj signing problem (handoff §10); fallback is an app-private key.

## Phase 7 — hand-off state

- Kiosk re-armed (`KIOSK_STATUS` → `lockTask=locked`).
- **Wireless debugging OFF** (Developer options) — stops the reconnect buzz; re-enable via
  the exit gesture + settings when servicing.
- Watch on participant's wrist. Daily cycle from here: buffer all day → first charge+WiFi
  moment in each 24 h window uploads automatically. No phone needed ever again.

## Per-cycle reissue (same watch, next participant)

No reset needed: exit kiosk (gesture or broadcast), `SET_PARTICIPANT_ID` to the next letter
(1A → 1B), optionally drain, re-arm kiosk. Key, install, and device-owner all survive.
