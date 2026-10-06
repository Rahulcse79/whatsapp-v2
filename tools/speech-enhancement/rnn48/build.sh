#!/usr/bin/env bash
#
# Builds `rnn48` against RNNoise.
#
# Looks for RNNoise in two places, in this order:
#
#   1. third_party/rnnoise - once it is vendored, this is the only correct source, because
#      it is the tree the Android build compiles and therefore the one a host measurement
#      has to agree with.
#   2. $RNNOISE_PREFIX - an install prefix (include/rnnoise.h + lib/librnnoise.a), for
#      measuring RNNoise *before* committing to vendoring it. That order is the point: the
#      decision to add a vendored dependency should be made on a number, and this is how
#      the number gets taken first.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="$here/build"
vendored="$root/third_party/rnnoise"

if [ -d "$vendored" ]; then
  # The vendored tree is built in place into its own out-of-tree directory: nothing is
  # written into third_party/ (N-7, architecture rule 12).
  stage="$out/rnnoise-host"
  if [ ! -f "$stage/prefix/lib/librnnoise.a" ]; then
    echo "rnn48: building the vendored RNNoise for the host (once)"
    rm -rf "$stage" && mkdir -p "$stage"
    cp -R "$vendored/." "$stage/src-copy"
    ( cd "$stage/src-copy" \
      && ./configure --disable-shared --enable-static --prefix="$stage/prefix" >"$stage/configure.log" 2>&1 \
      && make -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" >"$stage/make.log" 2>&1 \
      && make install >>"$stage/make.log" 2>&1 ) || {
        echo "rnn48: the vendored RNNoise failed to build for the host." >&2
        tail -20 "$stage/configure.log" "$stage/make.log" >&2 || true
        exit 1
      }
  fi
  prefix="$stage/prefix"
  echo "rnn48: RNNoise from third_party/rnnoise"
elif [ -n "${RNNOISE_PREFIX:-}" ] && [ -f "$RNNOISE_PREFIX/lib/librnnoise.a" ]; then
  prefix="$RNNOISE_PREFIX"
  echo "rnn48: RNNoise from \$RNNOISE_PREFIX ($prefix)"
else
  cat >&2 <<'MSG'
rnn48: no RNNoise to build against.

Either vendor it (third_party/rnnoise), or point RNNOISE_PREFIX at a host install:

    curl -LO https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz
    tar xzf rnnoise-0.2.tar.gz && cd rnnoise-0.2
    ./configure --disable-shared --enable-static --prefix=$PWD/../prefix
    make && make install
    export RNNOISE_PREFIX=$PWD/../prefix

Use the RELEASE tarball, not the git tag archive: the tag ships no generated `configure`,
and `autogen.sh` on master reaches for the model over the network - the same trap
documented for Opus in tools/vendor/pins.sh.
MSG
  exit 1
fi

mkdir -p "$out"
cc -O2 -std=c99 -Wall -Wextra -Wno-unused-parameter \
   -I"$prefix/include" -I"$here/.." \
   -o "$out/rnn48" "$here/main.c" "$prefix/lib/librnnoise.a" -lm

echo "rnn48: $out/rnn48"
