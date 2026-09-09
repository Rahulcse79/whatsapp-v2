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

OPUS_TAG="v1.5.2"
OPUS_SHA="ddbe48383984d56acd9e1ab6a090c54ca6b735a6"
OPUS_URL="https://github.com/xiph/opus/archive/refs/tags/${OPUS_TAG}.tar.gz"

LIBVPX_TAG="v1.17.0"
LIBVPX_SHA="6df3ec34557879fff673706f4a1d9fbd0f3a6f0e"
LIBVPX_URL="https://github.com/webmproject/libvpx/archive/refs/tags/${LIBVPX_TAG}.tar.gz"

# ---------------------------------------------------------------------------
# What is removed from each tree, and why.
#
# The rule: remove each tree's OWN test suite, its documentation, and its pre-generated
# build scratch — things neither compiled into the .so nor read by configure. Remove
# nothing else. Measured saving: 111 MB of 246, and 89 MB of that is OpenSSL's test suite
# alone. docs/native-dependencies.md §1.2 carries the table and the risk per entry.
#
# What is deliberately NOT here is as important as what is:
#   - pjproject/pjsip-apps/src/swig  — this IS stage 1. Pruning it deletes the bindings.
#   - pjproject/third_party/*        — upstream's own vendoring, built by relative path.
#                                      webrtc/ is the acoustic echo canceller.
#   - opus/dnn                       — on the include path in Makefile.am:14. Removing it
#                                      breaks the build, not just a feature.

PJPROJECT_PRUNE=(
  tests
  pjsip-apps/src/samples

  # The sample applications. Removed for two reasons that are not size.
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
  pjsip-apps/src/pjsua/android
  pjsip-apps/src/pjsua/ios
  pjsip-apps/src/swig/java/android
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
  doc
  tests
)

LIBVPX_PRUNE=(
  test
  build_debug
  examples
)
