# Cycle assignment

How participant IDs are issued across the life of each physical watch.

## The scheme

- Each **watch** has a permanent number stamped at provisioning: watch 1, watch 2, … 20.
- Each **wearer** of a watch is one *cycle*, lettered in order: A (first), B (second), C, …
- **Participant ID = watch number + cycle letter**: `2B` = watch 2, second wearer.
- Every upload lands under the current **study folder** on MISR:

```
/data1/wearables/
└── pilot/                  ← study folder (STUDY in watch ServerConfig.kt)
    ├── 1A/2026-09-01/sensors_1A_continuous.json
    │                 sensors_1A_ondemand.json
    ├── 2A/…
    └── 3A/…                ← appears the moment 3A is assigned
```

## The registry is the server tree

There is no spreadsheet. `/data1/wearables/pilot/<pid>/` exists for every cycle
ever assigned: `cycle.sh assign` creates the folder at assignment time (empty
until the watch's first upload), so `cycle.sh next` derives the next unused
letter by listing the tree and can never issue a duplicate.

## Watch lifecycle

1. **New watch** → `./cycles/cycle.sh next <watch#>` says `<n>A` → provision with
   `./setup.sh <n>A --pair`.
2. **Watch returns** at end of a cycle → `./drain.sh` (final offload; merges on
   the server), verify the data, then factory-reset the watch.
3. **Reissue** → `./cycles/cycle.sh assign <watch#>` reserves the next letter on
   the server and prints the `setup.sh` command to run against the wiped watch.
   (If the watch is still provisioned and reachable over ADB, `assign` also sets
   the new ID on it directly — only enough for testing; a real reissue should go
   through the full wipe + `setup.sh`.)

## Commands

```
cycle.sh status              # all cycles on record, with days of data each
cycle.sh next <watch#>       # print the next unused ID (no side effects)
cycle.sh assign <watch#>     # reserve next ID on the server (+ set on watch if reachable)
```
