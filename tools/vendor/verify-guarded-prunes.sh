#!/usr/bin/env bash
#
# Proves each guarded prune in pins.sh (GUARDED_PRUNES) is still safe.
#
# A guarded prune removes a directory the tree's own CMakeLists.txt names — but only
# inside an IF(<GUARD>) block — and is safe exactly while two things hold:
#
#   1. the tree's CMakeLists.txt still has that guard (an upstream bump that renamed or
#      dropped the option would make the reference unconditional overnight), and
#   2. pjsip/lyra/CMakeLists.txt still sets the guard OFF.
#
# The same construction as tools/vendor/verify-openssl-prune.sh, for CMake. What this
# cannot prove is that EVERY reference sits inside the guard — that is what the build is
# for, and CI runs it. What it can prove is that the two facts the prune was argued from
# are still true.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
source tools/vendor/pins.sh

status=0
for g in "${GUARDED_PRUNES[@]}"; do
  path="${g%%:*}"; guard="${g##*:}"
  tree="${path%%/*}"
  cmake="third_party/$tree/CMakeLists.txt"
  [ -f "$cmake" ] || continue
  if ! grep -qE "^[[:space:]]*(IF|if)[[:space:]]*\(.*\b${guard}\b" "$cmake"; then
    echo "::error::$cmake no longer guards anything with $guard, so pruning $path is no longer known to be safe"
    status=1
  fi
  if ! grep -qE "set\(${guard}[[:space:]]+OFF" pjsip/lyra/CMakeLists.txt; then
    echo "::error::pjsip/lyra/CMakeLists.txt does not set $guard OFF, so $path may be built"
    status=1
  fi
done
[ $status -eq 0 ] && echo "every guarded prune still has its guard, and the guard is OFF"
exit $status
