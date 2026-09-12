#!/usr/bin/env bash
#
# Build the app, put it on the phone plugged in over USB, and open it.
#
#   ./launch.sh                    build (native libraries reused when already built), install
#                                  or update on the connected phone, open the app
#   ./launch.sh --no-build         install and open the APK already under app/build/outputs
#   ./launch.sh --rebuild-native   compile the native stack again rather than reusing it
#   ./launch.sh --reinstall        uninstall first -- the way past INSTALL_FAILED_UPDATE_INCOMPATIBLE.
#                                  This ERASES the app's accounts, credentials and call log.
#   ./launch.sh --serial SERIAL    the phone to use when more than one is attached
#
# ## What "update" means here, and when it cannot
#
# `adb install -r` replaces the app in place and keeps its data, whether the version went
# up, stayed the same, or went down. The one case it refuses is an APK signed by a
# different key than the one on the phone -- a build from another machine, or from CI --
# and Android's only way past that is to uninstall. That erases the SIP accounts and the
# call log, so it is behind --reinstall rather than done for you.
#
# ## Which ABI gets built
#
# The one the phone reports (ro.product.cpu.abi): arm64-v8a on every handset, x86_64 on
# an emulator. Building for the device rather than for a default is what makes this work
# on an emulator without a flag, and refuse -- rather than install and die on the first
# call -- on an ABI this project does not build.
#
# ## Why it proves the update rather than assuming it
#
# "Success" from adb says an APK was installed, not which one. The version and the
# install time are read back off the phone before and after, and the process is checked
# a moment after launch: an app that installed and crashed on its first frame would
# otherwise look exactly like one that worked.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$1" >&2; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

build=1
rebuild_native=0
reinstall=0
serial="${ANDROID_SERIAL:-}"

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) build=0; shift ;;
    --rebuild-native) rebuild_native=1; shift ;;
    --reinstall) reinstall=1; shift ;;
    --serial) [ $# -ge 2 ] || die "--serial needs a value"; serial="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ------------------------------------------------------------------ adb and the phone

adb_bin="$(command -v adb || true)"
for candidate in "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb" "$HOME/Downloads/platform-tools/adb"; do
  [ -n "$adb_bin" ] && break
  [ -x "$candidate" ] && adb_bin="$candidate"
done
[ -n "$adb_bin" ] || die "no adb found: not on PATH, not in the SDK's platform-tools, not in ~/Downloads/platform-tools."

# One line per attached device: "<serial>\t<state>". Everything that is not in state
# 'device' is named with what to do about it, because "no devices found" when the phone
# is visibly plugged in is the message that sends people to check the cable when the
# problem is a dialog they have not tapped yet.
devices="$("$adb_bin" devices | awk 'NR > 1 && NF >= 2 { print $1 "\t" $2 }')"
if [ -n "$serial" ]; then
  state="$(printf '%s\n' "$devices" | awk -F'\t' -v s="$serial" '$1 == s { print $2 }')"
  [ -n "$state" ] || die "no device with serial $serial is attached. Attached:
$(printf '%s\n' "$devices" | sed 's/^/  /')"
else
  ready="$(printf '%s\n' "$devices" | awk -F'\t' '$2 == "device" { print $1 }')"
  case "$(printf '%s\n' "$ready" | grep -c .)" in
    0)
      case "$devices" in
        *unauthorized*) die "the phone is attached but has not allowed USB debugging from this computer.
  Unlock it and tap 'Allow' on the USB debugging prompt, then run this again." ;;
        *offline*) die "the phone is attached but offline. Unplug and replug the cable, or toggle USB debugging." ;;
        "") die "no phone is attached over USB. Plug one in with USB debugging on." ;;
        *) die "no phone is ready. adb sees:
$(printf '%s\n' "$devices" | sed 's/^/  /')" ;;
      esac ;;
    1) serial="$ready"; state="device" ;;
    *) die "more than one phone is attached and adb cannot choose. Name one with --serial:
$(printf '%s\n' "$devices" | sed 's/^/  /')" ;;
  esac
fi
[ "$state" = "device" ] || die "device $serial is '$state', not ready."
export ANDROID_SERIAL="$serial"
adb() { "$adb_bin" "$@"; }

model="$(adb shell getprop ro.product.model | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
android="$(adb shell getprop ro.build.version.release | tr -d '\r')"
step "phone: $model (Android $android, $abi, serial $serial)"

# The ABI set comes from gradle.properties, the same place build.sh and Gradle read it,
# so a phone this project does not build for is refused here with the list, rather than
# deep inside the native build as something that reads like a broken checkout.
supported="$(awk -F= '$1 == "pjsip.abis" { sub(/^[^=]*=/, ""); gsub(/[ \t]/, ""); print; exit }' gradle.properties)"
case ",$supported," in
  *",$abi,"*) ;;
  *) die "this phone is $abi, and this project builds only: $supported" ;;
esac

pkg="$(sed -n -E 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts)"
[ -n "$pkg" ] || die "could not read applicationId from app/build.gradle.kts"

# What is on the phone now, so the update can be shown rather than claimed.
installed() {
  local dump
  dump="$(adb shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r')"
  case "$dump" in
    *"versionName="*)
      printf '%s (code %s), installed %s' \
        "$(printf '%s\n' "$dump" | sed -n -E 's/^[[:space:]]*versionName=(.*)$/\1/p' | head -1)" \
        "$(printf '%s\n' "$dump" | sed -n -E 's/^[[:space:]]*versionCode=([0-9]+).*/\1/p' | head -1)" \
        "$(printf '%s\n' "$dump" | sed -n -E 's/^[[:space:]]*lastUpdateTime=(.*)$/\1/p' | head -1)" ;;
    *) printf 'not installed' ;;
  esac
}
before="$(installed)"
echo "  $pkg on the phone: $before"

# ------------------------------------------------------------------ build

apk="app/build/outputs/apk/debug/app-debug.apk"
if [ "$build" -eq 1 ]; then
  build_args=(--abi "$abi")
  native_so="pjsip/build/generated/jniLibs/$abi/libpjsua2.so"
  if [ "$rebuild_native" -eq 0 ] && [ -f "$native_so" ]; then
    # Reuse is the fast path and the usual one: a Kotlin change does not need the SIP
    # stack cross-compiled again. The library's age is printed so a stale one is visible;
    # --rebuild-native is the answer after touching third_party/ or pjsip/config.
    build_args+=(--reuse-native)
    step "building $abi, reusing libpjsua2.so from $(date -r "$native_so" '+%Y-%m-%d %H:%M')"
  else
    step "building $abi with the native stack compiled from source"
  fi
  ./build.sh "${build_args[@]}"
else
  [ -f "$apk" ] || die "--no-build, but there is no APK at $apk. Run ./build.sh, or drop --no-build."
  step "installing the APK already built $(date -r "$apk" '+%Y-%m-%d %H:%M')"
fi
[ -f "$apk" ] || die "no APK at $apk after the build."

# The APK must carry the phone's ABI. build.sh narrows the packaged set to what it built,
# so a mismatch here is a wrong --abi on a --no-build install, which Android would refuse
# with INSTALL_FAILED_NO_MATCHING_ABIS -- a message that does not say which ABI it wanted.
case "$(unzip -l "$apk" 'lib/*' | awk '/libpjsua2\.so$/ {print $4}')" in
  *"lib/$abi/"*) ;;
  *) die "$apk does not carry libpjsua2.so for $abi. It was built for another ABI; run without --no-build." ;;
esac

# ------------------------------------------------------------------ install

if [ "$reinstall" -eq 1 ] && [ "$before" != "not installed" ]; then
  step "uninstalling $pkg first (--reinstall): its accounts and call log are erased"
  adb uninstall "$pkg" >/dev/null || warn "uninstall reported failure; trying the install anyway."
fi

step "installing on $model"
if ! out="$(adb install -r "$apk" 2>&1)"; then
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      die "the phone has $pkg signed with a different key, and Android will not update across keys.
  That is an APK from another machine or from CI. The way past it erases the app's data:
      ./launch.sh --reinstall" ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      die "the phone has a newer versionCode than this APK, and Android refuses a downgrade in place.
      ./launch.sh --reinstall   (erases the app's data)" ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      die "the phone is out of storage for a $(du -h "$apk" | cut -f1) APK." ;;
    *) die "adb install failed:
$(printf '%s\n' "$out" | sed 's/^/  /')" ;;
  esac
fi
after="$(installed)"
echo "  before: $before"
echo "  after:  $after"
[ "$after" != "$before" ] || warn "the phone reports the same install time as before -- did the install actually happen?"

# ------------------------------------------------------------------ open it

step "opening $pkg"
# Resolved from the installed manifest rather than hard-coded, so this stays right if the
# activity is ever renamed. Debug builds declare a second launcher -- LeakCanary's, in the
# same package -- so the one in the app's own namespace (com.whatsappv2/.Main...) is
# preferred, and anything else in the package is only a fallback.
launchers="$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" 2>/dev/null \
  | tr -d '\r' | grep -E "^[[:space:]]*$pkg/" | sed 's/^[[:space:]]*//')"
component="$(printf '%s\n' "$launchers" | grep -E "^$pkg/(\.|$pkg\.)" | head -1 || true)"
[ -n "$component" ] || component="$(printf '%s\n' "$launchers" | head -1)"
[ -n "$component" ] || die "no launcher activity for $pkg is installed on the phone."
# Stop first, so what opens is the APK just installed and not an activity the old process
# still had on screen.
adb shell am force-stop "$pkg"
started="$(adb shell am start -W -n "$component" 2>&1 | tr -d '\r')"
printf '%s\n' "$started" | grep -E '^(Status|Error|Warning)' | sed 's/^/  /' || printf '%s\n' "$started" | sed 's/^/  /'

sleep 2
pid="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
if [ -n "$pid" ]; then
  printf '\n\033[32mrunning\033[0m  %s (pid %s) on %s\n' "$component" "$pid" "$model"
else
  warn "$pkg is not running two seconds after launch. The most recent crash on the phone:"
  adb logcat -b crash -d -v time 2>/dev/null | tr -d '\r' | grep -A 12 "FATAL EXCEPTION\|Fatal signal" | tail -14 | sed 's/^/  /'
  exit 1
fi
