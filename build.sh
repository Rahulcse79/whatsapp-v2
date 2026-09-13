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
#
# Lyra is part of the build, not a flag (ADR-008 closed at Exit A, 2026-09-10): the
# declared feature set in pjsip/config/pj/config_site.h says PJMEDIA_HAS_LYRA_CODEC 1, and
# pjsip/build-native.sh builds the closure from third_party/ before pjproject on every run.
# The first native build pays ~25 minutes for TensorFlow Lite; --reuse-native skips it.
#
# Why the toolchain is checked here rather than left to Gradle: every one of these failures
# reads as something else at the point Gradle hits it. A missing NDK surfaces as
# "property 'ndkRoot' doesn't have a configured value", and SWIG without its Java typemaps
# fails inside arrays_java.i, which reads like a corrupt source tree. Both are one package
# away and the message should say so.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

reuse_native=0
install_after=0

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
note() { printf '\033[36m%s\033[0m\n' "$1"; }

# The supported ABI set and the SWIG pin are DECLARED IN gradle.properties, not here.
# Gradle reads them natively through providers.gradleProperty and this script reads the
# same file, so there is one answer instead of two that drift. A second copy here is not a
# tidiness question: move the pin in Gradle and this script would warn about the old one —
# silent on a build that is wrong and noisy on one that is right, which is the worst
# possible way for a warning to fail.
prop() {
  awk -F= -v k="$1" '$1 == k { sub(/^[^=]*=/, ""); gsub(/^[ \t]+|[ \t]+$/, ""); print; exit }' \
    "$root/gradle.properties"
}

supported_abis="$(prop pjsip.abis)"
swig_pin="$(prop pjsip.swig.version)"
[ -n "$supported_abis" ] || die "pjsip.abis is not declared in gradle.properties."
[ -n "$swig_pin" ] || die "pjsip.swig.version is not declared in gradle.properties."

# One ABI by default: the handsets this is tested on are arm64, and building three costs
# three native builds. --all-abis expands to whatever gradle.properties declares.
abis="arm64-v8a"

# An unknown ABI is caught here for the same reason the NDK and SWIG are: passed through,
# it fails deep in the native build as something that reads like a broken checkout.
check_abis() {
  local abi
  for abi in ${1//,/ }; do
    case ",$supported_abis," in
      *",$abi,"*) ;;
      *) die "unknown ABI: $abi
  Supported, from gradle.properties (pjsip.abis): $supported_abis" ;;
    esac
  done
}

# The header comment above IS the help text: awk stops at the first line that is not a
# comment, so the two cannot drift apart the way a second copy of the usage would.
usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --abi) [ $# -ge 2 ] || die "--abi needs a value"; check_abis "$2"; abis="$2"; shift 2 ;;
    --all-abis) abis="$supported_abis"; shift ;;
    --reuse-native) reuse_native=1; shift ;;
    --install) install_after=1; shift ;;
    --with-lyra) echo "note: --with-lyra is not a flag any more; Lyra is in every build (ADR-008 Exit A)" >&2; shift ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ----------------------------------------------------------------------- NDK
#
# ----------------------------------------------------------------------- JDK
#
# Gradle needs JVM 17+ (CI runs 21), but a shell whose JAVA_HOME points at an older JDK
# fails deep in Gradle with "requires JVM 17 or later ... currently configured to use JVM
# 11" — a message about the daemon, not about this being one export away. So the build
# chooses a JDK itself and hands it to Gradle, rather than inheriting whatever the shell
# happens to have.
JDK_MINIMUM=17

# The major version of a JDK home, or nothing if it cannot be read.
jdk_major() {
  local home="$1"
  [ -x "$home/bin/java" ] || return 1
  # `release` carries JAVA_VERSION="21.0.10"; reading it avoids launching a JVM. The
  # leading component is the major for 9+, and for a stray 1.8 it is "1" — below the
  # minimum, which is the correct verdict anyway.
  local version
  version="$(sed -n 's/^JAVA_VERSION="\{0,1\}\([0-9][0-9]*\).*/\1/p' "$home/release" 2>/dev/null | head -1)"
  [ -n "$version" ] || version="$("$home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
  [ -n "$version" ] && echo "$version"
}

# Prints a JDK home of at least JDK_MINIMUM, preferring the newest, or nothing.
find_jdk() {
  local home major
  # The shell's own, when it is already new enough — nothing to change.
  if [ -n "${JAVA_HOME:-}" ]; then
    major="$(jdk_major "$JAVA_HOME" || true)"
    [ -n "$major" ] && [ "$major" -ge "$JDK_MINIMUM" ] && { echo "$JAVA_HOME"; return; }
  fi
  # macOS keeps the canonical answer here; ask for the newest, then the minimum.
  if [ -x /usr/libexec/java_home ]; then
    for v in 21 "$JDK_MINIMUM"; do
      home="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
      [ -n "$home" ] && [ -x "$home/bin/java" ] && { echo "$home"; return; }
    done
  fi
  # Failing that, the usual install locations, newest first.
  local candidate
  for candidate in \
    "$HOME/jdks"/*/Contents/Home "$HOME/jdks"/* \
    "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
    /Library/Java/JavaVirtualMachines/*/Contents/Home \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$candidate/bin/java" ] || continue
    major="$(jdk_major "$candidate" || true)"
    [ -n "$major" ] && [ "$major" -ge "$JDK_MINIMUM" ] && echo "$candidate"
  done | sort -V | tail -1
}

jdk="$(find_jdk)"
[ -n "$jdk" ] && [ -x "$jdk/bin/java" ] || die "no JDK $JDK_MINIMUM or newer found, and Gradle needs one.
  Looked at: \$JAVA_HOME, /usr/libexec/java_home, ~/jdks, the system JavaVirtualMachines, Android Studio's JBR.
  Install one (e.g. Temurin 21) or set JAVA_HOME to a JDK $JDK_MINIMUM+.
  \$JAVA_HOME is currently: ${JAVA_HOME:-unset}"
# Exported so the Gradle wrapper and the daemon it starts both use it, regardless of the
# shell's own JAVA_HOME. Also passed explicitly below, so a stale daemon on the wrong JVM
# is replaced rather than reused.
export JAVA_HOME="$jdk"

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
  "-Dorg.gradle.java.home=$JAVA_HOME"
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

  # A user-local copy of the typemaps is a supported way out and it is what this machine
  # uses: MacPorts' swig ships without Lib/java, so a copy of the whole lib plus java/ sits
  # under ~/.local/share/swig/<version>. Find it rather than making every invocation carry
  # SWIG_LIB= by hand — the script can see it, so it should. Exporting is what does the
  # work: swig reads SWIG_LIB itself, and both stages inherit it from here.
  if [ ! -d "$swig_lib/java" ]; then
    for candidate in "$HOME/.local/share/swig/$swig_version" "$HOME/.local/share/swig"/*; do
      [ -d "$candidate/java" ] || continue
      export SWIG_LIB="$candidate"
      swig_lib="$candidate"
      note "Java typemaps: $SWIG_LIB (the swig on PATH ships none)"
      break
    done
  fi

  if [ ! -d "$swig_lib/java" ]; then
    die "swig $swig_version is installed without its Java typemaps: $swig_lib/java does not exist.
  Some distributions package them separately. Without them SWIG fails on arrays_java.i and
  enumtypeunsafe.swg, which reads like a corrupt source tree rather than a missing package.

  MacPorts:       sudo port selfupdate && sudo port install swig-java
  Debian/Ubuntu:  the swig package already includes them

  Or put a copy where this script looks for one, which needs no root:
      cp -R \"$swig_lib\" ~/.local/share/swig/$swig_version
      cp -R <a swig source tree>/Lib/java ~/.local/share/swig/$swig_version/

  To build an APK right now with the native libraries already on disk:
      ./build.sh --reuse-native"
  fi

  gradle_args+=("-Ppjsip.swig=$swig_bin" "-Ppjsip.swig.version=$swig_version")

  if [ "$swig_version" != "$swig_pin" ]; then
    printf '\033[33mwarning:\033[0m this build uses swig %s, and the pin is %s.\n' "$swig_version" "$swig_pin"
    printf '         Both stages use the same binary so the APK is self-consistent and fine\n'
    printf '         to install and test. Do NOT ship it: the JNI names differ from a 4.2.0\n'
    printf '         build. CI is the authority on what ships.\n\n'
  fi
fi

note "ABI(s):  $abis"
note "NDK:     $ndk"
note "JDK:     $JAVA_HOME"
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
  # adb's own answer when two phones are plugged in is "adb: error: failed to resolve host:
  # more than one device", which reads like DNS. Two TC15s on one desk is this project's
  # documented setup (docs/HANDOFF.md §0e), so name the real problem and the real fix.
  ready="$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  count="$(printf '%s' "$ready" | grep -c . || true)"

  if [ "$count" -eq 0 ]; then
    die "--install was asked for but no device is ready.
  \`$adb_bin devices\` lists none in state 'device'. Check the cable, and that the phone
  has accepted this machine's USB debugging key."
  fi

  if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    die "--install was asked for but $count devices are attached:
$(printf '      %s\n' $ready)
  adb cannot choose. Name one:
      ANDROID_SERIAL=<serial> ./build.sh --install"
  fi

  note "installing with $adb_bin${ANDROID_SERIAL:+ (ANDROID_SERIAL=$ANDROID_SERIAL)}"
  "$adb_bin" install -r "$apk"
fi
