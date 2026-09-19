#!/usr/bin/env bash
#
# Proves, on a USB-attached handset against the Mac's FreeSWITCH, that the app stays
# reachable in every state the platform can put it in - and shows the one it cannot.
#
#   ./tools/reachability-check.sh                 every scenario, in order
#   ./tools/reachability-check.sh --skip-reboot   everything but the reboot (fastest)
#   ./tools/reachability-check.sh --only wifi     one scenario: foreground | background |
#                                                 recents | wifi | reboot | force-stop
#   S=<serial> ./tools/reachability-check.sh      pick the handset when several are attached
#
# Nothing here names an extension or an account. The handset's Wi-Fi address is read
# from the handset, the extensions it registers are read from the server's registration
# table for that address, and every call is placed to whatever was found. A phone with
# two logged-in accounts is checked on both.
#
# Each scenario ends the same way: FreeSWITCH originates a call to the extension and the
# check passes when the handset posts its ringing card (the app's notification id 2)
# within the ring window - which is the whole point, a call that arrives. "Registered"
# alone is not enough: the server keeps a binding for an hour after the phone behind it
# has died, so freshness is proved by the binding's expiry moving forward.
#
# Scenarios
#   foreground   the app on screen
#   background   HOME pressed
#   recents      the task removed - ActivityTaskManager.removeTask, the call the launcher
#                makes when a card is swiped away - and the process expected to survive
#                (or to be brought back by the sticky restart / the restart alarm)
#   wifi         Wi-Fi off until the app reports no network, then on: a fresh REGISTER
#                without anybody opening the app, and the process never having died
#   reboot       adb reboot, then registration and a ringing call with the app never
#                opened - the BOOT_COMPLETED receiver
#   force-stop   am force-stop, then Wi-Fi off/on. EXPECTED TO STAY DEAD: Android puts a
#                force-stopped package in the stopped state and delivers it nothing until
#                the user opens it. The check confirms that, then confirms recovery on the
#                next launch. It counts as a pass when the platform behaves as documented.
#
# Preconditions: one handset attached over USB with the app installed and at least one
# account logged in against this Mac's FreeSWITCH; fs_cli reachable; the handset and the
# Mac on the same network. Wi-Fi is toggled with `svc wifi`, which adb shell may do
# without root on the handsets this was written for.
set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
FS="${FS:-/usr/local/freeswitch/bin/fs_cli}"
PKG=com.whatsappv2
RING_WINDOW=10
ONLY=""; SKIP_REBOOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --skip-reboot) SKIP_REBOOT=1; shift ;;
    -h|--help) sed -n '2,/^set -u/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# ------------------------------------------------------------------ plumbing
c_ok=$'\033[32m'; c_bad=$'\033[31m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
log()  { printf '%s[%s]%s %s\n' "$c_dim" "$(date +%H:%M:%S)" "$c_off" "$*"; }
pass() { printf '%sPASS%s %s\n' "$c_ok" "$c_off" "$*"; RESULTS+=("PASS  $*"); }
fail() { printf '%sFAIL%s %s\n' "$c_bad" "$c_off" "$*"; RESULTS+=("FAIL  $*"); FAILED=1; }
RESULTS=(); FAILED=0

S="${S:-$($ADB devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$S" ] || { echo "no handset attached (adb devices)"; exit 2; }
sh() { $ADB -s "$S" shell "$@" 2>/dev/null; }

phone_ip()     { sh ip -4 addr show wlan0 | grep -o 'inet [0-9.]*' | awk '{print $2}'; }
pid()          { sh pidof "$PKG"; }
service_fg()   { sh dumpsys activity services "$PKG/.service.RegistrationService" | grep -q 'isForeground=true'; }
focus()        { sh dumpsys window | grep mCurrentFocus | grep -o 'com[^}/]*' | head -1; }
ringing()      { sh dumpsys notification | grep -E "NotificationRecord.*pkg=$PKG" | grep -q ' id=2 '; }
task_id()      { sh dumpsys activity recents | grep -oE "Task\{[0-9a-f]+ #[0-9]+ type=standard A=[0-9]+:$PKG" | grep -oE '#[0-9]+' | tr -d '#' | head -1; }
launch()       { sh am start -n "$PKG/.MainActivity" >/dev/null; }
applog()       { $ADB -s "$S" logcat -d 2>/dev/null | grep -E " (BootReceiver|RegistrationService|ServiceLauncher|SipApplication|RegistrationRecovery)[: ]" | tail -n "${1:-4}" | cut -c1-140 | sed 's/^/      /'; }

# The server's registration table, as "user port expires" rows for one address.
regs_for() { $FS -x "show registrations as json" | python3 -c "
import sys, json
for r in json.load(sys.stdin).get('rows', []):
    if r.get('network_ip') == '$1': print(r['reg_user'], r['network_port'], r['expires'])"; }

# Wait until every extension in EXTS is registered from the handset's current address
# with an expiry later than the one recorded in BASELINE (a fresh REGISTER), or give up.
wait_fresh() {  # $1 = seconds
  local deadline=$(( $(date +%s) + $1 )) ip
  while [ "$(date +%s)" -lt "$deadline" ]; do
    ip=$(phone_ip)
    if [ -n "$ip" ]; then
      local ok=1 e
      for e in $EXTS; do
        local row; row=$(regs_for "$ip" | awk -v e="$e" '$1==e')
        [ -n "$row" ] && [ "$(echo "$row" | awk '{print $3}')" -gt "$(baseline_of "$e")" ] || ok=0
      done
      [ $ok -eq 1 ] && return 0
    fi
    sleep 2
  done
  return 1
}
# "user=expires user=expires ..." - a plain string, so a stock macOS bash 3.2 can run this.
BASELINE=""
snapshot_baseline() { BASELINE=$(regs_for "$(phone_ip)" | awk '{printf "%s=%s ", $1, $3}'); }
baseline_of() { echo " $BASELINE" | grep -o " $1=[0-9]*" | cut -d= -f2 | head -1 | sed 's/^$/0/'; }

# Originate to every extension in turn; pass when the ringing card appears in time.
ring_each() {  # $1 = scenario label
  local e t hit
  for e in $EXTS; do
    $FS -x "bgapi originate {origination_caller_id_number=9001,origination_caller_id_name=ReachabilityCheck,ignore_early_media=true}user/$e &park" >/dev/null
    hit=0
    for t in $(seq 1 $RING_WINDOW); do sleep 1; if ringing; then hit=$t; break; fi; done
    $FS -x "hupall" >/dev/null; sleep 2
    if [ $hit -gt 0 ]; then pass "$1: $e rang on the handset after ${hit}s (focus: $(focus))"
    else fail "$1: $e did not ring within ${RING_WINDOW}s (focus: $(focus), pid: $(pid))"; applog; fi
  done
}

want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

# ------------------------------------------------------------------ discovery
IP=$(phone_ip); [ -n "$IP" ] || { echo "handset $S has no Wi-Fi address"; exit 2; }
EXTS=$(regs_for "$IP" | awk '{print $1}' | sort -u | tr '\n' ' ')
if [ -z "$EXTS" ]; then
  log "no extension is registered from $IP yet; launching the app once to find out"
  launch; sleep 8; EXTS=$(regs_for "$IP" | awk '{print $1}' | sort -u | tr '\n' ' ')
fi
[ -n "$EXTS" ] || { echo "nothing registers from $IP on this server - log an account in first"; exit 2; }
log "handset $S at $IP registers: $EXTS"
log "battery-optimisation exemption: $(sh dumpsys deviceidle whitelist | grep -q "$PKG" && echo granted || echo NOT granted)"
snapshot_baseline

# ------------------------------------------------------------------ scenarios
if want foreground; then
  log "== foreground: the app on screen"
  launch; sleep 3
  [ "$(focus)" = "$PKG" ] && pass "foreground: app is on screen" || fail "foreground: app not on screen ($(focus))"
  ring_each foreground
fi

if want background; then
  log "== background: HOME pressed"
  sh input keyevent KEYCODE_HOME; sleep 2
  service_fg && pass "background: service is foreground" || fail "background: service not foreground"
  ring_each background
fi

if want recents; then
  log "== recents: the task removed"
  launch; sleep 2; sh input keyevent KEYCODE_HOME; sleep 1
  before=$(pid); tid=$(task_id)
  [ -n "$tid" ] && sh am stack remove "$tid" >/dev/null
  sleep 6
  after=$(pid)
  if [ -n "$after" ] && [ "$after" = "$before" ]; then pass "recents: process $before survived the task removal"
  elif [ -n "$after" ]; then pass "recents: process was ended by the platform and came back as $after (was $before)"
  else fail "recents: no process 6s after the task was removed"; applog; fi
  service_fg && pass "recents: service is foreground" || fail "recents: service not foreground"
  ring_each recents
fi

if want wifi; then
  log "== wifi: off until the app reports no network, then on"
  before=$(pid); snapshot_baseline
  sh svc wifi disable
  for t in $(seq 1 20); do sleep 1; [ -z "$(phone_ip)" ] && break; done
  [ -z "$(phone_ip)" ] && log "wifi is down after ${t}s" || fail "wifi: svc wifi disable did not drop the address"
  sleep 8   # long enough for the recovery coordinator's debounce and the 'No network' state
  service_fg && pass "wifi: service stayed foreground through the outage" || fail "wifi: service gone during the outage"; applog 2
  sh svc wifi enable
  for t in $(seq 1 30); do sleep 1; [ -n "$(phone_ip)" ] && break; done
  log "wifi is back at $(phone_ip) after ${t}s"
  if wait_fresh 60; then pass "wifi: fresh REGISTER for $EXTS without the app being opened"
  else fail "wifi: no fresh REGISTER within 60s"; applog; fi
  after=$(pid)
  [ -n "$after" ] && [ "$after" = "$before" ] && pass "wifi: process $before never died" || fail "wifi: process changed ($before -> ${after:-none})"
  ring_each wifi
fi

if want force-stop; then
  log "== force-stop: the platform's stopped state (expected to stay dead until launched)"
  snapshot_baseline
  sh am force-stop "$PKG"; sleep 2
  sh svc wifi disable; sleep 6; sh svc wifi enable
  for t in $(seq 1 30); do sleep 1; [ -n "$(phone_ip)" ] && break; done
  sleep 20
  if [ -z "$(pid)" ] && ! wait_fresh 1; then
    pass "force-stop: no process and no fresh REGISTER - the platform's stopped state, as documented"
  else
    fail "force-stop: something revived a force-stopped app (pid $(pid)) - that would be a platform bug"
  fi
  launch
  if wait_fresh 40; then pass "force-stop: recovered on the next launch"; else fail "force-stop: no REGISTER after launch"; applog; fi
  ring_each force-stop
  sh input keyevent KEYCODE_HOME
fi

if want reboot && [ $SKIP_REBOOT -eq 0 ]; then
  log "== reboot: power-cycle, then registration and a call with the app never opened"
  snapshot_baseline
  $ADB -s "$S" reboot
  sleep 15
  for t in $(seq 1 90); do sleep 2; [ "$(sh getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; done
  log "boot completed after ~$((15 + 2 * t))s"
  # Wait for Wi-Fi to come back before waiting for the registration.
  for t in $(seq 1 30); do sleep 2; [ -n "$(phone_ip)" ] && break; done
  if wait_fresh 120; then pass "reboot: fresh REGISTER for $EXTS with the app never opened"; else fail "reboot: no fresh REGISTER within 120s of boot"; applog; fi
  [ "$(focus)" != "$PKG" ] && pass "reboot: the app was not opened (focus: $(focus))" || fail "reboot: the app is on screen, which the check must not have caused"
  service_fg && pass "reboot: service is foreground" || fail "reboot: service not foreground"
  $ADB -s "$S" logcat -d 2>/dev/null | grep -q "BootReceiver: android.intent.action.BOOT_COMPLETED" && pass "reboot: BootReceiver handled BOOT_COMPLETED" || fail "reboot: no BootReceiver log line"
  ring_each reboot
fi

# ------------------------------------------------------------------ summary
echo; echo "=== summary for $S ($IP), extensions: $EXTS"
printf '  %s\n' "${RESULTS[@]}"
exit $FAILED
