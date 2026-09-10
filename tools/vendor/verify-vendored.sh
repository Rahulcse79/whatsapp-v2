#!/usr/bin/env bash
#
# Asserts that every file on disk under third_party/ is one git is tracking.
#
# ## Why this exists — it caught a real, silent 348-file corruption
#
# `.gitignore` applies to vendored trees like any other, and git honours the DEEPEST
# `.gitignore` for a path. Two separate mechanisms bit on the first vendoring attempt:
#
#   1. This repository's own `build/` rule — meant for Gradle output — matched
#      `third_party/pjproject/build/`, which is not output at all. It is the make-based
#      build system: every pjproject target includes the `.mak` files in it. Nine such
#      directories were excluded across pjproject and libvpx.
#   2. The sample apps inside pjproject ship their own `.gitignore` files, which excluded
#      a further ~340 files that upstream nonetheless tracks and ships in the tarball.
#
# Both are silent. The tree looks complete on the machine that vendored it, the commit is
# missing a third of a build system, and CI fails on a fresh checkout with an error about
# a missing include — nowhere near the cause.
#
# So the count is asserted, on every run, rather than trusted.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

if [ ! -d third_party ]; then
  echo "third_party/ does not exist — nothing to verify"
  exit 0
fi

on_disk="$(find third_party -type f | LC_ALL=C sort)"
tracked="$(git ls-files third_party | LC_ALL=C sort)"

disk_count="$(printf '%s\n' "$on_disk" | grep -c . || true)"
tracked_count="$(printf '%s\n' "$tracked" | grep -c . || true)"

if [ "$disk_count" != "$tracked_count" ]; then
  echo "::error::third_party/ has $disk_count files on disk but git tracks $tracked_count."
  echo "Untracked (git is silently dropping these — check .gitignore):"
  comm -23 <(printf '%s\n' "$on_disk") <(printf '%s\n' "$tracked") | head -40
  echo "Tracked but absent from disk:"
  comm -13 <(printf '%s\n' "$on_disk") <(printf '%s\n' "$tracked") | head -40
  exit 1
fi

echo "third_party/: $disk_count files, all tracked"
