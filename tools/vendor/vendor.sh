#!/usr/bin/env bash
#
# Vendors the native dependencies into third_party/ at their pinned commits (N-2).
#
# Run this when a pin changes, then commit the result and the regenerated manifest. It is
# NOT part of the build: the build reads what is checked in, which is the whole point of
# vendoring. `docs/native-dependencies.md` is the source of truth for what is pinned here,
# and the two must agree — `tools/vendor/verify-pins.sh` is what enforces that.
#
# Why tarballs and not `git clone`: a clone brings upstream's entire history, which is
# where the ~500 MB estimate in the master prompt's §2.1 came from. A tag's tarball is the
# source at that tag and nothing else. Measured: 246 MB extracted against 115 MB of pack
# for the clone-shaped alternative. See docs/native-dependencies.md §1.1.
#
# Why not a submodule: a submodule is a pointer to somebody else's repository resolved at
# fetch time, which re-introduces exactly the property N-2 removes, and it breaks N-7 the
# first time a file is patched.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

source "$(dirname "${BASH_SOURCE[0]}")/pins.sh"

vendor_dir="third_party"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch_and_prune() {
  local name="$1" url="$2" sha="$3"
  shift 3
  local prune=("$@")

  echo "==> $name"
  curl -fsSL -o "$work/$name.tgz" "$url"
  mkdir -p "$work/$name"
  tar -xzf "$work/$name.tgz" -C "$work/$name" --strip-components=1

  # Prune before the tree is moved into place, so a half-pruned tree can never be
  # committed if this script is interrupted.
  local p
  for p in "${prune[@]}"; do
    rm -rf "${work:?}/$name/$p"
  done

  rm -rf "${vendor_dir:?}/$name"
  mkdir -p "$vendor_dir"
  mv "$work/$name" "$vendor_dir/$name"

  # The commit the tarball came from. A tag can move; a hash cannot, and this is the
  # value docs/native-dependencies.md records and verify-pins.sh checks.
  echo "    pinned at $sha"
}

fetch_and_prune pjproject "$PJPROJECT_URL" "$PJPROJECT_SHA" "${PJPROJECT_PRUNE[@]}"
fetch_and_prune openssl   "$OPENSSL_URL"   "$OPENSSL_SHA"   "${OPENSSL_PRUNE[@]}"
fetch_and_prune opus      "$OPUS_URL"      "$OPUS_SHA"      "${OPUS_PRUNE[@]}"
fetch_and_prune libvpx    "$LIBVPX_URL"    "$LIBVPX_SHA"    "${LIBVPX_PRUNE[@]}"

echo
echo "==> applying patches"
"$root/tools/vendor/apply-patches.sh"

echo
echo "==> recording tree hashes (rule 12)"
"$root/tools/vendor/record-hashes.sh"

echo
echo "==> sizes"
du -sh "$vendor_dir"/*/ | sort -h
du -sh "$vendor_dir"
