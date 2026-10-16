#!/usr/bin/env bash
#
# One command from a clean checkout to an installable APK, with the native stack compiled
# from vendored source (ADR-007, N-1: there is no prebuilt PJSIP binary in this repository).
#
#   ./build.sh                  the Android APK (the default target, unchanged)
#   ./build.sh android          the same, named
#   ./build.sh ios              the iOS app
#   ./build.sh all              both, with a per-platform result at the end
#
#   ./build.sh --abi x86_64     a different ABI
#   ./build.sh --all-abis       arm64-v8a, armeabi-v7a and x86_64
#   ./build.sh --reuse-native   skip the two SWIG stages, reuse the libraries already built
#   ./build.sh --install        adb install -r the result when it succeeds
#
# `all` runs both targets even when the first one fails, and exits non-zero if either did.
# A target that cannot run on this machine says which tool is missing and how to get it; it
# never reports success it did not earn. See docs/ios-port.md for what iOS can do today.
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

# The platform to build. A positional rather than a flag because it selects *what is
# built* rather than how, and because `./build.sh` with no argument has to go on meaning
# exactly what it meant before -- it is in docs/, in CI notes and in muscle memory.
target="android"
# iOS-only flags, gathered in the loop below and read by build_ios.
IOS_EXTRA_ARGS=()

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
    android|ios|all) target="$1"; shift ;;
    --abi) [ $# -ge 2 ] || die "--abi needs a value"; check_abis "$2"; abis="$2"; shift 2 ;;
    --all-abis) abis="$supported_abis"; shift ;;
    --reuse-native) reuse_native=1; shift ;;
    --install) install_after=1; shift ;;
    --with-lyra) echo "note: --with-lyra is not a flag any more; Lyra is in every build (ADR-008 Exit A)" >&2; shift ;;
    # iOS-only options, collected rather than acted on here so that an Android build
    # that is handed one fails with a message naming the target instead of silently
    # ignoring it.
    --ipa|--ipa-signed) IOS_EXTRA_ARGS+=("$1"); shift ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ----------------------------------------------------------------------- JDK
#
# Chosen before the target is dispatched, because every target needs it: Gradle builds the
# Android APK and it also builds the shared Kotlin framework the iOS app links against.
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

# ------------------------------------------------------------------- Android
#
# Everything from here to the end of the function is what this script has always done,
# moved inside a function so that `ios` and `all` can exist without it running first. The
# body is unchanged: same NDK and SWIG preflight, same Gradle invocation, same APK report.
# The JDK is chosen above instead, because both targets need one.
build_android() {

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
}

# ----------------------------------------------------------------------- iOS
#
# The iOS app is a real Xcode project driven by xcodebuild, with the shared Kotlin logic
# arriving as an XCFramework the Gradle build produces. None of that can run without a
# full Xcode: the Command Line Tools carry no iPhoneOS SDK and no simulator runtimes, so
# both Kotlin/Native and xcodebuild fail on the same missing piece.
#
# Each check below names the single thing that is missing and how to get it, because the
# native failures do not. Kotlin/Native without the SDK reports a linker it cannot run,
# and xcode-select reports "tool 'xcodebuild' requires Xcode" while Xcode is, as far as
# the user is concerned, installed -- the Command Line Tools are not Xcode.
build_ios() {
  local xcode_app sdk ipa_mode="" want_ipa=0

  # `--ipa` and `--ipa-signed` are read from the remaining arguments rather than from a
  # flag parsed up top, so that `./build.sh ios` keeps meaning exactly what it did.
  for extra in "${IOS_EXTRA_ARGS[@]:-}"; do
    case "$extra" in
      --ipa)        want_ipa=1; ipa_mode="--unsigned" ;;
      --ipa-signed) want_ipa=1; ipa_mode="--signed" ;;
      "")           ;;
      *) die "unknown iOS option: $extra (try --ipa or --ipa-signed)" ;;
    esac
  done

  [ "$(uname -s)" = "Darwin" ] || die "the iOS target needs macOS; this is $(uname -s).
  Apple's SDKs and the simulator do not exist on other platforms, and there is no
  cross-compiler. Build the Android target here and the iOS one on a Mac."

  # `xcode-select -p` pointing at the Command Line Tools is the usual state of a machine
  # that has never built for a device, and it is indistinguishable from "Xcode installed"
  # until something asks for an SDK. Ask now.
  if ! xcodebuild -version >/dev/null 2>&1; then
    xcode_app="$(ls -d /Applications/Xcode*.app 2>/dev/null | head -1 || true)"
    if [ -n "$xcode_app" ]; then
      die "Xcode is installed at $xcode_app but the developer directory points elsewhere:
      $(xcode-select -p 2>/dev/null)
  Point it at Xcode (this needs your password):
      sudo xcode-select -s $xcode_app/Contents/Developer"
    fi
    die "the iOS target needs a full Xcode, and this machine has only the Command Line Tools.
      xcode-select -p  ->  $(xcode-select -p 2>/dev/null || echo 'nothing')
  There is no iPhoneOS SDK and no simulator runtime under the Command Line Tools, so
  neither Kotlin/Native nor xcodebuild can produce anything for iOS.

  Install Xcode from the App Store, or from https://developer.apple.com/download/all/
  if this machine needs a specific version, then:
      sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
      xcodebuild -runFirstLaunch

  macOS $(sw_vers -productVersion 2>/dev/null) caps the Xcode it can run -- 12.x tops out
  at Xcode 14.2 (iOS 16 SDK), which builds and runs locally but is below the SDK Apple
  currently requires for App Store submission. See docs/ios-port.md."
  fi

  sdk="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)"
  [ -n "$sdk" ] || die "Xcode is active but no iOS simulator SDK is installed.
      xcodebuild -runFirstLaunch
      xcodebuild -downloadPlatform iOS"

  note "Xcode:   $(xcodebuild -version | head -1)"
  note "SDK:     $(basename "$sdk")"

  # ---------------------------------------------------------------- 1. the native stack
  #
  # PJSIP, OpenSSL, Opus and libvpx from third_party/, the same vendored sources the
  # Android build compiles. Skipped when the archives are already there and no vendored
  # tree has changed -- the script keeps its own content-hash stamps, so this is a few
  # seconds on a warm tree and the better part of an hour on a cold one.
  local slices="--device"
  [ "$want_ipa" -eq 0 ] && slices=""        # a simulator run needs both; an IPA needs the device
  note "building PJSIP for iOS ..."
  "$root/ios/scripts/build-pjsip-ios.sh" $slices

  # ---------------------------------------------------------------- 2. the shared module
  note "building the shared Kotlin framework ..."
  "$root/ios/scripts/build-shared-framework.sh" Release

  # ---------------------------------------------------------------- 3. the project
  #
  # Regenerated every time. It is committed so the repository opens in Xcode on a clean
  # checkout, and regenerated here so a source file added without re-running the
  # generator does not silently fail to be compiled -- the generator is deterministic,
  # so this is a no-op whenever nothing has changed.
  note "generating the Xcode project ..."
  python3 "$root/ios/scripts/generate-xcodeproj.py"

  # ---------------------------------------------------------------- 4. build
  if [ "$want_ipa" -eq 1 ]; then
    note "building the IPA ($ipa_mode) ..."
    "$root/ios/scripts/build-ipa.sh" "$ipa_mode"
    return
  fi

  note "building the iOS app for the simulator ..."
  xcodebuild -project "$root/ios/WhatsAppV2.xcodeproj" -scheme WhatsAppV2 \
    -configuration Debug -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$root/build/ios" \
    CODE_SIGNING_ALLOWED=NO \
    build | tail -30

  local app
  app="$(ls -d "$root/build/ios/Build/Products/Debug-iphonesimulator"/*.app 2>/dev/null | head -1 || true)"
  [ -n "$app" ] || die "xcodebuild reported success but no .app was produced under
  build/ios/Build/Products/Debug-iphonesimulator."

  printf '\n\033[32mAPP\033[0m  %s\n' "$app"
  printf '     built %s\n\n' "$(date -r "$app" '+%Y-%m-%d %H:%M:%S')"
  printf 'run it with:\n  xcrun simctl install booted %s\n' "$app"
  printf 'or produce an installable archive with:\n  ./build.sh ios --ipa\n\n'
}

# ------------------------------------------------------------------ dispatch
#
# `all` runs both targets and reports both. It deliberately does NOT stop at the first
# failure: when two platforms are being kept in step, "Android is fine and iOS is broken"
# and "both are broken" are different situations and the summary has to tell them apart.
# `set -e` would have made the first failure the whole answer.
if [ "${#IOS_EXTRA_ARGS[@]}" -gt 0 ] && [ "$target" = "android" ]; then
  die "${IOS_EXTRA_ARGS[*]} is an iOS option, and the target is android.
  Did you mean:  ./build.sh ios ${IOS_EXTRA_ARGS[*]}"
fi

case "$target" in
  android) build_android ;;
  ios)     build_ios ;;
  all)
    android_status=0
    ios_status=0
    # Subshells, because `die` ends the script it is called from and in `all` that would
    # take the summary with it -- the one thing this target exists to print. A subshell's
    # exit ends only the subshell, so a failing platform becomes a status rather than the
    # end of the run. Each target writes its artefact to disk, so nothing is lost with the
    # subshell's environment.
    note "==> target: android"
    ( build_android ) || android_status=$?
    echo
    note "==> target: ios"
    ( build_ios ) || ios_status=$?
    echo
    printf '\033[36m==> summary\033[0m\n'
    [ "$android_status" = 0 ] && printf '  android  \033[32mok\033[0m\n' \
                             || printf '  android  \033[31mfailed\033[0m (exit %s)\n' "$android_status"
    [ "$ios_status" = 0 ]     && printf '  ios      \033[32mok\033[0m\n' \
                             || printf '  ios      \033[31mfailed\033[0m (exit %s)\n' "$ios_status"
    echo
    [ "$android_status" = 0 ] && [ "$ios_status" = 0 ] || exit 1
    ;;
  *) die "unknown target: $target (android, ios or all)" ;;
esac
