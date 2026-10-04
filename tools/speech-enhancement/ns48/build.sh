#!/usr/bin/env bash
#
# Builds `ns48` for the host from the vendored WebRTC sources.
#
# Three translation units, and no more: the float noise suppressor uses no `WebRtcSpl_*`
# function at all (its `signal_processing_library.h` include is vestigial - grep ns_core.c
# for `WebRtcSpl_` and the result is empty), so the only real dependency outside the `ns/`
# directory is the FFT in `common_audio/fft4g.c`.
#
# Nothing is written into third_party/ (N-7, architecture rule 12): the objects and the
# binary land in this directory's `build/`.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
webrtc="$root/third_party/pjproject/third_party/webrtc/src"
out="$here/build"

[ -d "$webrtc/webrtc/modules/audio_processing/ns" ] || {
  echo "ns48: $webrtc is missing. The WebRTC sources come with pjproject;" >&2
  echo "      run tools/vendor/vendor.sh or check the repository out completely." >&2
  exit 1
}

mkdir -p "$out"

# -DWEBRTC_POSIX and -DWEBRTC_MAC are what typedefs.h and defines.h key their platform
# branches on; without them the build picks the Windows branch and fails on <windows.h>.
# -ffast-math is NOT used: this tool exists to reproduce the handset's arithmetic, and
# reassociating the noise estimator's accumulations would make it a different filter.
cc -O2 -std=c99 -Wall -Wextra -Wno-unused-parameter \
   -DWEBRTC_POSIX -DWEBRTC_MAC \
   -I"$webrtc" -I"$here/.." \
   -o "$out/ns48" \
   "$here/main.c" \
   "$webrtc/webrtc/modules/audio_processing/ns/ns_core.c" \
   "$webrtc/webrtc/modules/audio_processing/ns/noise_suppression.c" \
   "$webrtc/webrtc/common_audio/fft4g.c" \
   -lm

echo "ns48: $out/ns48"
