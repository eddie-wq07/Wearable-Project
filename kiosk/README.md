# :kiosk

Locks the study watch into the `:watch` app so participants cannot exit or tamper with it.
Device-owner lock-task kiosk with a passcode-gated exit (hidden 5-tap gesture on-watch, or
ADB broadcast).

Setup, exit, and de-provisioning instructions: [docs/kiosk-setup.md](docs/kiosk-setup.md).

Integration surface in `:watch` is intentionally tiny: `KioskManager.sync(this)` in
`MainActivity.onResume()` and `KioskGate { ... }` around the root composable; everything
else (device-admin receiver, control receiver, unlock screen, HOME alias) merges in from
this module's manifest.
