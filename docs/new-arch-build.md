# Direct watch-to-server upload — implementation prompt

Repo: `github.com/eddie-wq07/Wearable-Project` (current `main`).

Before making changes, read `watch/src/main/java/com/example/testwatch/tracking/HrTrackingService.kt`, `watch/src/main/java/com/example/testwatch/sync/BatchSerializer.kt`, `watch/src/main/java/com/example/testwatch/data/*`, `watch/src/main/java/com/example/testwatch/config/*`, and everything under `mobile/src/main/java/com/example/testwatch/mobile/` to ground yourself in the existing code before editing.

## Goal

Eliminate the phone as a relay. The watch uploads directly to the server over SFTP whenever it is charging and connected to WiFi; otherwise it keeps buffering locally, exactly as it does today.

## Server access reality — verified live 2026-08-29 (READ THIS BEFORE §4–§8)

A diagnostic pass over SSH established what access we actually have on MISR, and it changes
the server-side half of this plan:

- **Account:** `edward` (uid 1050), groups `edward` + `wearables`. **No sudo** — not in the
  `sudo` group, `sudo -n` refused. We cannot create users, edit `sshd_config`, or restart sshd.
- **Upload directory:** `/data1/wearables`, owned `gene:wearables`, mode 770. Verified live:
  edward can create/read/delete files and subdirectories inside it. We cannot change the
  directory's own ownership/permissions, and everyone in the `wearables` group has the same
  full access.
- **Server:** Debian 10 (buster), OpenSSH 7.9p1 (`1:7.9p1-10+deb10u4`), service name
  `ssh.service`. Key auth for edward already works (ed25519).
- **Host key fingerprints for §5 pinning** (captured via `ssh-keyscan -p 16800`, 2026-08-29):
  - ED25519 `SHA256:ggNscecIXhow6MfQcGwdaSUXj6N6k/2V38OgAvXD0uc`
  - ECDSA   `SHA256:AbAJ43Yw5iTJIXpHRhNB1fYV/mQW0Qzq4M6kQrTV5YE`
  - RSA     `SHA256:0wMDYM7uf4fbVnQHGZLYzZr3FiAwFw7Cad3mECS0Wm8`

**Accepted trade-off (decision, 2026-08-29):** the chrooted write-only per-watch identities in
§8 require root we don't have, and we are NOT waiting on the server admin. The study proceeds
on the shared-access model, entirely self-serve under the existing account:

- All watches authenticate **as `edward`**; each watch still generates its own keypair (§4
  unchanged) and its public key is appended to `~edward/.ssh/authorized_keys` — that file is
  ours, no admin needed. Revoking a watch = deleting its line.
- Each watch uploads into its own subdirectory `/data1/wearables/<watch-slot>/`, which we
  create ourselves (§6's "dedicated folder per watch" stands; it's just not enforced by chroot).
- **What this model does NOT give:** isolation. Any watch's credentials can read/delete
  everything under `/data1/wearables`. Accepted as fine for a controlled 2-watches-out-at-a-time
  study. §8's `sshd_config` documentation task is therefore dropped — if per-watch isolation is
  ever wanted, it goes to the server admin (likely `gene`, who owns the directory) as an
  infrastructure request.

## 1. Remove the phone relay path

In `HrTrackingService.kt`, remove the Wear Data Layer send logic (the `Wearable.getMessageClient`-based sync loop).

Delete the entire `mobile/` module:
- `wear/HrBatchListenerService.kt`
- the phone-side `data/` mirror (`PhoneHrSample.kt`, `PhoneHrDao.kt`, `PhoneHrDatabase.kt`, `PhoneSensorBatch.kt`, `PhoneSensorDao.kt`)
- `upload/UploadWorker.kt`
- `config/ServerConfig.kt.example`
- `boot/BootReceiver.kt`
- `status/PipelineStatus.kt` + `StatusActivity.kt`

If a monitoring dashboard is still wanted later, it should read from the server, not the phone — do not rebuild it now.

## 2. Port the upload logic onto the watch

Create `watch/src/main/java/com/example/testwatch/upload/UploadWorker.kt`, adapted from the mobile version's `buildPayload`/`buildSensorPayload`/`uploadOverSftp` logic (same `sshj` + `BouncyCastle` dependency, same JSON shape, same `hr_<participantId>_<epochMs>.json` / `sensors_<participantId>_<epochMs>.json` naming).

Source rows from the watch's own `HrDao.unsynced()` / `SensorBatchDao.unsynced()`, and on successful upload call the existing `markSynced` / `pruneSynced` — this part of the pipeline barely changes, it's just running locally now instead of on the phone.

## 3. Trigger condition: charging + WiFi, not a timer

Implement the upload check as a `WorkManager` job constrained on `NetworkType.UNMETERED` plus a charging check (`BatteryManager.isCharging` via `ACTION_BATTERY_CHANGED`, or `Constraints.setRequiresCharging(true)` if available on this WorkManager version) — evaluate whichever combination is actually enforceable and confirm before assuming both are simultaneously expressible as `Constraints`.

The intent is: attempt upload only when plugged in and on WiFi; do nothing otherwise. Do not implement a fixed "every 3 days" timer — that was an earlier draft of this idea and is superseded by the charging-triggered version.

## 4. Security — per-watch SSH keys, not a shared password

Each watch gets its own SSH keypair, generated and stored in the Android Keystore (hardware-backed, non-exportable — signing happens inside the TEE, the raw private key is never readable even with debug/root access).

This requires bridging `sshj`'s `KeyProvider` interface to an Android Keystore `PrivateKeyEntry` — flag this integration explicitly if it proves non-trivial, since `sshj` typically expects a standard `PrivateKey` object and Keystore-backed keys behave differently under the hood.

Do not fall back to a plaintext private key file on disk.

## 5. Host key pinning — remove `PromiscuousVerifier()`

Replace it with a verifier checking against a hardcoded SHA256 fingerprint of the actual server's host key, stored as a constant in `ServerConfig.kt`.

## 6. `ServerConfig.kt` on the watch

Holds:
- server host/port
- this watch's remote upload subdirectory (one dedicated folder per watch, matching its participant ID slot)
- a reference to the Keystore alias for its private key

No shared password field.

## 7. `BatchSerializer.kt`

Repurpose it to build the upload-ready JSON payload (absorb `buildPayload`/`buildSensorPayload`) instead of the old `WireBatch`/`WireSensorBatch` Wear-message format.

Remove `encode`/`decode` and the wire data classes if nothing else references them once `mobile/` is deleted.

## 8. Explicitly out of scope for this task — flag, don't build

- The server-side setup itself (20 chrooted, write-only, no-read/no-list/no-delete SFTP identities, one per watch, each restricted to its own subfolder) is an infrastructure task, not app code. Document the required `sshd_config` shape (`Match` block per key, `ChrootDirectory`, `ForceCommand internal-sftp`, directory mode restricting to write+execute only) as a comment or a `docs/server-setup.md` note, but don't attempt to provision the actual server.
- There is no shared inbox and no validation/ingestion step — each watch's key can only write into its own dedicated folder, full stop. Do not build or reference any kind of ingester process.

## 9. Docs

Update `docs/handoff.md`:
- remove or rewrite anything describing the phone-relay path (the BT-vs-WiFi drain-throughput question, the phone periodic-upload-floor note) since it's now obsolete
- add a section describing the new direct-to-server, per-watch-key, charge-triggered architecture
- leave the existing "Participant ID reassignment on watch reuse" open question as-is — it's unaffected by this change

## After implementing

Build `watch/` to confirm it compiles, and report back anything from steps 3 or 4 that couldn't be implemented as described so the plan can be adjusted rather than silently worked around.