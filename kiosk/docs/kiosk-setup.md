# Kiosk mode — setup and operations

Goal: a study watch that participants **cannot exit, reconfigure, or play with**. The watch
boots into the study app, stays pinned there, and every way out is gated by the study
passcode.

**Study passcode: `MIS123!`** (only researchers should know this; it is stored on the watch
only as a SHA-256 hash).

---

## 1. How it works

The `:kiosk` module makes the study app a **device owner** and uses Android's **lock task
mode** ("true kiosk"), the strongest lockdown available without a custom OS image:

| Layer | Mechanism | Effect |
|---|---|---|
| Pinning | `startLockTask()` as device owner | No home, no recents, no back-out, no swipe-to-exit. Entered automatically on every app resume. |
| System UI | `setStatusBarDisabled`, `LOCK_TASK_FEATURE_NONE` | No quick-settings shade, no notifications, no system dialogs. |
| Launcher | `KioskHomeAlias` + persistent preferred HOME | If the app ever dies or the watch reboots, HOME resolves straight back into the study app — the real launcher is unreachable. |
| Keyguard | `setKeyguardDisabled(true)` | No lock screen between the participant and the app. |
| Restrictions | `DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_ADD_USER`, `DISALLOW_INSTALL_UNKNOWN_SOURCES` | Participant cannot wipe the watch, boot around the kiosk, or sideload. |
| Reboot | `BootReceiver` (in `:watch`) + HOME pin | Tracking service restarts and the UI relaunches after any reboot. |

All of this only activates when the app is **device owner** (section 2). On an
unprovisioned dev watch the app runs completely normally — no pinning, no prompts.

Key classes (all in `kiosk/src/main/java/com/example/testwatch/kiosk/`):

- `KioskManager` — policy application + lock/unlock state machine; `sync()` is called from
  `MainActivity.onResume()`.
- `KioskDeviceAdminReceiver` — the device-admin component named during provisioning.
- `KioskControlReceiver` — ADB broadcast channel (status / exit / re-arm / release).
- `KioskGate` + `KioskUnlockActivity` — hidden on-watch exit (section 4).

## 2. One-time provisioning (per watch)

Device-owner provisioning has one hard Android requirement: **no accounts on the device**.
Provision each watch **before** signing into Samsung/Google accounts (during initial watch
setup, skip/dismiss every account sign-in — dev mode already bypasses the Samsung partner
gate for the sensor SDK).

1. Enable developer options + ADB debugging (and *Debug over Wi-Fi* if using wireless adb)
   on the watch.
2. Connect: `adb connect <watch-ip>:5555` (or USB/dock).
3. Install the app:

   ```bash
   ./gradlew :watch:installRelease   # or installDebug during rollout testing
   ```

4. Make it device owner:

   ```bash
   adb shell dpm set-device-owner com.example.testwatch/com.example.testwatch.kiosk.KioskDeviceAdminReceiver
   ```

   Expected output: `Success: Device owner set to package com.example.testwatch`.

5. Set the participant ID as usual (`SET_PARTICIPANT_ID` broadcast), then launch the app:

   ```bash
   adb shell am start com.example.testwatch/.presentation.MainActivity
   ```

   On first resume the app applies every policy above and pins itself. Done — the watch is
   now locked to the study app.

### Verify

```bash
adb shell am broadcast -a com.example.testwatch.KIOSK_STATUS -n com.example.testwatch/com.example.testwatch.kiosk.KioskControlReceiver
adb logcat -d -s KioskControl KioskManager
```

Expect: `deviceOwner=true enabled=true lockTask=locked`. On-watch check: press the home
button — the app must stay in the foreground; swipe down — no shade.

## 3. Common failure: "there are already some accounts"

`dpm set-device-owner` refuses if any account exists. Fix: remove all accounts in watch
settings (or factory reset and redo setup while skipping sign-in), then repeat step 4.
Other refusal: "already several users" — remove secondary users; "device owner is already
set" — the watch is already provisioned (check `KIOSK_STATUS`).

## 4. Exiting the kiosk (researchers only)

### On the watch (no computer needed)

1. **Tap 5 times quickly** (within 3 seconds) on the **invisible hotspot at the top center
   of the screen** (over the "HEART RATE" / "MEASURING" label area).
2. The *Study lock* screen opens. Type the passcode `MIS123!` and press **Unlock**.
3. The watch unpins and normal navigation returns. 5 wrong attempts closes the screen.

### Over ADB

```bash
adb shell am broadcast -a com.example.testwatch.KIOSK_EXIT -n com.example.testwatch/com.example.testwatch.kiosk.KioskControlReceiver --es passcode 'MIS123!'
```

(Quote the passcode — `!` is a shell history character. The explicit `-n` component is required on all kiosk broadcasts: implicit broadcasts never reach manifest receivers on modern Android.) Wrong/missing passcode is rejected
and logged. Either exit only **disarms** the kiosk; the app stays device owner, so re-arming
is instant.

## 5. Re-arming

After maintenance, re-enable the kiosk (no passcode needed — arming is always safe):

```bash
adb shell am broadcast -a com.example.testwatch.KIOSK_ENABLE -n com.example.testwatch/com.example.testwatch.kiosk.KioskControlReceiver
```

Or simply relaunch the app after toggling: the pinned state always follows the kiosk flag on
resume. A watch handed to a participant must show `lockTask=locked` in `KIOSK_STATUS`.

## 6. Study-end de-provisioning

To return a watch to normal consumer behavior (lifts all restrictions, restores keyguard and
status bar, drops device-owner status):

```bash
adb shell am broadcast -a com.example.testwatch.KIOSK_RELEASE_OWNER -n com.example.testwatch/com.example.testwatch.kiosk.KioskControlReceiver --es passcode 'MIS123!'
```

Verify with `KIOSK_STATUS` (`deviceOwner=false`). If the platform refuses the programmatic
release (some builds restrict `clearDeviceOwnerApp` for non-test installs), the fallback is
a factory reset **after** running `KIOSK_EXIT` (which is required anyway — the
`DISALLOW_FACTORY_RESET` restriction is lifted by the release/exit paths, and a device-owner
watch cannot otherwise be reset by hand).

## 7. Dev watches

Nothing to do. Without device-owner status the kiosk never pins, never prompts, and the
5-tap gesture just opens a harmless passcode screen. `KioskManager` logs a warning
(`kiosk enabled but app is not device owner`) so an unprotected study watch is visible in
logcat.

## 8. Hardening notes / deliberate choices

- **ADB stays enabled.** `DISALLOW_DEBUGGING_FEATURES` would block participants with a dock,
  but it would also block `SET_PARTICIPANT_ID`, `GET_PUBKEY`, and every kiosk broadcast.
  Physical access + adb is considered acceptable risk for this study; revisit if watches are
  deployed unsupervised for long periods.
- **The passcode lives in docs and as a hash in code.** Rotating it = hash a new passcode
  (`printf 'NEW' | shasum -a 256`), update `KioskManager.PASSCODE_SHA256`, reinstall.
- **App updates while provisioned:** `adb install -r` works normally; device-owner status
  and the kiosk flag survive updates and reboots. Never `adb uninstall` a device-owner app
  (Android refuses; release owner first).
