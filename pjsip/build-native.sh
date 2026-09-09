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

./configure-android --use-ndk-cflags \
  --with-ssl="$prefix" --with-opus="$prefix" --with-vpx="$prefix"

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
#   2. LDFLAGS is UNSET first. It carried `-Wl,-z,max-page-size=16384` for the pjproject
#      link above; the java Makefile appends `$(LDFLAGS)` to a line it has already
#      assembled, and in the green workflow that step was a separate shell where the
#      export did not reach. Alignment is already handled by the pjproject link.
#   3. The target is `java`, not the default. `swig/Makefile:5` sets `LANG = java csharp`
#      for Android, so a bare `make` also builds the C# bindings — which this project does
#      not want, and whose tree is pruned, so the build got as far as
#      `make[3]: *** [Makefile:27: csharp] Error 2` AFTER linking the Java wrapper
#      successfully. Naming the target is better than restoring a binding nobody consumes.
#
#      Note what this one says about `tools/vendor/verify-prune.sh`: it greps build files
#      for pruned PATHS, and `LANG = java csharp` is a bare word. A checker that reads
#      paths cannot see a target name, and this is the class of prune it will not catch.
( unset LDFLAGS; cd pjsip-apps/src/swig && make java )

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

echo "==> $ABI: libpjsua2.so + libc++_shared.so in $OUT_DIR"
