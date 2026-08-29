# Wearable Project

Data-collection pipeline for the UBC Data + AI Lab (Sauder) / SRCA sleep-health study:
**Galaxy Watch 8 → lab server (direct SFTP, no phone relay)**.

## The repo at a glance

One folder holds everything we wrote; everything else is documentation or build machinery.

```
Wearable-Project/
│
│  ── OUR CODE ──────────────────────────────────────────────────────
├── watch/      Wear OS app. Collects sensor data, buffers it in an
│               on-watch database, uploads straight to the server over
│               SFTP whenever it is charging on WiFi.
│               └─ src/main/java/com/example/testwatch/
│                  ├─ sensors/       ← THE SENSOR LAYER (Samsung SDK code)
│                  ├─ tracking/      the always-on service that runs everything
│                  ├─ data/          on-watch database (Room)
│                  ├─ upload/        SFTP uploader + this watch's Keystore SSH key
│                  ├─ sync/          upload JSON payload building
│                  ├─ admin/         ADB broadcasts (participant ID, pubkey export)
│                  ├─ config/        tuning knobs + server config (no secrets)
│                  └─ presentation/  the watch face UI
│
│  ── DOCS ──────────────────────────────────────────────────────────
├── docs/
│   └─ handoff.md             living session log: device quirks, provisioning, open questions
├── README.md   ← you are here (the only doc allowed at root)
│
│  ── BUILD MACHINERY (must live at root — Gradle/IDE look for them there) ──
├── settings.gradle.kts   declares the project = the :watch module
├── build.gradle.kts      root build script (just declares plugin versions)
├── gradle.properties     JVM/AndroidX flags for every build
├── local.properties      YOUR machine's Android SDK path (gitignored, not shared)
├── gradlew / gradlew.bat "Gradle wrapper" — the build launcher. ./gradlew builds
│                         with the exact pinned Gradle version, no install needed
├── gradle/               the wrapper's jar + version pin, and libs.versions.toml
│                         (the one list of all dependency versions)
│
│  ── GENERATED / IDE-OWNED (safe to ignore, never edit) ─────────────
├── .idea/      Android Studio's project settings (IDE writes these)
├── .gradle/    Gradle's build cache
├── .kotlin/    Kotlin compiler session cache
└── .git/       version control history
```

**Why the build files can't be foldered:** Gradle bootstraps itself by looking for
`settings.gradle.kts`, `gradle.properties`, `gradlew`, and `gradle/wrapper/` at fixed
root paths *before* any script runs — and Android Studio's sync assumes the same layout.
Same for `local.properties` (Android Gradle Plugin) and each module's `build.gradle.kts`
and `AndroidManifest.xml` inside its module. Everything that was legally movable has
been moved (`docs/`, per-module `config` packages).

## Going deeper (high level → low level)

1. **This README** — what each top-level thing is.
2. **[docs/handoff.md](docs/handoff.md)** — the operational details: provisioning, ADB commands, Samsung SDK quirks.
3. The code, in this order: `watch/.../sensors/SensorSpec.kt` (the sensor catalogue) → `tracking/HrTrackingService.kt` (the hub) → `upload/UploadWorker.kt` (the upload).

## Build

```bash
./gradlew :watch:installDebug    # watch APK
```

Before a watch can upload, provision its key once: `adb shell am broadcast -a
com.example.testwatch.GET_PUBKEY` prints the public-key line; append it to the
server account's `~/.ssh/authorized_keys` (details in docs/handoff.md).

## Research context

Study documents (proposal, plans, VISION abstract) live in the Google Drive folder
[Wearable Project](https://drive.google.com/drive/folders/1MaUn37OZ_yVCrYMO06pskBvTQjqDbYXc) —
Drive is the single source of truth for study-side context; this repo only holds code.
