#!/usr/bin/env bash
#
# PJSIP and its three native dependencies, cross-compiled for iOS from the vendored
# sources in third_party/.
#
# ## What this is a sibling of
#
# `pjsip/build-native.sh` does the same job for Android: OpenSSL, Opus, libvpx and the
# Lyra closure, then pjproject configured against all four, for three ABIs. Every trap
# that script documents applies here too, because they are properties of the libraries
# and not of the NDK:
#
#   * a configure that accepts "no TLS, no Opus, no VPX" silently and builds a stack
#     without them, so the first anybody knows is a feature missing on a handset;
#   * the host `ar`/`ranlib` leaking into a cross-compile;
#   * autotools writing into the source tree, which third_party/ forbids.
#
# The Apple-specific ones this script adds:
#
#   * `libtool -static`, not `ar`, is the archiver Apple's toolchain wants. pjproject's
#     own configure-iphone sets `AR="libtool -static -o"` for this reason, and OpenSSL and
#     Opus have to be told separately.
#   * `-fembed-bitcode` is NOT passed. Bitcode was deprecated in Xcode 14 and removed in
#     16; an archive built with it fails to link against a current SDK.
#   * The simulator is a different *platform*, not a different architecture. An
#     arm64 device slice and an arm64 simulator slice have the same `lipo` architecture
#     and different `LC_BUILD_VERSION` load commands, so they cannot be `lipo`-ed into one
#     file — they go into separate directories keyed by $(PLATFORM_NAME), which is exactly
#     the variable Xcode expands in LIBRARY_SEARCH_PATHS.
#
# ## Output
#
#   ios/Vendor/pjsip/include/                  headers (platform independent)
#   ios/Vendor/pjsip/lib/iphoneos/libpjproject.a         device,    arm64
#   ios/Vendor/pjsip/lib/iphonesimulator/libpjproject.a  simulator, arm64 + x86_64
#
# ONE archive per platform, not the twenty-odd pjproject emits. The Xcode project then
# carries a single `-lpjproject` and no link-order list that goes stale every time
# pjproject adds a module. `libtool -static` merges them, and because every input is a
# static archive the result is just their union.
#
# ## Usage
#
#   ios/scripts/build-pjsip-ios.sh                 # both platforms
#   ios/scripts/build-pjsip-ios.sh --device        # iphoneos only
#   ios/scripts/build-pjsip-ios.sh --simulator     # iphonesimulator only
#   ios/scripts/build-pjsip-ios.sh --clean         # discard the work tree first
#
# Nothing here downloads anything, in keeping with N-2: the sources are in third_party/.
set -euo pipefail

# ---------------------------------------------------------------- where things are
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_root="$(cd "$here/.." && pwd)"
repo_root="$(cd "$ios_root/.." && pwd)"

VENDOR_ROOT="${VENDOR_ROOT:-$repo_root/third_party}"
CONFIG_SITE_DIR="${CONFIG_SITE_DIR:-$ios_root/config}"
STAGE="${STAGE:-$repo_root/build/ios-native}"
OUT_DIR="${OUT_DIR:-$ios_root/Vendor/pjsip}"

# iOS 15 is the floor. CallKit, PushKit and AVAudioSession all predate it comfortably;
# what sets it is SwiftUI — `NavigationStack` and `.searchable` are iOS 16, and the app
# avoids both so that one more OS generation of devices can run it. Raising this later is
# a one-line change here and in the Xcode project, which read the same value.
MIN_IOS="${MIN_IOS:-15.0}"

# ---------------------------------------------------------------- argument handling
want_device=1
want_simulator=1
do_clean=0

# Which simulator architecture to build.
#
# The host's, by default. A simulator slice is only ever run on the machine that built
# it, so an Apple-silicon Mac needs arm64 and an Intel one needs x86_64 — building both
# doubles the slowest part of this script to produce a slice that will not be used.
#
# It also avoids a real failure: libvpx's x86 build assembles `vpx_ports/emms_mmx.asm`
# with yasm, and this script sets AS=clang for the ARM slices, so an x86_64 slice got
# clang invoked with yasm's flags —
#     clang: error: unknown argument: '-f'
#     clang: error: no such file or directory: 'macho64'
# (CI run 38868928491.) `--sim-arch x86_64` is still available and now checks for yasm
# in the preflight rather than failing twenty minutes in.
sim_arch="$(uname -m)"
case "$sim_arch" in arm64|x86_64) ;; *) sim_arch="arm64" ;; esac

for arg in "$@"; do
  case "$arg" in
    --device)     want_simulator=0 ;;
    --simulator)  want_device=0 ;;
    --sim-arch=*) sim_arch="${arg#--sim-arch=}" ;;
    --clean)      do_clean=1 ;;
    -h|--help)    sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- preflight
#
# Stated, and checked, rather than discovered three minutes into a configure. Every one of
# these produced a confusing failure the first time it was absent.
fail=0
if [ "$(uname -s)" != "Darwin" ]; then
  echo "::error::iOS cross-compilation needs macOS; this is $(uname -s)." >&2
  exit 1
fi
if ! xcode-select -p >/dev/null 2>&1 || ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
  echo "::error::no iPhoneOS SDK. This needs a full Xcode, not the Command Line Tools." >&2
  echo "  xcode-select -p  -> $(xcode-select -p 2>&1)" >&2
  echo "  Install Xcode, then: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
  exit 1
fi
for tool in autoconf libtool lipo; do
  command -v "$tool" >/dev/null 2>&1 || { echo "::error::$tool is not on PATH." >&2; fail=1; }
done
# automake is required ONLY when the vendored opus has no generated `configure` — the
# pinned tree does ship one, so this is a conditional gate rather than a blanket
# prerequisite. Demanding it unconditionally would turn a working machine away.
if [ ! -x "$VENDOR_ROOT/opus/configure" ]; then
  command -v automake >/dev/null 2>&1 || {
    echo "::error::opus ships no ./configure here and automake is not on PATH." >&2
    echo "  It would have to be generated: brew install autoconf automake libtool" >&2
    fail=1; }
fi
# libvpx's x86 paths assemble .asm with yasm (or nasm); its ARM paths do not need one.
if [ "$want_simulator" -eq 1 ] && [ "$sim_arch" = "x86_64" ]; then
  command -v yasm >/dev/null 2>&1 || command -v nasm >/dev/null 2>&1 || {
    echo "::error::an x86_64 simulator slice needs yasm or nasm for libvpx's x86 assembly." >&2
    echo "  brew install yasm   — or build the arm64 simulator slice instead:" >&2
    echo "  $(basename "$0") --sim-arch=arm64" >&2
    fail=1; }
fi

for tree in pjproject openssl opus libvpx; do
  [ -d "$VENDOR_ROOT/$tree" ] || { echo "::error::$VENDOR_ROOT/$tree is missing." >&2; fail=1; }
done
# The defect CI found on 2026-10-14, in the form it takes here: six of pjproject's seven
# `build/` directories survived a prune and the top-level one did not, and every makefile
# in the tree includes `build/os-auto.mak.in`. A clean checkout could not configure.
[ -f "$VENDOR_ROOT/pjproject/build/os-auto.mak.in" ] || {
  echo "::error::$VENDOR_ROOT/pjproject/build/os-auto.mak.in is missing - the vendored tree is incomplete." >&2
  echo "  Every pjproject makefile includes it; configure fails with a message that names neither." >&2
  fail=1; }
[ -f "$VENDOR_ROOT/libvpx/build/make/configure.sh" ] || {
  echo "::error::$VENDOR_ROOT/libvpx/build/make/configure.sh is missing - the vendored tree is incomplete." >&2
  fail=1; }
[ "$fail" -eq 0 ] || exit 1

[ "$do_clean" -eq 1 ] && rm -rf "${STAGE:?}"

jobs="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
echo "pjsip for iOS: min $MIN_IOS, $jobs jobs, stage $STAGE"
echo "Xcode: $(xcodebuild -version 2>/dev/null | head -1), $(xcode-select -p)"

# ---------------------------------------------------------------- helpers
#
# `current_hash` is lifted verbatim in construction from pjsip/build-native.sh: every
# file's path relative to the tree, a NUL, then its bytes, sorted bytewise. Location
# independent, no xargs argument limit, and a rename counts as a change. The variant that
# used `find -newer /dev/null` hashed every tree to the digest of the empty string on any
# machine that had rebooted, which made its cache permanent; this one does not.
current_hash() {
  ( cd "$VENDOR_ROOT/$1" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s' "${f#./}"; printf '\0'; cat "$f"
    done | shasum -a 256 | cut -d' ' -f1 )
}
stamp()      { echo "$work/.$1.stamp"; }
up_to_date() { [ -f "$(stamp "$1")" ] && [ "$(cat "$(stamp "$1")")" = "$(current_hash "$1")" ]; }
mark_done()  { current_hash "$1" > "$(stamp "$1")"; }
sync_tree()  { rm -rf "${work:?}/$1"; cp -R "$VENDOR_ROOT/$1" "$work/$1"; }

# Builds every dependency and pjproject for one (platform, arch) pair into its own prefix.
# `host_triple` is `aarch64-apple-darwin_ios` for the arm64 slices and NOT
# `arm-apple-darwin_ios`, which is what upstream's configure-iphone defaults to.
#
# The difference is not cosmetic. Opus's configure branches on `$host_cpu`, and `arm*`
# selects its 32-bit ARMv7 assembly — GNU-syntax `.S` files that Apple's integrated
# assembler rejects outright:
#
#     celt/arm/celt_pitch_xcorr_arm-gnu.S:17:1: error: unexpected token at start of statement
#     celt/arm/armopts-gnu.S:1:5: error: unknown directive
#
# (CI run 38866416894, after OpenSSL had built cleanly.) `aarch64*` does not match that
# branch, so the arm64 path is taken. pjproject's aconfigure handles `aarch64*`
# explicitly alongside `arm64*`, and both trees' config.sub accept the triple.
#
# The `_ios` suffix is load-bearing in the other direction: pjproject keys its iOS
# support off `*-apple-darwin_ios*`, and it also keeps the host triple different from
# the build triple on an Apple-silicon runner, which is what puts autoconf into
# cross-compilation mode instead of trying to RUN its iOS test binaries.
build_slice() {
  local platform="$1" arch="$2" host_triple="$3" openssl_target="$4" vpx_target="$5"
  local slice="$platform-$arch"

  work="$STAGE/$slice"
  local prefix="$STAGE/prefix/$slice"
  mkdir -p "$work" "$prefix"

  local sdk_path; sdk_path="$(xcrun --sdk "$platform" --show-sdk-path)"
  local cc;       cc="$(xcrun --sdk "$platform" --find clang)"
  local cxx;      cxx="$(xcrun --sdk "$platform" --find clang++)"

  # The minimum-version flag is spelled differently per platform and clang rejects the
  # wrong one. Getting this wrong on the simulator produces objects that link but crash
  # on dyld's availability check, which is a very long way from the cause.
  local min_flag
  if [ "$platform" = "iphonesimulator" ]; then
    min_flag="-mios-simulator-version-min=$MIN_IOS"
  else
    min_flag="-miphoneos-version-min=$MIN_IOS"
  fi

  echo
  echo "================================================================"
  echo "  $slice   sdk=$(basename "$sdk_path")"
  echo "================================================================"

  local common_cflags="-arch $arch -isysroot $sdk_path $min_flag -fPIC -O2"

  # ------------------------------------------------------------ OpenSSL (TLS)
  #
  # `no-tests` is not optional: third_party/openssl/test is pruned and the root build.info
  # guards SUBDIRS=test behind !$disabled{tests}, so a configure with tests enabled fails
  # on a directory that is not there. `no-shared` because an iOS app cannot load a dylib
  # it did not sign, and `no-asm` for the simulator because OpenSSL's arm64 assembly is
  # device-only.
  if ! up_to_date openssl; then
    echo "==> OpenSSL ($slice)"
    sync_tree openssl
    ( cd "$work/openssl"
      # CC is NOT set here, and that is the point of choosing the `-xcrun` targets: each
      # one is defined upstream as `CC => "xcrun -sdk iphoneos cc"` (or iphonesimulator),
      # so the SDK is selected by the target name. Exporting CC="$cc" would replace that
      # wrapper with a bare clang and quietly build against the host's headers.
      #
      # `no-tests` is not optional: third_party/openssl/test is pruned and the root
      # build.info guards SUBDIRS=test behind !$disabled{tests}. `no-shared` because an
      # iOS app cannot dlopen a dylib it did not sign. `install_sw`, not `install`, for
      # the same reason the Android build uses it - `install` wants the pruned docs.
      ./Configure "$openssl_target" no-shared no-tests no-ui-console \
        --prefix="$prefix" \
        -isysroot "$sdk_path" "$min_flag"
      make -j"$jobs"
      make install_sw )
    for f in lib/libssl.a lib/libcrypto.a include/openssl/ssl.h; do
      [ -f "$prefix/$f" ] || { echo "::error::OpenSSL produced no $prefix/$f" >&2; exit 1; }
    done
    mark_done openssl
  else
    echo "==> OpenSSL ($slice) up to date"
  fi

  # ------------------------------------------------------------ Opus
  if ! up_to_date opus; then
    echo "==> Opus ($slice)"
    sync_tree opus
    ( cd "$work/opus"
      [ -x ./configure ] || ./autogen.sh
      # AR must be Apple's libtool. GNU ar exists on a Mac with MacPorts or Homebrew on
      # PATH and silently produces an archive the Apple linker reads as "file was built for
      # archive which is not the architecture being linked" - an error that names the
      # architecture and not the archiver.
      ./configure --host="$host_triple" --prefix="$prefix" \
        --disable-shared --enable-static --disable-doc --disable-extra-programs \
        CC="$cc" CFLAGS="$common_cflags" LDFLAGS="-arch $arch -isysroot $sdk_path $min_flag" \
        AR="$(xcrun --sdk "$platform" --find ar)" \
        RANLIB="$(xcrun --sdk "$platform" --find ranlib)"
      make -j"$jobs"
      make install )
    [ -f "$prefix/lib/libopus.a" ] || { echo "::error::Opus produced no libopus.a" >&2; exit 1; }
    mark_done opus
  else
    echo "==> Opus ($slice) up to date"
  fi

  # ------------------------------------------------------------ libvpx (VP8)
  #
  # libvpx's configure defaults CC/AR to ${CROSS}gcc and ${CROSS}ar, neither of which
  # exists in an Apple toolchain, so they are stated. `--disable-unit-tests` and
  # `--disable-examples` are load-bearing and not tidiness: both link executables, and
  # nothing can link an executable for iOS without a signing identity.
  if ! up_to_date libvpx; then
    echo "==> libvpx ($slice)"
    sync_tree libvpx
    ( cd "$work/libvpx"

      # `arm64-iphonesimulator-gcc` does not exist upstream in v1.17.0 - `configure` lists
      # only x86 and x86_64 for the simulator (configure:148,177) - so it is added to THIS
      # COPY. third_party/ is untouched; architecture rule 12 hashes it.
      #
      # Adding the name is enough for the SDK, because `*-iphonesimulator-*` in
      # configure.sh:998 already selects the simulator sysroot for any architecture. It is
      # NOT enough for the assembler: the ARM branch of `case ${tgt_os}` (configure.sh:1163)
      # knows `darwin`, `android` and `linux` and has no `iphonesimulator`, so
      # `asm_conversion_cmd` - the ads2gas pass that turns libvpx's GNU-syntax .asm into
      # something Apple's assembler accepts - is never set. The NEON sources would then be
      # handed to clang verbatim and fail on the first `.text` directive.
      #
      # So NEON is disabled for that slice only, and the C paths are used. It costs encode
      # speed on a simulator, which is not a thing anybody measures, and nothing at all on
      # a device: the device slice is `arm64-darwin-gcc`, which configure.sh:926 maps to
      # the iPhoneOS SDK with the full NEON assembly path.
      vpx_extra=""
      if [ "$vpx_target" = "arm64-iphonesimulator-gcc" ]; then
        grep -q 'arm64-iphonesimulator-gcc' configure || \
          sed -i.bak 's|^all_platforms="${all_platforms} x86_64-iphonesimulator-gcc"|&\
all_platforms="${all_platforms} arm64-iphonesimulator-gcc"|' configure
        grep -q 'arm64-iphonesimulator-gcc' configure || {
          echo "::error::could not add arm64-iphonesimulator-gcc to libvpx's platform list" >&2
          exit 1; }
        vpx_extra="--disable-neon --disable-neon-asm --disable-runtime-cpu-detect"
      fi

      # The toolchain, exported rather than written as an assignment prefix.
      #
      # It was `${vpx_as:+AS="$vpx_as"}` in the prefix list, which does not work and
      # fails in a way worth remembering: the shell decides whether a token is an
      # assignment BEFORE expanding it, and a token starting with `${` is a command
      # word. So the expansion produced `AS=/…/clang` and the shell tried to execute a
      # program by that name —
      #     line 328: AS=/Applications/Xcode_26.6.app/…/clang: No such file or directory
      # (CI run 38871035997.) An `export` has no such subtlety.
      #
      # libvpx's configure defaults are CC=${CROSS}gcc and AR=${CROSS}ar, neither of
      # which exists in an Apple toolchain, so every tool is named.
      export CC="$cc" CXX="$cxx" LD="$cc"
      # Assigned before export, deliberately. `export AR="$(xcrun ...)"` reports
      # export's exit status and swallows xcrun's, so a toolchain lookup that failed
      # would leave AR empty and the build would carry on without an archiver
      # (ShellCheck SC2155).
      local vpx_ar vpx_strip vpx_nm vpx_ranlib
      vpx_ar="$(xcrun --sdk "$platform" --find ar)"
      vpx_strip="$(xcrun --sdk "$platform" --find strip)"
      vpx_nm="$(xcrun --sdk "$platform" --find nm)"
      vpx_ranlib="$(xcrun --sdk "$platform" --find ranlib)"
      export AR="$vpx_ar" STRIP="$vpx_strip" NM="$vpx_nm" RANLIB="$vpx_ranlib"
      export CFLAGS="$common_cflags" CXXFLAGS="$common_cflags"
      export LDFLAGS="-arch $arch -isysroot $sdk_path $min_flag"

      # AS is clang ONLY for the ARM slices, where libvpx's .asm goes through the
      # ads2gas conversion and then clang's integrated assembler. On x86 the .asm is
      # yasm syntax and libvpx must find yasm itself — forcing clang there gave
      #     clang: error: unknown argument: '-f'
      #     clang: error: no such file or directory: 'macho64'
      # on vpx_ports/emms_mmx.asm (CI run 38868928491).
      case "$arch" in arm64) export AS="$cc" ;; esac

      # `--disable-unit-tests` and `--disable-examples` are load-bearing rather than
      # tidiness: both link executables, and an iOS executable cannot be linked without
      # a signing identity.
      # shellcheck disable=SC2086  # vpx_extra is a deliberate word list
      ./configure --target="$vpx_target" --prefix="$prefix" \
        --disable-examples --disable-tools --disable-docs --disable-unit-tests \
        --enable-pic --enable-static --disable-shared --enable-vp8 --enable-vp9 \
        $vpx_extra
      make -j"$jobs"
      make install )
    # pjproject's vpx.c fails on a missing vpx/vpx_encoder.h, and the error lands inside a
    # generated .depend rather than at the top of the log. Named here instead.
    [ -f "$prefix/include/vpx/vpx_encoder.h" ] || {
      echo "::error::libvpx installed no headers - pjmedia's vpx.c will fail obscurely" >&2; exit 1; }
    [ -f "$prefix/lib/libvpx.a" ] || { echo "::error::libvpx produced no libvpx.a" >&2; exit 1; }
    mark_done libvpx
  else
    echo "==> libvpx ($slice) up to date"
  fi

  # ------------------------------------------------------------ pjproject
  echo "==> pjproject ($slice)"
  sync_tree pjproject
  ( cd "$work/pjproject"

    # The declared feature set, into the BUILD COPY. Never into third_party/, which
    # architecture rule 12 hashes.
    cp "$CONFIG_SITE_DIR/pj/config_site.h" pjlib/include/pj/config_site.h

    # configure-iphone exists upstream and is not used, deliberately. It hardcodes
    # /Applications/XCode.app, picks "the latest SDK installed" by sorting directory
    # names, and has no notion of the simulator at all - its ARCH defaults to arm64 and
    # its only min-version flag is -miphoneos-version-min, so a simulator build made
    # through it produces device objects with a simulator sysroot. Everything it would
    # export is set here instead, per slice, from xcrun.
    export CC="$cc" CXX="$cxx"
    export CFLAGS="$common_cflags -I$prefix/include"
    export CXXFLAGS="$CFLAGS"
    export LDFLAGS="-arch $arch -isysroot $sdk_path $min_flag -L$prefix/lib"
    # Apple's libtool, as configure-iphone itself sets. `ar` would work for most of the
    # tree and then fail on the one archive with duplicate member names.
    pj_libtool="$(xcrun --sdk "$platform" --find libtool)"
    export AR="$pj_libtool -static -o"
    export AR_FLAGS=" "
    export RANLIB="echo ranlib"
    # cpp searches /usr/include regardless of -isysroot, so the preprocessor is the
    # compiler. configure-iphone does the same and for the same reason.
    export CPP="$cc -arch $arch -E -isysroot $sdk_path"
    export LD="$cc"

    # The transcript is kept, because every assertion below is about a decision
    # configure made and "what did it decide" is otherwise 4,000 lines of config.log.
    ./aconfigure --host="$host_triple" --disable-sdl --disable-ffmpeg \
      --disable-v4l2 --disable-openh264 \
      --with-ssl="$prefix" --with-opus="$prefix" --with-vpx="$prefix" \
      2>&1 | tee configure-output.log

    # aconfigure does NOT fail when it cannot find OpenSSL, Opus or libvpx - it quietly
    # builds a stack without them, and config_site.h above says they are there. A client
    # that declares a codec it does not have offers it in SDP and then cannot encode,
    # which is worse than not offering it.
    #
    # Each check reads what configure WROTE rather than guessing a variable name. The
    # first version of this block invented `AC_NO_VPX_CODEC` and `AC_NO_OPUS_CODEC`,
    # neither of which pjproject defines, and failed a configure that had actually
    # succeeded (CI run 38867121440). The strings below are from aconfigure itself.
    echo "--- what configure decided ---"
    grep -E "OPUS (library|support)|VPX (is disabled|usability|prefix)|SSL|Using (OPUS|VPX|SSL) prefix" \
      configure-output.log | sed 's/^/    /' || true

    cfail=0

    # TLS: the generated config header is the authority, not the transcript.
    grep -qE '^#define[[:space:]]+PJ_HAS_SSL_SOCK[[:space:]]+1' pjlib/include/pj/compat/os_auto.h || {
      echo "::error::TLS is NOT enabled - aconfigure did not find OpenSSL at $prefix." >&2
      echo "  config_site.h sets PJSIP_HAS_TLS_TRANSPORT 1, so TLS accounts would fail on a device." >&2
      cfail=1; }

    # Opus: a DIFFERENT generated header. pjproject has five AC_CONFIG_HEADERS, and
    # autoconf routes each #define to whichever template carries the matching #undef —
    # PJ_HAS_SSL_SOCK to pjlib's os_auto.h, PJMEDIA_HAS_OPUS_CODEC to pjmedia-codec's
    # config_auto.h, which does not mention SSL and whose sibling does not mention Opus.
    grep -qE '^#define[[:space:]]+PJMEDIA_HAS_OPUS_CODEC[[:space:]]+1' \
      pjmedia/include/pjmedia-codec/config_auto.h || {
      echo "::error::Opus is NOT enabled - aconfigure did not find it at $prefix." >&2
      grep -E "OPUS" configure-output.log | tail -5 >&2 || true
      cfail=1; }

    # VPX: no such define. configure sets VPX_CFLAGS to the include path when it finds
    # it and to the empty string when it does not, and that variable is what the codec
    # Makefile compiles with.
    grep -qE '^VPX_CFLAGS = .*-I' pjmedia/build/os-auto.mak || {
      echo "::error::VPX is NOT enabled - aconfigure did not find libvpx at $prefix." >&2
      echo "  config_site.h sets PJMEDIA_HAS_VPX_CODEC 1 and the deployed server offers only" >&2
      echo "  VP8/VP9, so every video call would negotiate no video at all." >&2
      grep -E "VPX" configure-output.log | tail -5 >&2 || true
      cfail=1; }

    [ "$cfail" -eq 0 ] || exit 1

    # Stated for the log, because "which backends did this build actually get" is the
    # first question every media bug asks.
    echo "--- backends aconfigure selected ---"
    grep -E 'ac_pjmedia_(snd|video)=|PJMEDIA_VIDEO_DEV_HAS_(DARWIN|METAL)|VID_TOOLBOX' \
      build.mak pjmedia/build/os-auto.mak 2>/dev/null | sed 's/^/    /' || true

    make dep

    # `lib`, not `all`.
    #
    # `all` iterates $(DIRS), which is $(LIB_DIRS) plus pjsip-apps/build, and each
    # module's `all` also links its own test executable. Those fail for iOS, and not
    # because anything is wrong with the libraries:
    #
    #     "_OBJC_CLASS_$_NSNotificationCenter", referenced from:
    #          in libpjmedia-videodev-aarch64-apple-darwin_ios.a(darwin_dev.o)
    #     ld: symbol(s) not found for architecture arm64
    #
    # darwin_dev.m, metal_dev.m and coreaudio_dev.m are Objective-C and need Foundation,
    # AVFoundation and Metal on the link line. pjproject's own test binaries do not ask
    # for them; our app does, in the Xcode project's Frameworks phase. So the test
    # executables could not link even though every library they were linking is correct
    # — which is exactly what happened on CI run 38868156677, after all three
    # dependencies had been found and enabled.
    #
    # `lib` builds $(LIB_DIRS) only — pjlib, pjlib-util, pjnath, third_party, pjmedia
    # and pjsip (which carries pjsua-lib) — and links no executables at all. It is also
    # the faster target, since nothing here consumes the sample apps.
    #
    # The directories are created first because `all` does that and `lib` does not; the
    # archiver otherwise warns about a missing search path on every module.
    mkdir -p pjlib/lib pjlib-util/lib pjnath/lib pjmedia/lib pjsip/lib third_party/lib
    make -j"$jobs" lib )

  # Collect this slice's archives. pjproject names them with the host triple
  # (libpjsua-arm-apple-darwin_ios.a), which is why nothing downstream can hardcode a
  # list - they are found, not named.
  local slice_libs
  slice_libs="$STAGE/libs/$slice"
  rm -rf "$slice_libs"; mkdir -p "$slice_libs"
  find "$work/pjproject" -name '*.a' -path '*/lib/*' -exec cp {} "$slice_libs/" \;
  cp "$prefix"/lib/*.a "$slice_libs/" 2>/dev/null || true
  local count; count="$(find "$slice_libs" -name '*.a' | wc -l | tr -d ' ')"
  [ "$count" -gt 0 ] || { echo "::error::$slice produced no static libraries at all" >&2; exit 1; }
  echo "==> $slice: $count archives"
}

# ---------------------------------------------------------------- merge one platform
#
# Every slice of a platform becomes one libpjproject.a. Two steps, because the two are
# different operations and conflating them is how a simulator build ends up device-only:
# `libtool -static` unions archives of the SAME architecture, `lipo -create` combines
# architectures into one fat file.
merge_platform() {
  local platform="$1"; shift
  local archs=("$@")
  local out="$OUT_DIR/lib/$platform"
  mkdir -p "$out"

  local fat_inputs=()
  for arch in "${archs[@]}"; do
    local slice="$platform-$arch"
    local slice_libs
  slice_libs="$STAGE/libs/$slice"
    local merged="$STAGE/libs/libpjproject-$slice.a"
    rm -f "$merged"
    local archives=()
    while IFS= read -r a; do archives+=("$a"); done \
      < <(find "$slice_libs" -name '*.a' | LC_ALL=C sort)
    [ "${#archives[@]}" -gt 0 ] || { echo "::error::no archives to merge for $slice" >&2; exit 1; }
    "$(xcrun --sdk "$platform" --find libtool)" -static -o "$merged" "${archives[@]}" 2>/dev/null
    [ -f "$merged" ] || { echo "::error::libtool produced no archive for $slice" >&2; exit 1; }
    fat_inputs+=("$merged")
  done

  if [ "${#fat_inputs[@]}" -eq 1 ]; then
    cp "${fat_inputs[0]}" "$out/libpjproject.a"
  else
    lipo -create "${fat_inputs[@]}" -output "$out/libpjproject.a"
  fi

  echo "==> $platform: $out/libpjproject.a"
  lipo -info "$out/libpjproject.a" | sed 's/^/    /'
}

# ---------------------------------------------------------------- run
mkdir -p "$STAGE" "$OUT_DIR"

if [ "$want_device" -eq 1 ]; then
  # `arm64-darwin-gcc` is libvpx's iOS DEVICE target, not a macOS one: configure.sh:926
  # matches `arm*-darwin-*` and selects the iPhoneOS SDK. The macOS arm64 targets are the
  # numbered ones (arm64-darwin20-gcc and up), which is an easy and silent mistake to make.
  build_slice iphoneos arm64 aarch64-apple-darwin_ios ios64-xcrun arm64-darwin-gcc
  merge_platform iphoneos arm64
fi

if [ "$want_simulator" -eq 1 ]; then
  if [ "$sim_arch" = "x86_64" ]; then
    build_slice iphonesimulator x86_64 x86_64-apple-darwin_ios \
                iossimulator-x86_64-xcrun x86_64-iphonesimulator-gcc
  else
    build_slice iphonesimulator arm64 aarch64-apple-darwin_ios \
                iossimulator-arm64-xcrun arm64-iphonesimulator-gcc
  fi
  merge_platform iphonesimulator "$sim_arch"
fi

# ---------------------------------------------------------------- headers
#
# Taken from the device slice. pjproject's public headers carry no per-architecture
# content except `pj/compat/*_auto.h`, which aconfigure generates - and those differ
# between device and simulator only in fields nothing in this app reads. Taking them from
# one slice and saying so is better than merging two trees and pretending they were equal.
if [ "$want_device" -eq 1 ]; then
  header_src="$STAGE/iphoneos-arm64"
else
  header_src="$STAGE/iphonesimulator-$sim_arch"
fi
rm -rf "$OUT_DIR/include"; mkdir -p "$OUT_DIR/include"
for m in pjlib pjlib-util pjnath pjmedia pjsip; do
  [ -d "$header_src/pjproject/$m/include" ] && cp -R "$header_src/pjproject/$m/include/." "$OUT_DIR/include/"
done
[ -f "$OUT_DIR/include/pjsua-lib/pjsua.h" ] || {
  echo "::error::no pjsua.h in $OUT_DIR/include - the bridge will not compile" >&2; exit 1; }

cat > "$OUT_DIR/BUILD-INFO.txt" <<INFO
Built $(date -u '+%Y-%m-%dT%H:%M:%SZ') on $(sw_vers -productName) $(sw_vers -productVersion)
Xcode        $(xcodebuild -version 2>/dev/null | head -1)
Min iOS      $MIN_IOS
pjproject    $(grep -E '^export PJ_VERSION_(MAJOR|MINOR)' "$VENDOR_ROOT/pjproject/version.mak" | sed 's/.*:= *//' | paste -sd. -)
Codecs       G.711, G.722, GSM, iLBC(CoreAudio), Opus, VP8/VP9, H.264(VideoToolbox)
Not built    Lyra - the TFLite closure has not been cross-compiled for iOS
INFO

echo
echo "================================================================"
echo "  done"
echo "================================================================"
find "$OUT_DIR/lib" -name '*.a' -exec ls -lh {} \; | awk '{print "  " $NF " (" $5 ")"}'
echo "  headers: $(find "$OUT_DIR/include" -name '*.h' | wc -l | tr -d ' ')"
