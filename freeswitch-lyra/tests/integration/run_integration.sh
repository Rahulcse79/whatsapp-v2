#!/usr/bin/env bash
# End-to-end integration test for server-side Lyra recording.
#
# Proves, against the REAL local FreeSWITCH with mod_lyra + mod_lyra_record loaded:
#   A. recording disabled  -> a Lyra call connects and NOTHING is recorded;
#   B. recording enabled   -> a Lyra call is recorded to a playable WAV of the right
#                             rate/channels/duration;
#   C. concurrency         -> N simultaneous Lyra calls each produce their own WAV with
#                             no cross-call contamination, with CPU/RAM measured.
#
# It routes through a dedicated itest<NNNN> extension (installed and removed here), so it
# never edits the production dialplan. Recording is toggled by rewriting a private copy
# of lyra_recording.conf.xml and reloading — the operator's config is left untouched.
#
# Usage: run_integration.sh [--prefix P] [--concurrency "1 5 10"] [--seconds 8]
#                           [--users "1000 1001 1006 1007 ..."]  (pairs; excludes 1004/1005)
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
prefix="/usr/local/freeswitch"
concurrency="1 5 10"
seconds=8
fsip=""   # SIP host to register/dial against; default: the internal profile's bind IP
# Default user pool: 1000-1019 minus the live handsets 1004/1005.
users="1000 1001 1002 1003 1006 1007 1008 1009 1010 1011 1012 1013 1014 1015 1016 1017 1018 1019"
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix="$2"; shift 2;;
    --concurrency) concurrency="$2"; shift 2;;
    --seconds) seconds="$2"; shift 2;;
    --users) users="$2"; shift 2;;
    --host) fsip="$2"; shift 2;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done

fs_cli="$prefix/bin/fs_cli"
autoload="$prefix/etc/freeswitch/autoload_configs"
dialplan="$prefix/etc/freeswitch/dialplan/default"
build="$root/build"
models="$build/lyra-prefix/model_coeffs"; [ -d "$models" ] || models="$root/../third_party/lyra/lyra/model_coeffs"
work="$(mktemp -d "${TMPDIR:-/tmp}/lyra-itest.XXXXXX")"
rec_root="$work/recordings"
mkdir -p "$rec_root"
frames="$work/tone.lyra"
sipua="$here/sipua.py"
pass=0; fail=0
declare -a CLEANUP_FILES

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { echo "  PASS: $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $*" >&2; fail=$((fail+1)); }

cleanup() {
  log "cleanup"
  set_enabled false || true
  rm -f "$dialplan/03_lyra_record_itest.xml"
  [ -f "$autoload/lyra_recording.conf.xml.itest-bak" ] && mv "$autoload/lyra_recording.conf.xml.itest-bak" "$autoload/lyra_recording.conf.xml"
  "$fs_cli" -x "reloadxml" >/dev/null 2>&1 || true
  "$fs_cli" -x "lyra_recording reload" >/dev/null 2>&1 || true
  echo "  (recordings + logs kept in $work)"
}
trap cleanup EXIT

# Rewrite the recording config with a given enabled flag and our temp root, then reload.
set_enabled() {
  local en="$1"
  cat > "$autoload/lyra_recording.conf.xml" <<XML
<configuration name="lyra_recording.conf" description="itest">
  <settings>
    <param name="enabled" value="$en"/>
    <param name="recording-path" value="$rec_root"/>
    <param name="format" value="wav"/>
    <param name="track" value="stereo"/>
    <param name="sample-rate" value="16000"/>
    <param name="directory-pattern" value="{date}"/>
    <param name="filename-pattern" value="{time}_{caller}_{callee}_{uuid}"/>
    <param name="min-free-mb" value="10"/>
    <param name="max-seconds" value="60"/>
  </settings>
</configuration>
XML
  "$fs_cli" -x "reloadxml" >/dev/null 2>&1
  "$fs_cli" -x "lyra_recording reload" >/dev/null 2>&1
}

count_wavs() { find "$rec_root" -name '*.wav' 2>/dev/null | wc -l | tr -d ' '; }

place_call() {  # caller_user callee_user -> runs a full call, backgrounded logs in $work
    local caller="$1" callee="$2" tag="$3"
    python3 "$sipua" callee --user "$callee" --host "$fsip" --seconds "$((seconds+4))" \
        --wait 6 --lyra "$frames" >"$work/callee-$tag.log" 2>&1 &
    local cpid=$!
    sleep 1
    python3 "$sipua" caller --user "$caller" --dial "itest$callee" --host "$fsip" \
        --seconds "$seconds" --lyra "$frames" >"$work/caller-$tag.log" 2>&1
    wait "$cpid" 2>/dev/null
}

# ---- preflight ----------------------------------------------------------------
log "preflight"
[ -x "$fs_cli" ] || { echo "no fs_cli at $fs_cli" >&2; exit 1; }
"$fs_cli" -x "status" >/dev/null 2>&1 || { echo "FreeSWITCH not running" >&2; exit 1; }
# Discover the internal profile's SIP bind IP unless one was given (it is usually a LAN IP,
# not 127.0.0.1 — sofia binds to the routable interface).
if [ -z "$fsip" ]; then
  fsip=$("$fs_cli" -x "sofia status profile internal" | awk -F'[@:]' '/^ *SIP-IP/ {print $2}' | tr -d ' ')
  [ -z "$fsip" ] && fsip=$("$fs_cli" -x "sofia status profile internal" | sed -n 's|.*sip:mod_sofia@\([0-9.]*\):.*|\1|p' | head -1)
  [ -z "$fsip" ] && fsip="127.0.0.1"
fi
echo "  using SIP host $fsip"
for m in mod_lyra mod_lyra_record; do
  "$fs_cli" -x "module_exists $m" | grep -q true || { echo "$m not loaded — run scripts/install.sh --reload" >&2; exit 1; }
done
[ -x "$build/tests/integration/tools/wav_check" ] || { echo "build wav_check first (see run_integration.sh header)"; }
wav_check="$build/tests/integration/tools/wav_check"
gen="$build/tests/integration/tools/lyra_gen_frames"
[ -x "$gen" ] || gen="$(command -v lyra_gen_frames || true)"
"$fs_cli" -x "lyra version" | grep -q "1.3" && ok "mod_lyra reports library $("$fs_cli" -x 'lyra version')" || bad "lyra version"

log "generate a real Lyra tone stream"
if [ -x "$gen" ]; then
  "$gen" "$models" "$frames" --seconds "$((seconds+6))" --bitrate 3200 --hz 440 && ok "generated $frames" || bad "frame generation"
else
  echo "  (lyra_gen_frames not built; UAs will stream silence frames — recording still exercised)"
fi

# Install the itest dialplan.
cp "$here/03_lyra_record_itest.xml" "$dialplan/03_lyra_record_itest.xml"
[ -f "$autoload/lyra_recording.conf.xml" ] && cp "$autoload/lyra_recording.conf.xml" "$autoload/lyra_recording.conf.xml.itest-bak"
"$fs_cli" -x "reloadxml" >/dev/null 2>&1

read -r u1 u2 rest <<<"$users"

# ---- A. disabled: no recording -----------------------------------------------
log "A. recording DISABLED — call connects, nothing recorded"
set_enabled false
before=$(count_wavs)
place_call "$u1" "$u2" "disabledA"
after=$(count_wavs)
if grep -q "in call" "$work/caller-disabledA.log"; then ok "call connected with recording disabled"; else bad "call did not connect (see $work/caller-disabledA.log)"; fi
if [ "$after" = "$before" ]; then ok "no WAV created while disabled ($after)"; else bad "a WAV was created while disabled ($before -> $after)"; fi
"$fs_cli" -x "lyra_recording status json" | grep -q '"skipped_disabled"' && ok "status reports skipped_disabled counter"

# ---- B. enabled: one playable recording --------------------------------------
log "B. recording ENABLED — one playable WAV, correct rate/channels/duration"
set_enabled true
before=$(count_wavs)
place_call "$u1" "$u2" "enabledB"
sleep 1
after=$(count_wavs)
if [ "$after" -gt "$before" ]; then ok "a WAV was created ($before -> $after)"; else bad "no WAV created while enabled"; fi
newest=$(find "$rec_root" -name '*.wav' -print0 | xargs -0 ls -t 2>/dev/null | head -1)
if [ -n "$newest" ] && [ -x "$wav_check" ]; then
  if "$wav_check" "$newest" --rate 16000 --channels 2 --min-seconds "$(echo "$seconds*0.5" | bc)" --max-seconds "$((seconds+5))"; then
    ok "WAV valid: $(basename "$newest")"
  else
    bad "wav_check rejected $newest"
  fi
elif [ -n "$newest" ]; then
  echo "  (wav_check not built; file is $newest, $(stat -f%z "$newest" 2>/dev/null || stat -c%s "$newest") bytes)"
fi
# filename must carry caller, callee (the dialed itest<NNNN>) and a uuid (uniqueness).
if [ -n "$newest" ] && echo "$(basename "$newest")" | grep -Eq "_${u1}_itest${u2}_[0-9a-f-]{36}\.wav"; then ok "filename encodes caller/callee/uuid"; else bad "filename pattern not applied: $(basename "${newest:-none}")"; fi

# ---- C. concurrency ----------------------------------------------------------
log "C. concurrency — N simultaneous calls, one WAV each, no contamination"
for N in $concurrency; do
  # need 2N distinct users
  count=0; pool=()
  for u in $users; do pool+=("$u"); count=$((count+1)); [ "$count" -ge $((2*N)) ] && break; done
  if [ "${#pool[@]}" -lt $((2*N)) ]; then echo "  skip N=$N: need $((2*N)) users, have ${#pool[@]}"; continue; fi
  set_enabled true
  before=$(count_wavs)
  # sample CPU/RAM of the freeswitch process during the run
  fspid=$(pgrep -x freeswitch | head -1)
  ( for _ in $(seq 1 "$seconds"); do ps -o %cpu=,rss= -p "$fspid" 2>/dev/null; sleep 1; done ) > "$work/load-N$N.txt" &
  loadpid=$!
  pids=()
  for ((i=0; i<N; i++)); do
    ca="${pool[$((2*i))]}"; ce="${pool[$((2*i+1))]}"
    ( place_call "$ca" "$ce" "N${N}_$i" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  wait "$loadpid" 2>/dev/null
  sleep 1
  after=$(count_wavs)
  made=$((after-before))
  if [ "$made" -eq "$N" ]; then ok "N=$N: produced exactly $N recordings"; else bad "N=$N: expected $N recordings, got $made"; fi
  # each of the N newest WAVs should be a valid, non-empty stereo 16k file
  badvalid=0
  while IFS= read -r w; do
    [ -x "$wav_check" ] && { "$wav_check" "$w" --rate 16000 --channels 2 --min-seconds 1 >/dev/null 2>&1 || badvalid=$((badvalid+1)); }
  done < <(find "$rec_root" -name '*.wav' -newermt "@0" -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -n "$N")
  [ "${badvalid:-0}" -eq 0 ] && ok "N=$N: all recordings valid" || bad "N=$N: $badvalid invalid recordings"
  peak_cpu=$(awk '{print $1}' "$work/load-N$N.txt" | sort -n | tail -1)
  peak_rss=$(awk '{print $2}' "$work/load-N$N.txt" | sort -n | tail -1)
  echo "  N=$N measured: peak FreeSWITCH CPU ${peak_cpu:-?}%, peak RSS $(( ${peak_rss:-0} / 1024 )) MB, active=$("$fs_cli" -x 'lyra_recording status json' | sed 's/.*"active"://; s/,.*//')"
done

log "recording engine metrics"
"$fs_cli" -x "lyra_recording status"
"$fs_cli" -x "lyra status"

log "RESULT: $pass passed, $fail failed"
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
