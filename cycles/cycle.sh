#!/usr/bin/env bash
# Cycle assignment for the wearables study: participant ID = <watch#><cycle letter>.
#
#   cycle.sh status              # all cycles on record, with days of data each
#   cycle.sh next <watch#>       # print the next unused ID for that watch (e.g. 2B)
#   cycle.sh assign <watch#> [<ip:port>]
#                                # reserve the next ID on the server; if a watch is
#                                # reachable over ADB, also set the ID on it
#
# The server tree IS the registry (see README.md): assign creates
# /data1/wearables/pilot/<pid>/ immediately, so `next` can never hand out the
# same letter twice — even before the cycle's first upload.
set -uo pipefail

SERVER="edward@misr.sauder.ubc.ca"
SSH_PORT=16800
STUDY_DIR="/data1/wearables/pilot"
PKG="com.example.testwatch"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ok()  { printf '    OK: %s\n' "$*"; }

remote() { ssh -o ConnectTimeout=10 -o BatchMode=yes -p "$SSH_PORT" "$SERVER" "$@"; }

pids() { remote "ls '$STUDY_DIR' 2>/dev/null" | grep -E '^[0-9]+[A-Z]$' || true; }

next_id() {  # $1 = watch number -> echoes e.g. 2B
  local n="$1" last
  last=$(pids | grep -E "^${n}[A-Z]$" | sed "s/^${n}//" | sort | tail -1)
  if [ -z "$last" ]; then
    echo "${n}A"
  elif [ "$last" = "Z" ]; then
    die "watch $n has used cycles A through Z — no letters left"
  else
    echo "${n}$(echo "$last" | tr 'A-Y' 'B-Z')"
  fi
}

cmd_status() {
  local list
  list=$(pids)
  [ -n "$list" ] || { echo "no cycles on record in $STUDY_DIR"; return; }
  printf '%-6s %-6s %-6s %s\n' "WATCH" "CYCLE" "DAYS" "LATEST DATA"
  # One ssh round-trip for all rows.
  remote 'for p in '"$STUDY_DIR"'/*/; do
            pid=$(basename "$p")
            days=$(ls "$p" 2>/dev/null | wc -l)
            latest=$(ls "$p" 2>/dev/null | tail -1)
            echo "$pid $days ${latest:-—}"
          done' | grep -E '^[0-9]+[A-Z] ' | while read -r pid days latest; do
    printf '%-6s %-6s %-6s %s\n' "${pid%%[A-Z]*}" "${pid##*[0-9]}" "$days" "$latest"
  done
}

cmd_next() {
  local n="${1:-}"
  [[ "$n" =~ ^[0-9]+$ ]] || die "usage: cycle.sh next <watch#>"
  next_id "$n"
}

cmd_assign() {
  local n="${1:-}" addr="${2:-}" pid
  [[ "$n" =~ ^[0-9]+$ ]] || die "usage: cycle.sh assign <watch#> [<ip:port>]"
  pid=$(next_id "$n") || exit 1
  echo "==> Assigning $pid (watch $n)"

  remote "mkdir -p '$STUDY_DIR/$pid'" || die "could not reserve $STUDY_DIR/$pid on the server"
  ok "reserved $STUDY_DIR/$pid — 'next $n' now returns the letter after ${pid##*[0-9]}"

  # Best-effort: push the ID to a reachable watch. A real reissue goes through
  # wipe + setup.sh instead (README.md lifecycle).
  if [ -z "$addr" ]; then
    addr=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3}' | sort -u)
    [ "$(echo "$addr" | grep -c .)" -eq 1 ] || addr=""
  fi
  if [ -n "$addr" ] && adb connect "$addr" >/dev/null 2>&1 \
      && adb -s "$addr" shell echo ok >/dev/null 2>&1; then
    adb -s "$addr" shell am broadcast -a com.example.testwatch.SET_PARTICIPANT_ID \
      -n "$PKG/.admin.SetParticipantIdReceiver" --es participant_id "$pid" >/dev/null \
      && ok "watch at $addr now uploads as $pid" \
      || echo "    WARNING: broadcast failed — set the ID via setup.sh"
  else
    echo "    no single watch reachable over ADB — when provisioning, run:  ./setup.sh $pid"
  fi
  echo "==> DONE — $pid"
}

case "${1:-}" in
  status) cmd_status ;;
  next)   shift; cmd_next "$@" ;;
  assign) shift; cmd_assign "$@" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
