#!/usr/bin/env bash
#
# Applies pjsip/patches/*.patch to the vendored trees, in numbered order (N-7).
#
# Every local change to vendored source is a patch file, never an edit. An edit with no
# patch is invisible at the next version bump: somebody bumps pjproject, re-applies the
# series, and a fixed bug comes back with no commit that removed it. Architecture rule 12
# is the machine check that this held.
#
# Patches are applied from the repository root with -p1, so their paths start
# `third_party/<dep>/…` and one patch may touch more than one tree.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

shopt -s nullglob
patches=(pjsip/patches/*.patch)

if [ ${#patches[@]} -eq 0 ]; then
  echo "    no patches (pjsip/patches/ holds none yet)"
  exit 0
fi

for p in "${patches[@]}"; do
  echo "    $p"
  # --forward so re-running is a clear "already applied" rather than a reversed patch,
  # and no fuzz: a patch that no longer applies cleanly to a bumped tree must FAIL and be
  # rewritten, not be silently placed somewhere near where it used to go.
  patch -p1 --forward --no-backup-if-mismatch -F0 < "$p"
done
