#!/usr/bin/env bash
#
# The pinned versions of every vendored native dependency, in one place.
#
# This file and the table in docs/native-dependencies.md §1 are the same facts twice, so
# `tools/vendor/verify-pins.sh` asserts they agree. Changing a pin means changing both, in
# one commit, with the patch series re-applied — which is the reviewable form N-7 asks for.
#
# Every entry is a TAG and its COMMIT HASH. The tag is what a human reads; the hash is what
# is actually pinned, because a tag can be moved and a hash cannot. There is no `branch =`
# anywhere here and there must never be: an unpinned input makes N-11 unmeasurable before
# it can be violated (master prompt §2.4.3).

PJPROJECT_TAG="2.17"
PJPROJECT_SHA="5a457451fa2712ba18e12b01738e8ff3af2b26fd"
PJPROJECT_URL="https://github.com/pjsip/pjproject/archive/refs/tags/${PJPROJECT_TAG}.tar.gz"

OPENSSL_TAG="openssl-3.5.0"
OPENSSL_SHA="636dfadc70ce26f2473870570bfd9ec352806b1d"
OPENSSL_URL="https://github.com/openssl/openssl/archive/refs/tags/${OPENSSL_TAG}.tar.gz"

# Opus comes from the RELEASE tarball, not the GitHub tag archive, and the difference is
# not cosmetic. The tag archive ships no generated `configure`, so the build runs
# `autogen.sh` — and `autogen.sh:12` calls `dnn/download_model.sh`, which **wgets a model
# from media.xiph.org at build time**. That is a network fetch from inside a vendored tree:
# it defeats N-2 outright, and it would have passed unnoticed on CI, where wget exists.
#
# The release tarball has `configure` pre-generated, ships the DNN weights as ten
# `dnn/*_data.c` files, and contains no download script at all. It is also what the green
# workflow already used (.github/workflows/build-pjsip.yml:293-294) — vendoring from the tag
# was this project's divergence from a build that worked.
OPUS_TAG="1.5.2"
OPUS_SHA="ddbe48383984d56acd9e1ab6a090c54ca6b735a6"
OPUS_URL="https://downloads.xiph.org/releases/opus/opus-${OPUS_TAG}.tar.gz"

LIBVPX_TAG="v1.17.0"
LIBVPX_SHA="6df3ec34557879fff673706f4a1d9fbd0f3a6f0e"
LIBVPX_URL="https://github.com/webmproject/libvpx/archive/refs/tags/${LIBVPX_TAG}.tar.gz"

# ---------------------------------------------------------------------------
# The Lyra closure — ADR-008, Exit A (2026-09-10).
#
# Nineteen trees for one codec, and every one of them is here because N-2 forbids the
# build to fetch anything. The pins are TensorFlow Lite's own for its dependencies
# (tensorflow/lite/tools/cmake/modules/*.cmake at v2.11.0, and XNNPACK's cmake/Download*.cmake
# for its sub-dependencies), Lyra's WORKSPACE for audio_dsp, and this repository's for the
# three Lyra floats: glog (`branch = "master"` upstream), psimd (FP16 fetches `master`) and
# gulrak/filesystem, pinned to the commits their tags pointed at on 2026-09-10.
#
# Two trees Lyra's WORKSPACE names are deliberately NOT here:
#   - com_google_protobuf: lyra_config.proto has one int32 field and the file it parses is
#     two bytes. pjsip/patches/0001-lyra-config-without-protobuf.patch replaces the protoc
#     output with a hand-written parser of exactly that message.
#   - com_github_gflags_gflags: glog is built WITH_GFLAGS=OFF; nothing in Lyra uses it.
#
# cpuinfo is vendored once. XNNPACK wants `clog` from a different cpuinfo commit
# (4b5a76c4); its deps/clog is byte-identical to this one's (diff -rq, 2026-09-10).
#
# All GitHub trees are fetched as archive-by-commit tarballs, which is what makes the pin
# the hash rather than the tag. Eigen is on GitLab and uses its equivalent URL.

LYRA_TAG="v1.3.2"
LYRA_SHA="47698dadf0010abff6a848e02642f55f806d4842"
LYRA_URL="https://github.com/google/lyra/archive/${LYRA_SHA}.tar.gz"

TENSORFLOW_TAG="v2.11.0"
TENSORFLOW_SHA="d5b57ca93e506df258271ea00fc29cf98383a374"
TENSORFLOW_URL="https://github.com/tensorflow/tensorflow/archive/${TENSORFLOW_SHA}.tar.gz"

ABSEIL_CPP_TAG="lts_2022_06_23 (TFLite's pin, ahead of the tag)"
ABSEIL_CPP_SHA="273292d1cfc0a94a65082ee350509af1d113344d"
ABSEIL_CPP_URL="https://github.com/abseil/abseil-cpp/archive/${ABSEIL_CPP_SHA}.tar.gz"

CPUINFO_TAG="(no tag; TFLite's pin)"
CPUINFO_SHA="5e63739504f0f8e18e941bd63b2d6d42536c7d90"
CPUINFO_URL="https://github.com/pytorch/cpuinfo/archive/${CPUINFO_SHA}.tar.gz"

EIGEN_TAG="(no tag; TFLite's pin)"
EIGEN_SHA="3bb6a48d8c171cf20b5f8e48bfb4e424fbd4f79e"
EIGEN_URL="https://gitlab.com/libeigen/eigen/-/archive/${EIGEN_SHA}/eigen-${EIGEN_SHA}.tar.gz"

FARMHASH_TAG="(no tag; TFLite's pin)"
FARMHASH_SHA="0d859a811870d10f53a594927d0d0b97573ad06d"
FARMHASH_URL="https://github.com/google/farmhash/archive/${FARMHASH_SHA}.tar.gz"

FFT2D_TAG="v1.0"
FFT2D_SHA="c6fd2dd6d21397baa6653139d31d84540d5449a2"
FFT2D_URL="https://github.com/petewarden/OouraFFT/archive/${FFT2D_SHA}.tar.gz"

FLATBUFFERS_TAG="v2.0.6"
FLATBUFFERS_SHA="615616cb5549a34bdf288c04bc1b94bd7a65c396"
FLATBUFFERS_URL="https://github.com/google/flatbuffers/archive/${FLATBUFFERS_SHA}.tar.gz"

FP16_TAG="(no tag; TFLite's and XNNPACK's pin)"
FP16_SHA="0a92994d729ff76a58f692d3028ca1b64b145d91"
FP16_URL="https://github.com/Maratyszcza/FP16/archive/${FP16_SHA}.tar.gz"

FXDIV_TAG="(no tag; XNNPACK's pin)"
FXDIV_SHA="b408327ac2a15ec3e43352421954f5b1967701d1"
FXDIV_URL="https://github.com/Maratyszcza/FXdiv/archive/${FXDIV_SHA}.tar.gz"

GEMMLOWP_TAG="(no tag; TFLite's pin)"
GEMMLOWP_SHA="fda83bdc38b118cc6b56753bd540caa49e570745"
GEMMLOWP_URL="https://github.com/google/gemmlowp/archive/${GEMMLOWP_SHA}.tar.gz"

NEON2SSE_TAG="(no tag; TFLite's pin)"
NEON2SSE_SHA="a15b489e1222b2087007546b4912e21293ea86ff"
NEON2SSE_URL="https://github.com/intel/ARM_NEON_2_x86_SSE/archive/${NEON2SSE_SHA}.tar.gz"

PSIMD_TAG="(no tag; FP16 fetches master — pinned here to its last commit, 2020-05-17)"
PSIMD_SHA="072586a71b55b7f8c584153d223e95687148a900"
PSIMD_URL="https://github.com/Maratyszcza/psimd/archive/${PSIMD_SHA}.tar.gz"

PTHREADPOOL_TAG="(no tag; XNNPACK's pin)"
PTHREADPOOL_SHA="545ebe9f225aec6dca49109516fac02e973a3de2"
PTHREADPOOL_URL="https://github.com/Maratyszcza/pthreadpool/archive/${PTHREADPOOL_SHA}.tar.gz"

RUY_TAG="(no tag; TFLite's pin)"
RUY_SHA="841ea4172ba904fe3536789497f9565f2ef64129"
RUY_URL="https://github.com/google/ruy/archive/${RUY_SHA}.tar.gz"

XNNPACK_TAG="(no tag; TFLite's pin)"
XNNPACK_SHA="e8f74a9763aa36559980a0c2f37f587794995622"
XNNPACK_URL="https://github.com/google/XNNPACK/archive/${XNNPACK_SHA}.tar.gz"

AUDIO_DSP_TAG="(no tag; Lyra's pin)"
AUDIO_DSP_SHA="14a45c5a7c965e5ef01fe537bd816ce10a247813"
AUDIO_DSP_URL="https://github.com/google/multichannel-audio-tools/archive/${AUDIO_DSP_SHA}.tar.gz"

GLOG_TAG="v0.6.0"
GLOG_SHA="b33e3bad4c46c8a6345525fd822af355e5ef9446"
GLOG_URL="https://github.com/google/glog/archive/${GLOG_SHA}.tar.gz"

GULRAK_FILESYSTEM_TAG="v1.3.6"
GULRAK_FILESYSTEM_SHA="7e37433f318488ae4bc80f80e12df12a01579874"
GULRAK_FILESYSTEM_URL="https://github.com/gulrak/filesystem/archive/${GULRAK_FILESYSTEM_SHA}.tar.gz"

# Directory name under third_party/ -> the variable prefix above. Order is the order
# vendor.sh fetches in and record-hashes.sh reports in; nothing depends on it.
LYRA_TREES=(
  "lyra:LYRA"
  "tensorflow:TENSORFLOW"
  "abseil-cpp:ABSEIL_CPP"
  "cpuinfo:CPUINFO"
  "eigen:EIGEN"
  "farmhash:FARMHASH"
  "fft2d:FFT2D"
  "flatbuffers:FLATBUFFERS"
  "FP16:FP16"
  "FXdiv:FXDIV"
  "gemmlowp:GEMMLOWP"
  "neon2sse:NEON2SSE"
  "psimd:PSIMD"
  "pthreadpool:PTHREADPOOL"
  "ruy:RUY"
  "xnnpack:XNNPACK"
  "audio_dsp:AUDIO_DSP"
  "glog:GLOG"
  "gulrak-filesystem:GULRAK_FILESYSTEM"
)

# ---------------------------------------------------------------------------
# What is removed from each tree, and why.
#
# ## The rule, corrected by three failures
#
# The first rule was "remove each tree's own tests, docs and build scratch". That is WRONG,
# and it broke the build three times before the real rule was clear:
#
#   1. OpenSSL's `doc/`, `demos/` and `fuzz/` are in its UNCONDITIONAL `SUBDIRS` line, so
#      Configure walks into each and reads a `build.info` that was not there.
#   2. Opus's `configure.ac:1039` declares `doc/Makefile`, so `autoreconf` failed with
#      "required file 'doc/Makefile.in' not found" — a message about a file nobody deleted,
#      three minutes into a cross-compile.
#   3. pjproject's root `Makefile` names `pjsip-apps/src/pjsua/android`.
#
# **The real rule: remove only what the tree's OWN build files never mention.** Not "tests
# and docs" — an autotools project routinely declares both, and declaring them is enough to
# require them. `tools/vendor/verify-prune.sh` greps every build file in every vendored tree
# for every pruned path and fails if one is referenced, so this is checked rather than
# reasoned about.
#
# The saving is smaller than the first pass claimed — ~98 MB of 246 rather than 111 — and
# 89 MB of it is OpenSSL's test suite, which survives because its SUBDIRS entry is GUARDED
# (`IF[{- !$disabled{tests} -}]`) and every Configure here passes `no-tests`.
# docs/native-dependencies.md §1.2 carries the table and the risk per entry.
#
# What is deliberately NOT here is as important as what is:
#   - pjproject/pjsip-apps/src/swig  — this IS stage 1. Pruning it deletes the bindings.
#   - pjproject/third_party/*        — upstream's own vendoring, built by relative path.
#                                      webrtc/ is the acoustic echo canceller.
#   - opus/dnn                       — on the include path in Makefile.am:14. Removing it
#                                      breaks the build, not just a feature.

PJPROJECT_PRUNE=(
  # `tests` is NOT pruned: the root Makefile recurses into it (docs/native-dependencies.md
  # §1.2, "restored"). It was still listed here after the restore — the committed tree kept
  # it while a re-run of vendor.sh deleted it again, which is how the 2026-09-10 re-vendor
  # for Lyra showed 431 deletions under tests/. verify-prune.sh skips a path that exists,
  # so the stale entry was invisible until the script ran.
  # `pjsip-apps/src/samples` is NOT pruned: `configure-android:102` runs
  # `ndk-build -C pjsip-apps/src/samples/android_sample` as its NDK probe, and without the
  # directory it stops with "failed to run ndk-build, check ANDROID_NDK_ROOT env var" —
  # a message about an environment variable that is set correctly.
  #
  # Neither is `pjsip-apps/src/swig/java/android`: the same script reads
  # `.../android/jni/Application.mk`. It carries a gradle-wrapper.jar and its own
  # .gitignore, both of which this repository would rather not have. A build that runs
  # wins.
  #
  # The remaining sample applications. Removed for two reasons that are not size.
  #
  # 1. Each Android sample ships a committed `gradle-wrapper.jar`. This repository should
  #    not carry another project's wrapper binary. Rule 11 covers .aar/.so and would NOT
  #    fire on a .jar, so this is done by hand and said out loud rather than left to a
  #    rule that does not cover it. docs/module-structure.md §2.0.1.
  #
  # 2. Each carries its own `.gitignore`, and git honours the deepest one. Vendoring them
  #    silently dropped 348 files from the commit — a vendored tree that is not what
  #    upstream published, which is exactly the corruption N-2 exists to prevent.
  #    tools/vendor/verify-vendored.sh is the check that catches this class of defect.
  #
  # `pjsip-apps/src/swig/{Makefile,pjsua2.i,java/Makefile}` are KEPT: they are stage 1.
  # Only the sample apps around them go. Note that swig/java/Makefile's `android` target
  # writes into the `android/` tree removed here — stage 1 invokes `swig` directly rather
  # than through that target, exactly as the existing green workflow does
  # (.github/workflows/build-pjsip.yml:110-117), so nothing reads it.
  # `pjsip-apps/src/pjsua/android` is NOT pruned, though it carries a gradle-wrapper.jar:
  # pjproject's root Makefile names it. A wrapper jar this repository would rather not
  # carry is a smaller problem than a build that does not run, and `verify-prune.sh` is
  # what turned that from an opinion into a check.
  pjsip-apps/src/pjsua/ios
  pjsip-apps/src/swig/csharp
  pjsip-apps/src/swig/python
  pjsip-apps/src/rust
)

OPENSSL_PRUNE=(
  # `test/` alone — 89 MB, the single largest saving in the whole prune.
  #
  # It is safe for one specific reason, and the reason must not be lost: OpenSSL's root
  # `build.info` guards it, `IF[{- !$disabled{tests} -}] SUBDIRS=test`, and every Configure
  # invocation in this repository passes `no-tests`. `tools/vendor/verify-openssl-prune.sh`
  # asserts that guard still exists AND that `no-tests` is still passed, because the prune
  # is only safe while both are true.
  #
  # `doc/`, `demos/`, `fuzz/` were pruned in a first pass and RESTORED. They are in the
  # UNCONDITIONAL `SUBDIRS` line, so Configure walks into them and reads a `build.info`
  # that would not be there. That is a 9.6 MB saving against a build that does not
  # configure — read the root build.info before adding anything to this list.
  test
)

OPUS_PRUNE=(
  # Nothing. `configure.ac:1039` declares `doc/Makefile` and `Makefile.am:10` names
  # `./doc`, so `autoreconf` requires both directories to exist whatever the build does
  # with them. Opus is 16 MB; there is nothing here worth breaking a cross-compile for.
)

LIBVPX_PRUNE=(
  # Pre-generated build scratch, referenced by nothing. `test/` and `examples/` were
  # pruned in a first pass and restored: libvpx's configure knows about both, and
  # --disable-unit-tests is not the same as the directory being absent.
  build_debug
)

# ---------------------------------------------------------------------------
# The Lyra closure's prunes. Same rule as above: only what the tree's own build files
# never mention — with two guarded exceptions, and one tree that is kept rather than pruned.

# TensorFlow is 318 MB and 28,000 files, of which TensorFlow Lite's CMake build reads 472
# (measured from the arm64 build's dependency files, 2026-09-10). A prune list would name
# a hundred directories; this tree is the one place the rule runs the other way: KEEP
# these paths, delete everything else. tools/vendor/verify-tensorflow-keep.sh asserts
# every path lite's CMake names under the tree still exists.
#
# Why each is kept:
#   tensorflow/lite                      the library; everything else is pulled in by it
#   tensorflow/core/{platform,public,profiler/lib,lib/random}
#   tensorflow/tsl/{platform,profiler/lib,lib/random}
#                                        headers lite includes (platform.h, logging.h,
#                                        bfloat16.h, traceme.h, philox_random.h …)
#   tensorflow/core/util/stats_calculator.{cc,h}, stat_summarizer_options.h
#                                        named in lite/CMakeLists.txt by path
#   tensorflow/core/kernels/eigen_{convolution_helpers,spatial_convolutions-inl}.h
#                                        included by lite/kernels/internal/optimized
#   third_party/{eigen3,fft2d}           TF's shim headers: `third_party/eigen3/Eigen/Core`
#                                        and `third_party/fft2d/fft.h`, which audio_dsp
#                                        includes by those exact paths
#   LICENSE, tensorflow/lite/g3doc removed separately below (16 MB of documentation the
#                                        CMake never names)
TENSORFLOW_KEEP=(
  LICENSE
  tensorflow/lite
  tensorflow/core/platform
  tensorflow/core/public
  tensorflow/core/profiler/lib
  tensorflow/core/lib/random
  tensorflow/core/util/stats_calculator.cc
  tensorflow/core/util/stats_calculator.h
  tensorflow/core/util/stat_summarizer_options.h
  tensorflow/core/kernels/eigen_convolution_helpers.h
  tensorflow/core/kernels/eigen_spatial_convolutions-inl.h
  tensorflow/tsl/platform
  tensorflow/tsl/profiler/lib
  tensorflow/tsl/lib/random
  third_party/eigen3
  third_party/fft2d
)
TENSORFLOW_PRUNE=(
  tensorflow/lite/g3doc
  # Two sample/binding trees that ship committed `gradle-wrapper.jar`s and iOS test
  # archives (`input.a`). Rule 11 covers .aar and .so and would not fire on either, so
  # this is done by hand and said out loud, exactly as for pjproject's samples. Neither is
  # named by lite's CMake.
  tensorflow/lite/java
  tensorflow/lite/ios
)

# XNNPACK's bench/ (2 MB) is the OpenSSL case again: every mention in its CMakeLists.txt is
# inside the IF(XNNPACK_BUILD_BENCHMARKS) block (lines 9040-9548 of 9548), and
# pjsip/lyra/CMakeLists.txt sets it OFF. tools/vendor/verify-guarded-prunes.sh asserts the
# guard and the OFF, because the prune is only safe while both hold.
#
# test/ (55 MB) is KEPT, and the reason is one line: CMakeLists.txt:7215 declares
# `ADD_LIBRARY(convolution-test-helpers OBJECT test/convolution-test-helpers.cc)`
# UNCONDITIONALLY, outside every guard. Pruning test/ fails configure on a file nobody
# deleted — exactly the failure the rule above exists to prevent. Fifty-five megabytes is
# the price of not carving one file out of a deleted directory.
XNNPACK_PRUNE=(
  bench
)

# cpuinfo's test/ (19 MB of mock CPU dumps) is guarded the same way: every mention sits
# under IF(CPUINFO_SUPPORTED_PLATFORM AND CPUINFO_BUILD_MOCK_TESTS) (line 325) or
# IF(CPUINFO_SUPPORTED_PLATFORM AND CPUINFO_BUILD_UNIT_TESTS) (line 756), both OFF in
# pjsip/lyra/CMakeLists.txt.
CPUINFO_PRUNE=(
  test
)

# Lyra's own test data (WAVs, 3 MB) and its Android and CLI example apps. Lyra's Bazel
# BUILD files name them, but Bazel is not the build here — pjsip/lyra/CMakeLists.txt is —
# and it names none of these. model_coeffs/ is KEPT: it is the codec's weights.
LYRA_PRUNE=(
  lyra/testdata
  lyra/android_example
  lyra/cli_example
)

# Nothing is pruned from the rest. abseil-cpp, flatbuffers, eigen, ruy, gemmlowp and
# glog each name their tests or docs from a CMakeLists.txt (guarded, but guarded prunes
# are the exception here, not the rule), and none of the others is large enough to argue
# about.
ABSEIL_CPP_PRUNE=()
EIGEN_PRUNE=()
FARMHASH_PRUNE=()
FFT2D_PRUNE=()
# The Android and Kotlin sample projects each carry a committed gradle-wrapper.jar —
# the same reason pjproject's samples are pruned. Unreferenced by flatbuffers' CMake.
FLATBUFFERS_PRUNE=(
  android
  kotlin
)
FP16_PRUNE=()
FXDIV_PRUNE=()
GEMMLOWP_PRUNE=()
NEON2SSE_PRUNE=()
PSIMD_PRUNE=()
PTHREADPOOL_PRUNE=()
RUY_PRUNE=()
AUDIO_DSP_PRUNE=()
GLOG_PRUNE=()
GULRAK_FILESYSTEM_PRUNE=()

# tree/path -> the CMake guard that makes the prune safe. Checked by
# tools/vendor/verify-guarded-prunes.sh.
GUARDED_PRUNES=(
  "xnnpack/bench:XNNPACK_BUILD_BENCHMARKS"
  "cpuinfo/test:CPUINFO_BUILD_UNIT_TESTS"
  "cpuinfo/test:CPUINFO_BUILD_MOCK_TESTS"
)
