#!/usr/bin/env bash
#
# One command from a clean checkout to an installable APK, with the native stack compiled
# from vendored source (ADR-007, N-1: there is no prebuilt PJSIP binary in this repository).
#
#   ./build.sh                  arm64-v8a debug APK, PJSIP compiled from source
#   ./build.sh --abi x86_64     a different ABI
#   ./build.sh --all-abis       arm64-v8a, armeabi-v7a and x86_64
#   ./build.sh --reuse-native   skip the two SWIG stages, reuse the libraries already built
#   ./build.sh --install        adb install -r the result when it succeeds
#   ./build.sh --with-lyra      refuses today, and says exactly what is missing
#
# Why the toolchain is checked here rather than left to Gradle: every one of these failures
# reads as something else at the point Gradle hits it. A missing NDK surfaces as
# "property 'ndkRoot' doesn't have a configured value", and SWIG without its Java typemaps
# fails inside arrays_java.i, which reads like a corrupt source tree. Both are one package
# away and the message should say so.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

abis="arm64-v8a"
reuse_native=0
install_after=0
want_lyra=0

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
note() { printf '\033[36m%s\033[0m\n' "$1"; }

# The header comment above IS the help text: awk stops at the first line that is not a
# comment, so the two cannot drift apart the way a second copy of the usage would.
usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --abi) [ $# -ge 2 ] || die "--abi needs a value"; abis="$2"; shift 2 ;;
    --all-abis) abis="arm64-v8a,armeabi-v7a,x86_64"; shift ;;
    --reuse-native) reuse_native=1; shift ;;
    --install) install_after=1; shift ;;
    --with-lyra) want_lyra=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ---------------------------------------------------------------------- Lyra
#
# Refused rather than ignored. A flag that silently built something else is how "Lyra is
# working" ends up in a status report, and ADR-008 is explicit that the gate is open at
# criterion 1 only.
if [ "$want_lyra" = 1 ]; then
  cat >&2 <<'LYRA'

error: --with-lyra cannot build anything yet. Lyra is 0% implemented.

  What exists:
    - third_party/pjproject/aconfigure.ac already implements --with-lyra=DIR: it link-tests
      LyraDecoder::Create, sets ac_lyra_model_path and defines the flag. pjproject needs
      no change.
    - AudioCodec.LYRA exists in :domain, deliberately OUT of CodecPreferences.DEFAULT.

  What does not exist, and all four are required:
    1. third_party/lyra                          -- not vendored (google/lyra v1.3.2)
    2. TensorFlow Lite v2.11.0 for this ABI      -- ~1.35 GB, reached through
                                                    lyra/tflite_model_wrapper.cc, XNNPACK
                                                    delegate included directly. Building it
                                                    with NDK r27c is ADR-008 criterion 1 and
                                                    has never been attempted.
    3. com_google_audio_dsp                      -- no CMake build at all, 12 call sites
                                                    across 6 targets, all hand-written
    4. PJMEDIA_HAS_LYRA_CODEC                    -- still 0 in pjsip/config/pj/config_site.h,
                                                    which is the single source of truth (N-8)

  And the fact that survives either outcome: the deployed server offers PCMU, PCMA, G.729,
  G.723.1, AMR, Speex, VP8 and VP9 -- no Lyra. Even a successful build ships a codec no
  deployed peer accepts.

  Read docs/lyra-criterion-1.md and docs/architecture.md ADR-008 before starting. When
  criterion 1 passes, delete this block and wire --with-lyra=<dir> into the configure flags.

LYRA
  exit 2
fi

# ----------------------------------------------------------------------- NDK
#
# The native task reads ANDROID_NDK_ROOT, then ANDROID_NDK_HOME, then -Pandroid.ndkPath.
# Nothing reads local.properties' ndk.dir, which is the trap: the SDK is configured there
# and the NDK is not, so the failure looks like a broken checkout.
find_ndk() {
  if [ -n "${ANDROID_NDK_ROOT:-}" ]; then echo "$ANDROID_NDK_ROOT"; return; fi
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then echo "$ANDROID_NDK_HOME"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  # Newest first, so a machine with several NDKs picks the one most likely to be current.
  local candidate
  candidate="$(ls -1d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$candidate" ] && echo "$candidate"
}

ndk="$(find_ndk)"
[ -n "$ndk" ] && [ -d "$ndk" ] || die "no Android NDK found.
  Looked at: \$ANDROID_NDK_ROOT, \$ANDROID_NDK_HOME, \$ANDROID_HOME/ndk/*, ~/Library/Android/sdk/ndk/*
  Install one from Android Studio (SDK Manager > SDK Tools > NDK) or set ANDROID_NDK_ROOT."

# ---------------------------------------------------------------------- SWIG
#
# Two separate things go wrong here and they need separate messages.
#
#   - The Java typemaps are packaged apart from swig itself on MacPorts, and without them
#     both stages fail deep inside SWIG's own .i files.
#   - The build pins swig 4.2.0. 4.4.1 renames add/reserve to doAdd/doReserve in the
#     generated bindings, so a library built by one and bindings generated by the other do
#     not share JNI names. Overriding the pin is fine for a build you install and test; it
#     is not fine for one you ship.
gradle_args=(
  ":app:assembleDebug"
  "-Ppjsip.abis=$abis"
  "-Pandroid.ndkPath=$ndk"
)

if [ "$reuse_native" = 1 ]; then
  # The libraries on disk are still checked: :pjsip:assertNativeLibraries runs either way
  # and fails packaging if either .so is missing (N-6, N-14). What is skipped is rebuilding
  # them, which is correct only while third_party/ is unchanged.
  gradle_args+=("-x" ":pjsip:api:generatePjsua2Bindings" "-x" ":pjsip:buildPjsua2Native")
  note "reusing the native libraries already under pjsip/build/generated/jniLibs"
  note "  (correct while third_party/ is unchanged; drop --reuse-native to rebuild them)"
else
  command -v swig >/dev/null 2>&1 || die "swig is not installed, and the native stage needs it.
  MacPorts:       sudo port install swig swig-java
  Homebrew:       brew install swig
  Debian/Ubuntu:  sudo apt install swig
  Or build without it:  ./build.sh --reuse-native"

  swig_bin="$(command -v swig)"
  swig_version="$(swig -version 2>/dev/null | awk '/SWIG Version/ {print $3}')"
  swig_lib="$(swig -swiglib 2>/dev/null | head -1)"

  if [ ! -d "$swig_lib/java" ]; then
    die "swig $swig_version is installed without its Java typemaps: $swig_lib/java does not exist.
  Some distributions package them separately. Without them SWIG fails on arrays_java.i and
  enumtypeunsafe.swg, which reads like a corrupt source tree rather than a missing package.

  MacPorts:       sudo port selfupdate && sudo port install swig-java
  Debian/Ubuntu:  the swig package already includes them

  To build an APK right now with the native libraries already on disk:
      ./build.sh --reuse-native"
  fi

  gradle_args+=("-Ppjsip.swig=$swig_bin" "-Ppjsip.swig.version=$swig_version")

  if [ "$swig_version" != "4.2.0" ]; then
    printf '\033[33mwarning:\033[0m this build uses swig %s, and the pin is 4.2.0.\n' "$swig_version"
    printf '         Both stages use the same binary so the APK is self-consistent and fine\n'
    printf '         to install and test. Do NOT ship it: the JNI names differ from a 4.2.0\n'
    printf '         build. CI is the authority on what ships.\n\n'
  fi
fi

note "ABI(s):  $abis"
note "NDK:     $ndk"
note "building ..."
echo

./gradlew "${gradle_args[@]}" --console=plain

# ------------------------------------------------------------------- the APK
apk="$root/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || die "gradle reported success but $apk does not exist"

size_mb="$(awk -v b="$(wc -c <"$apk")" 'BEGIN {printf "%.1f", b/1048576}')"
built_at="$(date -r "$apk" '+%Y-%m-%d %H:%M:%S')"

printf '\n\033[32mAPK\033[0m  %s\n' "$apk"
printf '     %s MB, built %s\n\n' "$size_mb" "$built_at"

# What is actually inside it, because "it built" and "it contains the stack" are different
# claims and only the second one installs and makes a call.
printf 'native libraries in the APK:\n'
unzip -l "$apk" 'lib/*' | awk '/lib\/.*\.so$/ {printf "  %-46s %10d bytes\n", $4, $1}'
echo

printf 'install it with:\n  adb install -r %s\n\n' "$apk"

if [ "$install_after" = 1 ]; then
  adb_bin="$(command -v adb || true)"
  [ -n "$adb_bin" ] || adb_bin="$HOME/Downloads/platform-tools/adb"
  [ -x "$adb_bin" ] || die "--install was asked for but no adb was found.
  Not on PATH, and not at $HOME/Downloads/platform-tools/adb."
  note "installing with $adb_bin"
  "$adb_bin" install -r "$apk"
fi
