#!/usr/bin/env bash
# Build the vendored Lyra closure (third_party/) for the HOST that runs FreeSWITCH, into
# one archive, build/lyra-prefix/lib/liblyra.a, plus the headers and model files the
# module needs — the same CMake file the Android build uses (pjsip/lyra/CMakeLists.txt),
# without the NDK toolchain.
#
# Why a staged copy: two of the vendored trees write into their own source directory at
# configure time (TensorFlow Lite's eigen.cmake, XNNPACK's generated sources), and
# third_party/ is read-only by the repo's architecture rule 12. So every tree is copied
# under build/stage first — exactly what pjsip/build-native.sh's sync_tree does.
#
# Usage: scripts/build-lyra.sh [--clean]
#   CMAKE=/path/to/cmake      cmake >= 3.16 and < 4.0 (FP16/psimd/flatbuffers still say 2.8.12)
#   JOBS=n                    parallelism (default: number of cores)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$here/.." && pwd)"
vendor="$repo/third_party"
lyra_cmake_dir="$repo/pjsip/lyra"
build="$here/build"
stage="$build/stage"
prefix="$build/lyra-prefix"
lyra_build="$build/lyra-build"
jobs="${JOBS:-$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) )}"
CMAKE="${CMAKE:-cmake}"

if [ "${1:-}" = "--clean" ]; then
  rm -rf "$stage" "$lyra_build" "$prefix"
fi

# The nineteen trees pjsip/build-native.sh stages, verbatim.
trees=(lyra tensorflow abseil-cpp cpuinfo eigen farmhash fft2d flatbuffers FP16 FXdiv
       gemmlowp neon2sse psimd pthreadpool ruy xnnpack audio_dsp glog gulrak-filesystem)

hash_inputs() {
  { for t in "${trees[@]}"; do
      find "$vendor/$t" -type f | LC_ALL=C sort | xargs shasum -a 256 2>/dev/null | shasum -a 256 | cut -d' ' -f1
    done
    shasum -a 256 "$lyra_cmake_dir/CMakeLists.txt"; } | shasum -a 256 | cut -d' ' -f1
}
stamp="$build/.lyra.stamp"
if [ -f "$stamp" ] && [ -f "$prefix/lib/liblyra.a" ] && [ "$(cat "$stamp")" = "$(hash_inputs)" ]; then
  echo "==> Lyra (host): up to date at $prefix"
  exit 0
fi

echo "==> Lyra (host): staging ${#trees[@]} trees under $stage"
mkdir -p "$stage"
for t in "${trees[@]}"; do
  [ -d "$vendor/$t" ] || { echo "error: third_party/$t is not vendored — run tools/vendor/vendor.sh" >&2; exit 1; }
  rm -rf "${stage:?}/$t"
  cp -R "$vendor/$t" "$stage/$t"
done

gen="Unix Makefiles"
command -v ninja >/dev/null 2>&1 && gen="Ninja"

# The shared CMake file (pjsip/lyra/CMakeLists.txt) merges the closure into one archive
# with an `ar -M` MRI script — a GNU/llvm-ar feature. macOS's default /usr/bin/ar is BSD
# ar and has no -M, so on this host CMAKE_AR must point at an llvm-ar (the Android build
# uses the NDK's). Prefer an explicit LYRA_AR, then llvm-ar on PATH, then MacPorts'.
lyra_ar="${LYRA_AR:-}"
if [ -z "$lyra_ar" ]; then
  for cand in llvm-ar /opt/local/bin/llvm-ar /opt/local/bin/llvm-ar-mp-19 \
              /opt/local/libexec/llvm-19/bin/llvm-ar /usr/local/opt/llvm/bin/llvm-ar; do
    if command -v "$cand" >/dev/null 2>&1 || [ -x "$cand" ]; then lyra_ar="$cand"; break; fi
  done
fi
ar_arg=()
if [ -n "$lyra_ar" ]; then
  ar_arg=(-DCMAKE_AR="$lyra_ar")
  echo "==> Lyra (host): using ar = $lyra_ar (MRI-script merge)"
  # Keep ranlib in the same family so archive formats do not get mixed.
  lyra_ranlib="${lyra_ar/llvm-ar/llvm-ranlib}"
  [ -x "$lyra_ranlib" ] || command -v "$lyra_ranlib" >/dev/null 2>&1 || lyra_ranlib=""
  [ -n "$lyra_ranlib" ] && ar_arg+=(-DCMAKE_RANLIB="$lyra_ranlib")
else
  echo "::warning:: no llvm-ar found; the final archive merge needs one on macOS. Install with 'port install llvm-19' or set LYRA_AR." >&2
fi

# On macOS force a single architecture (the host's). Some sub-builds (flatbuffers) emit a
# Mach-O universal archive by default, which llvm-ar cannot merge with `addlib`. The module
# only ever runs on this host's FreeSWITCH, so a thin archive is what we want anyway.
osarch_arg=()
if [ "$(uname -s)" = "Darwin" ]; then
  osarch_arg=(-DCMAKE_OSX_ARCHITECTURES="$(uname -m)")
fi

echo "==> Lyra (host): configuring ($gen, $jobs jobs)"
rm -rf "$lyra_build"
mkdir -p "$lyra_build"
# IS_MOBILE_PLATFORM: the vendored TensorFlow tree is pruned for the mobile code
# path (the Android build defines this via __ANDROID__), which drops desktop-only
# profiler backends such as tsl/profiler/backends/cpu/traceme_recorder.h. Building
# the host the same way Android does keeps the two ends on the identical, tested
# code path and avoids reaching a file the prune removed. Lyra uses no profiler.
lyra_defs="-DIS_MOBILE_PLATFORM"
"$CMAKE" -S "$lyra_cmake_dir" -B "$lyra_build" -G "$gen" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_C_FLAGS="$lyra_defs" \
  -DCMAKE_CXX_FLAGS="$lyra_defs" \
  "${ar_arg[@]+"${ar_arg[@]}"}" \
  "${osarch_arg[@]+"${osarch_arg[@]}"}" \
  -DLYRA_VENDOR="$stage" \
  -DCMAKE_INSTALL_PREFIX="$prefix"
echo "==> Lyra (host): building"
"$CMAKE" --build "$lyra_build" --parallel "$jobs"
"$CMAKE" --install "$lyra_build"

for f in lib/liblyra.a lyra_encoder.h lyra_decoder.h lyra/lyra_config.pb.h \
         include/com_google_absl/absl/types/span.h include/gulrak_filesystem/include/ghc/filesystem.hpp \
         model_coeffs/lyragan.tflite model_coeffs/quantizer.tflite model_coeffs/soundstream_encoder.tflite; do
  [ -f "$prefix/$f" ] || { echo "error: the Lyra build produced no $prefix/$f" >&2; exit 1; }
done
hash_inputs > "$stamp"
echo "==> Lyra (host): done — $prefix/lib/liblyra.a"
