#!/usr/bin/env bash
#
# The per-ABI native build, invoked by pjsip/CMakeLists.txt (which AGP invokes once per ABI).
#
# ## Ported, not written
#
# Every flag here comes from `.github/workflows/build-pjsip.yml`, which has a GREEN run
# behind it — run 34317978694, all three ABIs, 2m46s-3m23s each. Three failure modes are
# encoded that this repository has already paid for, and each is commented where it bites:
# the host `ar` leaking into a cross-compile, a configure that accepts "no TLS, no Opus"
# silently, and libvpx's assembler defaulting to the host's on 32-bit ARM only.
#
# Nothing here downloads anything. The sources are in third_party/ (N-2) and the toolchain
# is the NDK CMake resolved (§2.1.1).
#
# ## Why each tree is copied first
#
# autotools writes config.log, .depend, build.mak and every object file into the source
# tree. third_party/ is read-only — architecture rule 12 hashes it and N-7 forbids editing
# it without a patch — and three ABIs cannot share one configured tree anyway. So each
# build gets its own copy under $STAGE/$ABI.
set -euo pipefail

: "${ANDROID_NDK_ROOT:?}" "${ANDROID_API:?}" "${ABI:?}" "${HOST_TRIPLE:?}" "${SYSROOT_TRIPLE:?}"
: "${OPENSSL_TARGET:?}" "${VPX_TARGET:?}" "${VENDOR_ROOT:?}" "${CONFIG_SITE_DIR:?}"
: "${STAGE:?}" "${PREFIX:?}" "${OUT_DIR:?}"

# ---------------------------------------------------------------- the SWIG both stages use
#
# THIS IS A CORRECTNESS REQUIREMENT, not a convenience. There are TWO swig invocations in
# this build and they must be the same binary:
#
#   stage 1  GeneratePjsua2Bindings runs swig to emit the Java   (org.pjsip.pjsua2.*)
#   stage 2  pjproject's swig/java/Makefile runs swig to emit    (pjsua2_wrap.cpp)
#            the C++ JNI wrapper that the .so is built from
#
# The .so exports `Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java the FIRST
# invocation produced. Two different swig versions therefore produce a Java class and a
# native library that disagree about symbol names — which is exactly the drift N-13 exists
# to make structurally impossible, reintroduced through the back door of $PATH.
#
# Stage 1 takes its swig as a task property. Stage 2 gets it from $PATH, because it is
# upstream's Makefile and not ours. So the property is pushed onto $PATH here, and the
# version is asserted, rather than trusting that whatever the Gradle daemon happened to
# inherit is the same tool.
if [ -n "${SWIG:-}" ]; then
  swig_dir="$(cd "$(dirname "$SWIG")" && pwd)"
  export PATH="$swig_dir:$PATH"
fi
swig_version="$(swig -version 2>/dev/null | sed -n 's/.*SWIG Version \([0-9.]*\).*/\1/p')"
expected_swig="${EXPECTED_SWIG_VERSION:-4.2.0}"
if [ "$swig_version" != "$expected_swig" ]; then
  echo "::error::stage 2 would use swig $swig_version, and this build is pinned to $expected_swig." >&2
  echo "  swig on PATH: $(command -v swig || echo 'not found')" >&2
  echo "  The .so exports symbols named after the Java stage 1 generated, so a different" >&2
  echo "  swig here produces a library whose JNI names do not match the bindings." >&2
  echo "  Pass -Ppjsip.swig=/path/to/swig-$expected_swig/bin/swig." >&2
  exit 1
fi
swig_lib="$(swig -swiglib 2>/dev/null)"
if [ ! -d "$swig_lib/java" ]; then
  echo "::error::swig at $(command -v swig) has no Java typemaps ($swig_lib/java is absent)." >&2
  echo "  Some distributions package them separately - MacPorts: port install swig-java." >&2
  echo "  Without them swig fails on java.swg and arrays_java.i, which reads like a corrupt" >&2
  echo "  source tree rather than a missing package." >&2
  exit 1
fi
echo "stage 2 swig: $(command -v swig) ($swig_version), typemaps at $swig_lib/java"

work="$STAGE/$ABI"
prefix="$PREFIX/$ABI"
mkdir -p "$work" "$prefix" "$OUT_DIR"

# The NDK's toolchain, on PATH, ahead of anything the host provides.
toolchain_bin="$(dirname "$(find "$ANDROID_NDK_ROOT" -path '*/toolchains/llvm/prebuilt/*/bin/clang' | head -1)")"
[ -n "$toolchain_bin" ] || { echo "no clang under $ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" >&2; exit 1; }
export PATH="$toolchain_bin:$PATH"

jobs="$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) )"

# A marker per tree, so a re-run does not redo an hour of work. The marker records the
# vendored tree's hash, so a bumped or patched dependency rebuilds and an unchanged one
# does not — the same input-tracking Gradle does, at the granularity Gradle cannot see.
stamp() { echo "$work/.$1.stamp"; }
current_hash() { find "$VENDOR_ROOT/$1" -type f -newer /dev/null | LC_ALL=C sort | xargs shasum -a 256 2>/dev/null | shasum -a 256 | cut -d' ' -f1; }
up_to_date() { [ -f "$(stamp "$1")" ] && [ "$(cat "$(stamp "$1")")" = "$(current_hash "$1")" ]; }
mark_done() { current_hash "$1" > "$(stamp "$1")"; }

sync_tree() {
  local name="$1"
  rm -rf "${work:?}/$name"
  cp -R "$VENDOR_ROOT/$name" "$work/$name"
}

# ---------------------------------------------------------------- OpenSSL (TLS, DoD 13)
#
# Without this, configure-android finds no OpenSSL and silently builds a stack with TLS
# disabled. That does not fail the build — it fails DoD 13 at run time, on a handset, on a
# TLS account. `no-tests` is not optional: third_party/openssl/test is pruned, and the root
# build.info guards SUBDIRS=test behind !$disabled{tests}. tools/vendor/verify-openssl-prune.sh
# asserts both halves of that.
if ! up_to_date openssl; then
  echo "==> OpenSSL ($ABI)"
  sync_tree openssl
  ( cd "$work/openssl"
    ./Configure "$OPENSSL_TARGET" "-D__ANDROID_API__=$ANDROID_API" \
      --prefix="$prefix" no-shared no-tests
    make -j"$jobs" && make install_sw )
  mark_done openssl
fi

# ---------------------------------------------------------------- Opus (wideband audio)
#
# AR/RANLIB/NM/STRIP are named explicitly because the NDK removed the triple-prefixed
# binutils. Left to itself autotools looks for `aarch64-linux-android-ar`, does not find it,
# and falls back to the HOST `ar` — which produces an x86_64 archive that then fails to link
# into an arm64 .so, or worse, links and misbehaves. Every LLVM tool is multi-target, so the
# unprefixed name is the correct one.
if ! up_to_date opus; then
  echo "==> Opus ($ABI)"
  sync_tree opus
  ( cd "$work/opus"
    [ -x ./configure ] || ./autogen.sh
    ./configure --host="$HOST_TRIPLE" \
      CC="$HOST_TRIPLE$ANDROID_API-clang" \
      AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip \
      --prefix="$prefix" --disable-shared --enable-static --disable-doc --disable-extra-programs
    make -j"$jobs" && make install )
  mark_done opus
fi

# ---------------------------------------------------------------- libvpx (VP8 — the only
# video codec both ends can negotiate; the deployed FreeSWITCH has no H.264 at all)
if ! up_to_date libvpx; then
  echo "==> libvpx ($ABI)"
  sync_tree libvpx
  ( cd "$work/libvpx"
    # The same trap as Opus, in libvpx's own words: its configure defaults are CC=${CROSS}gcc
    # and AR=${CROSS}ar (build/make/configure.sh), and a modern NDK ships neither. CROSS is
    # EMPTIED rather than unset, because the defaults interpolate it and a stale value would
    # put the prefix back on the names below.
    export CROSS=""
    export CC="$HOST_TRIPLE$ANDROID_API-clang"
    export CXX="$HOST_TRIPLE$ANDROID_API-clang++"
    export LD="$CC"
    export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip

    # AS is the same trap one tool further on, and ON 32-BIT ARM ONLY.
    #
    # setup_gnu_toolchain defaults it to AS=${AS:-${CROSS}as}, so emptying CROSS resolves it
    # to a bare `as` — the host's x86_64 assembler — which meets libvpx's own -march=armv7-a
    # and dies with "invalid -march= option". `$CC -c` is libvpx's own remedy: configure.sh
    # writes exactly that for its Windows ARM targets, and the -c is load-bearing because the
    # rule is `$(AS) $(ASFLAGS) -o $@ $<` and supplies none of its own.
    #
    # x86 must NOT get it: there the assembler is yasm and ASFLAGS are yasm's, so clang is
    # handed `-f elf64` and answers "unknown argument: '-f'". Setting AS unconditionally
    # traded a broken armeabi-v7a for a broken x86_64, which is why this is a case.
    case "$VPX_TARGET" in
      arm*) export AS="$CC -c" ;;
    esac

    ./configure --target="$VPX_TARGET" --prefix="$prefix" \
      --disable-examples --disable-tools --disable-docs --disable-unit-tests \
      --enable-pic --enable-static --disable-shared --enable-vp8 --enable-vp9
    make -j"$jobs" && make install )

  # vpx.c fails on a missing vpx/vpx_encoder.h, and the error text lands IN the generated
  # .depend as a "missing separator" thousands of lines in — which is what killed all three
  # ABIs on run #13. Assert the headers landed, here, where the message is readable.
  [ -f "$prefix/include/vpx/vpx_encoder.h" ] || {
    echo "::error::libvpx installed no headers — vpx.c will fail the same way again" >&2; exit 1; }
  mark_done libvpx
fi

# ---------------------------------------------------------------- Lyra (ADR-008, Exit A)
#
# Nineteen vendored trees become one archive, `$prefix/lib/liblyra.a`, in the layout
# pjproject's `--with-lyra=DIR` reads. pjsip/lyra/CMakeLists.txt is the build; this stage
# stages the trees, drives it with the NDK toolchain file, and installs the prefix.
#
# Every tree is COPIED first, like the autotools ones above and for the same reason: two
# of them write into their own source directory at configure time (TFLite's eigen.cmake
# `file(WRITE ...)`s into Eigen), and third_party/ is hashed by rule 12.
#
# The stamp covers all nineteen trees plus the CMake file itself: a re-run with nothing
# changed skips the twenty-five minutes TensorFlow Lite costs; a bumped tree or an edited
# CMakeLists.txt rebuilds. CI has no persistent stage, so CI pays it every run — see
# docs/native-dependencies.md §3.4 for the cache that would remove that.
lyra_trees=(lyra tensorflow abseil-cpp cpuinfo eigen farmhash fft2d flatbuffers FP16 FXdiv
            gemmlowp neon2sse psimd pthreadpool ruy xnnpack audio_dsp glog gulrak-filesystem)
lyra_cmake_dir="${LYRA_CMAKE_DIR:-$CONFIG_SITE_DIR/../lyra}"
lyra_hash() {
  { for t in "${lyra_trees[@]}"; do current_hash "$t"; done
    shasum -a 256 "$lyra_cmake_dir/CMakeLists.txt"; } | shasum -a 256 | cut -d' ' -f1
}
lyra_stamp="$work/.lyra.stamp"
if [ -f "$lyra_stamp" ] && [ "$(cat "$lyra_stamp")" = "$(lyra_hash)" ] && [ -f "$prefix/lib/liblyra.a" ]; then
  echo "==> Lyra ($ABI): up to date"
else
  echo "==> Lyra ($ABI): ${#lyra_trees[@]} trees"
  for t in "${lyra_trees[@]}"; do
    [ -d "$VENDOR_ROOT/$t" ] || { echo "::error::third_party/$t is not vendored — run tools/vendor/vendor.sh" >&2; exit 1; }
    sync_tree "$t"
  done
  lyra_build="$work/lyra-build"
  rm -rf "$lyra_build"
  mkdir -p "$lyra_build"
  # Ninja when the host has it, Make otherwise: 1,100 compile steps are the same either
  # way, and CI runners are not promised Ninja.
  lyra_gen="Unix Makefiles"
  command -v ninja >/dev/null 2>&1 && lyra_gen="Ninja"
  "${CMAKE:-cmake}" -S "$lyra_cmake_dir" -B "$lyra_build" -G "$lyra_gen" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DLYRA_VENDOR="$work" \
    -DCMAKE_INSTALL_PREFIX="$prefix"
  "${CMAKE:-cmake}" --build "$lyra_build" --parallel "$jobs"
  "${CMAKE:-cmake}" --install "$lyra_build"
  # Fail here, with the file named, rather than in configure-android's "lyra usability
  # ... no" — which does not stop the build, it builds a stack without the codec.
  for f in lib/liblyra.a lyra_encoder.h lyra_decoder.h lyra/lyra_config.pb.h \
           include/com_google_absl/absl/types/span.h include/gulrak_filesystem/include/ghc/filesystem.hpp \
           model_coeffs/lyragan.tflite; do
    [ -f "$prefix/$f" ] || { echo "::error::the Lyra build produced no $prefix/$f" >&2; exit 1; }
  done
  lyra_hash > "$lyra_stamp"
fi

# ---------------------------------------------------------------- pjproject
echo "==> pjproject ($ABI)"
sync_tree pjproject
cd "$work/pjproject"

# The declared feature set (N-8), from the ONE file both stages read. Copied into the build
# copy — never into third_party/, which rule 12 hashes.
cp "$CONFIG_SITE_DIR/pj/config_site.h" pjlib/include/pj/config_site.h

# 16 KB pages: r27 links its own output aligned, but pjproject drives its link line itself,
# so the flag is stated rather than assumed. An unaligned .so is one Android 15 refuses to
# load on a 16 KB device, and Play rejects the upload rather than warning.
export LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384"
export ANDROID_NDK_ROOT

# THE ABI. `configure-android` reads TARGET_ABI and defaults to arm64 without it, so every
# ABI configured as aarch64: libopus.a and libvpx.a were built correctly for armeabi-v7a and
# the wrapper then refused them — `libopus.a(bands.o) is incompatible with aarch64linux`,
# which reads like a broken cross-compile and is actually a missing environment variable.
#
# The green workflow sets it as step `env:` on both the pjproject and the swig steps
# (.github/workflows/build-pjsip.yml), which is easy to miss when porting a workflow into a
# script, because a step's env does not look like part of the command.
export TARGET_ABI="$ABI"

# THE API LEVEL. Left unset, `configure-android` derives APP_PLATFORM from the NDK's own
# minimum (r27c: 21, which its script then raises to 23) — not from this app. OpenSSL, Opus
# and libvpx above are built with `$ANDROID_API` (26, the app's minSdk) and never used a
# symbol newer than 23, so a pjproject compiled for 23 linked against them by luck. The
# Lyra closure is the first library that does not: `strtod_l` and the `_FORTIFY_SOURCE`
# `__*_chk` functions arrive in bionic at 24-26, and configure's Lyra link test failed on
# exactly those. One stack, one API level.
export APP_PLATFORM="$ANDROID_API"

./configure-android --use-ndk-cflags \
  --with-ssl="$prefix" --with-opus="$prefix" --with-vpx="$prefix" --with-lyra="$prefix"

# configure-android does NOT fail when it cannot find OpenSSL, Opus or libvpx — it quietly
# builds a stack without them. A build that silently drops TLS or wideband audio is worse
# than no build, so it stops here instead of on a handset.
fail=0
grep -qE '^#define PJ_HAS_SSL_SOCK[[:space:]]+1' pjlib/include/pj/compat/os_auto.h 2>/dev/null \
  || grep -qiE 'ssl.*(enabled|found)' config.log \
  || { echo "::error::TLS is NOT enabled — configure did not find OpenSSL. See --with-ssl." >&2; fail=1; }
grep -qi 'opus.*disabled\.\.\. *no\|Checking opus usability\.\.\. yes' config.log \
  || grep -qi 'opus' config.log \
  || { echo "::error::Opus is NOT enabled — configure did not find it. See --with-opus." >&2; fail=1; }
if grep -qi 'Checking if VPX is disabled\.\.\. yes' config.log; then
  echo "::error::VPX is NOT enabled — configure did not find libvpx. See --with-vpx." >&2
  echo "::error::config_site.h sets PJMEDIA_HAS_VPX_CODEC 1, so make dep will corrupt .depend." >&2
  fail=1
fi
# The same silence for Lyra: a failed link test is "checking lyra usability... no" and a
# build that goes on without the codec, while config_site.h says it is there (N-8). The
# link test is the whole closure in one archive; if it fails, the archive is the place to
# look, and config.log has the linker's actual complaint.
# Read off what configure WROTE, not what it printed: os-auto.mak carries
# `AC_NO_LYRA_CODEC=1` when the link test failed and an empty value when it passed, and
# that variable is the one the codec Makefile branches on. (config.log splits "checking"
# and "result:" across lines, and the first version of this check grepped for the
# one-line form and failed on a build that had actually succeeded.)
if ! grep -qE '^AC_NO_LYRA_CODEC=$' pjmedia/build/os-auto.mak; then
  echo "::error::Lyra is NOT enabled — configure's link test against $prefix/lib/liblyra.a failed." >&2
  echo "::error::config_site.h sets PJMEDIA_HAS_LYRA_CODEC 1; the codec would be declared and absent." >&2
  grep -n -A14 'checking lyra usability' config.log | tail -30 >&2 || true
  fail=1
fi
[ "$fail" -eq 0 ] || exit 1

make dep && make clean && make -j"$jobs"

# ---------------------------------------------------------------- the JNI wrapper
#
# `make` here builds libpjsua2.so — the wrapper whose exported symbol names must match the
# Java stage 1 generated. It writes into pjsip-apps/src/swig/java/android/… , a directory
# the vendored tree does not carry (it is a pruned sample app) and which this Makefile
# creates itself.
#
# Two details are copied from the green run rather than reasoned about, because both were
# got wrong first and the failure was a wall of undefined symbols four minutes in.
#
#   1. `make` runs from `pjsip-apps/src/swig`, NOT from `swig/java`. The parent delegates
#      with `$(MAKE) -C java`, and the delegation is what supplies the flags the java
#      Makefile reads out of build.mak.
#   2. LDFLAGS is REPLACED rather than inherited — see 4.
#   3. The target is `java`, not the default. `swig/Makefile:5` sets `LANG = java csharp`
#      for Android, so a bare `make` also builds the C# bindings — which this project does
#      not want, and whose tree is pruned, so the build got as far as
#      `make[3]: *** [Makefile:27: csharp] Error 2` AFTER linking the Java wrapper
#      successfully. Naming the target is better than restoring a binding nobody consumes.
#
#      Note what this one says about `tools/vendor/verify-prune.sh`: it greps build files
#      for pruned PATHS, and `LANG = java csharp` is a bare word. A checker that reads
#      paths cannot see a target name, and this is the class of prune it will not catch.
#   4. LDFLAGS carries `-L$prefix/lib`, and this is the one that had to be worked out
#      rather than copied. The wrapper link failed on every `opus_*` and `vpx_*` symbol:
#      the static libraries are in the prefix, `configure-android` found them, and they
#      were still not on the wrapper's link line. `java/Makefile:167` assembles
#      `MY_LDFLAGS := $(PJ_LDXXFLAGS) $(PJ_LDXXLIBS) … $(LDFLAGS)`, so LDFLAGS is the
#      documented seam for exactly this — and the alignment flag is dropped here because
#      the pjproject link above is where that belongs. `-L` alone did not fix it, so the
#      archives are named too, and the group above prints what configure actually wrote
#      into build.mak so the next failure of this shape is one line to diagnose.
echo "::group::what configure actually put in build.mak for the wrapper link"
grep -E "^(PJ_LDXXLIBS|APP_THIRD_PARTY_LIBS|APP_LDLIBS)" build.mak || true
ls -la "$prefix/lib" || true
echo "::endgroup::"

# `-L` alone was not enough: the prefix was on the search path and the archives were still
# not pulled in, so the libraries are NAMED here as well. LDFLAGS is last in MY_LDFLAGS,
# which is the correct position for static archives — after the objects that reference them.
# `-llyra` joins them for the same reason, and `-llog` after it: glog inside the archive
# logs through __android_log_write, which nothing else on this link line pulls in.
( export LDFLAGS="-L$prefix/lib -lopus -lvpx -llyra -llog"; cd pjsip-apps/src/swig && make java )

jni_so="$work/pjproject/pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/$ABI/libpjsua2.so"
[ -f "$jni_so" ] || { echo "::error::the SWIG Java build produced no $jni_so" >&2
  find "$work/pjproject" -name 'libpjsua2.so' | sed 's/^/  also present: /' >&2; exit 1; }

# Prove it is the JNI wrapper before it goes anywhere near an APK. pjproject builds more
# than one libpjsua2.so, identical in name, size and alignment, and only one exports
# Java_org_pjsip_*. Taking the wrong one produces an AAR that looks perfect and fails with
# exactly the UnsatisfiedLinkError this whole change exists to remove.
LC_ALL=C grep -aq "Java_org_pjsip_pjsua2_pjsua2JNI_swig_1module_1init" "$jni_so" \
  || { echo "::error::$jni_so exports no pjsua2JNI symbols — wrong wrapper" >&2; exit 1; }

# libc++_shared.so travels with it. libpjsua2.so links against the NDK's shared C++ runtime,
# so without it System.loadLibrary("pjsua2") fails with "library libc++_shared.so not found"
# — an APK that installs and cannot load. This is the second half of the expected .so set
# the library inventory asserts (N-6).
cxx_so="$(find "$ANDROID_NDK_ROOT" -path "*/sysroot/usr/lib/$SYSROOT_TRIPLE/libc++_shared.so" -print -quit)"
[ -n "$cxx_so" ] || { echo "::error::no libc++_shared.so for $SYSROOT_TRIPLE — the APK would not load" >&2; exit 1; }

cp -f "$jni_so" "$cxx_so" "$OUT_DIR/"

# DWARF off, symbol table kept. The NDK toolchain compiles everything with -g, and with the
# Lyra closure linked in that is ~80 MB of debug sections in a library whose loadable code
# is 21 MB — the APK went from 45 MB to 146 MB. AGP would strip at packaging, but only
# with an NDK configured on the app module, which this project keeps on :pjsip alone.
# `--strip-debug` rather than `--strip-all`: .symtab stays, so a native tombstone still
# names the frames, which is what turned the FinalizerDaemon abort into a fix.
strip_bin="$(command -v llvm-strip || true)"
if [ -n "$strip_bin" ]; then
  "$strip_bin" --strip-debug "$OUT_DIR/libpjsua2.so"
  echo "stripped debug sections: $(du -h "$OUT_DIR/libpjsua2.so" | cut -f1) libpjsua2.so"
else
  echo "warning: llvm-strip not on PATH — libpjsua2.so ships its debug sections" >&2
fi

# 16 KB alignment, asserted rather than printed. This used to be a step that dumped the LOAD
# headers and could not fail.
readelf_bin="$(command -v llvm-readelf || true)"
if [ -n "$readelf_bin" ]; then
  if "$readelf_bin" -lW "$OUT_DIR/libpjsua2.so" | awk '$1 == "LOAD" { print $NF }' \
       | grep -qvE '^0x(4000|8000|10000)$'; then
    echo "::error::libpjsua2.so ($ABI) has a LOAD segment aligned under 16 KB" >&2
    exit 1
  fi
else
  echo "warning: llvm-readelf not on PATH — 16 KB alignment was NOT verified for $ABI" >&2
fi

# N-11 diagnostics. Two CI runs of identical native input produce a libpjsua2.so of the
# SAME SIZE and a DIFFERENT hash, so something fixed-width varies. These three lines are
# what turn "probably a build id or a timestamp" into a named cause; they cost milliseconds
# and they are the difference between N-11 being answered and being guessed at.
if [ -n "$readelf_bin" ]; then
  echo "::group::N-11 — what varies between builds"
  echo "  build-id: $("$readelf_bin" -n "$OUT_DIR/libpjsua2.so" 2>/dev/null | sed -n 's/.*Build ID: *//p' | head -1)"
  # A literal date or time baked in by __DATE__/__TIME__ anywhere in the image.
  LC_ALL=C grep -aoE '[A-Z][a-z]{2} [ 0-9][0-9] [0-9]{4}|[0-9]{2}:[0-9]{2}:[0-9]{2}' \
    "$OUT_DIR/libpjsua2.so" 2>/dev/null | sort -u | head -5 | sed 's/^/  embedded: /'
  echo "::endgroup::"
fi

echo "==> $ABI: libpjsua2.so + libc++_shared.so in $OUT_DIR"
