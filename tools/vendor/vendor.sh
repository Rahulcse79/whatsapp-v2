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
  # `local prune=("$@")` with no arguments is an empty array, and bash 3.2 treats an
  # empty array as unset under `set -u`; the guarded expansion below is the same idiom as
  # at the call sites.

  echo "==> $name"
  curl -fsSL -o "$work/$name.tgz" "$url"
  mkdir -p "$work/$name"
  tar -xzf "$work/$name.tgz" -C "$work/$name" --strip-components=1

  # Prune before the tree is moved into place, so a half-pruned tree can never be
  # committed if this script is interrupted.
  local p
  for p in ${prune[@]+"${prune[@]}"}; do
    rm -rf "${work:?}/$name/$p"
  done

  rm -rf "${vendor_dir:?}/$name"
  mkdir -p "$vendor_dir"
  mv "$work/$name" "$vendor_dir/$name"

  # The commit the tarball came from. A tag can move; a hash cannot, and this is the
  # value docs/native-dependencies.md records and verify-pins.sh checks.
  echo "    pinned at $sha"
}

# The inverse of prune, for the one tree where a prune list would be a hundred lines:
# keep exactly the listed paths (files or directories) and delete everything else. Applied
# before the tree's PRUNE list, so a prune can still cut inside a kept directory.
keep_only() {
  local dir="$1"
  shift
  local keep=("$@")
  local f k keep_it
  # Every file, so a kept directory keeps its whole subtree and a kept file keeps itself.
  while IFS= read -r f; do
    keep_it=0
    for k in "${keep[@]}"; do
      case "$f" in
        "$k"|"$k"/*) keep_it=1; break ;;
      esac
    done
    [ "$keep_it" = 1 ] || rm -f "$dir/$f"
  done < <(cd "$dir" && find . -type f | sed 's|^\./||')
  # Directories emptied by the above.
  find "$dir" -type d -empty -delete
}

# `${ARR[@]+"${ARR[@]}"}` rather than `"${ARR[@]}"`: macOS ships bash 3.2, where an empty
# array under `set -u` is an unbound variable. OPUS_PRUNE is empty by design, and this
# script died on it the first time it was run on a Mac.
fetch_and_prune pjproject "$PJPROJECT_URL" "$PJPROJECT_SHA" ${PJPROJECT_PRUNE[@]+"${PJPROJECT_PRUNE[@]}"}
fetch_and_prune openssl   "$OPENSSL_URL"   "$OPENSSL_SHA"   ${OPENSSL_PRUNE[@]+"${OPENSSL_PRUNE[@]}"}
fetch_and_prune opus      "$OPUS_URL"      "$OPUS_SHA"      ${OPUS_PRUNE[@]+"${OPUS_PRUNE[@]}"}
fetch_and_prune libvpx    "$LIBVPX_URL"    "$LIBVPX_SHA"    ${LIBVPX_PRUNE[@]+"${LIBVPX_PRUNE[@]}"}

# ---------------------------------------------------------------- the Lyra closure
#
# Nineteen trees, one loop. `LYRA_TREES` maps each directory name to its variable prefix
# in pins.sh, so adding a tree is a pin block and one line there, not a copy of this.
# TensorFlow is the one tree with a KEEP list (pins.sh says why), applied before its
# PRUNE list.
for entry in "${LYRA_TREES[@]}"; do
  name="${entry%%:*}"
  var="${entry##*:}"
  url_var="${var}_URL"; sha_var="${var}_SHA"; prune_var="${var}_PRUNE[@]"; keep_var="${var}_KEEP[@]"
  # ${!prune_var} with an empty array trips `set -u` on bash 3 (macOS ships 3.2), hence
  # the `:-` and the explicit test rather than a bare expansion.
  prune=("${!prune_var:-}")
  [ -n "${prune[0]:-}" ] || prune=()
  keep=("${!keep_var:-}")
  [ -n "${keep[0]:-}" ] || keep=()

  # Every `.gitignore` inside these trees is removed, and this is the fix for a corruption
  # the first vendoring attempt already paid for once (348 files): git honours the DEEPEST
  # .gitignore, so a vendored tree's own ignore rules silently drop its files from the
  # commit — eigen's `core` rule swallows Eigen/src/Core/ on a case-insensitive disk,
  # flatbuffers' `build` swallows a BUILD file, 202 files across the closure. A tree
  # without its .gitignore is otherwise byte-for-byte what upstream published, and
  # verify-vendored.sh is what proves nothing else went missing. The four original trees
  # are left as committed.
  strip_gitignores=1
  if [ ${#keep[@]} -gt 0 ]; then
    # Fetch without pruning, keep, then prune: keep_only wants the whole tree first.
    fetch_and_prune "$name" "${!url_var}" "${!sha_var}"
    echo "    keeping only ${#keep[@]} paths"
    keep_only "$vendor_dir/$name" "${keep[@]}"
    for p in ${prune[@]+"${prune[@]}"}; do rm -rf "${vendor_dir:?}/$name/$p"; done
  else
    fetch_and_prune "$name" "${!url_var}" "${!sha_var}" ${prune[@]+"${prune[@]}"}
  fi
  if [ "$strip_gitignores" = 1 ]; then
    find "$vendor_dir/$name" -name .gitignore -type f -delete
    # A nested .gitattributes overrides the root's `third_party/** -text` for its subtree:
    # flatbuffers' says `*.bat text eol=crlf`, so a fresh checkout would rewrite those
    # files and rule 12's tree hash would differ between the vendoring machine and CI.
    find "$vendor_dir/$name" -name .gitattributes -type f -delete
    # Symlinks (five across the closure: a .pylintrc, a docs page, xnnpack/tools/xngen …)
    # are tracked by git as links, but hashed by nothing here — record-hashes.sh and
    # verify-vendored.sh walk regular files — so each one is a permanent "tracked but
    # absent from disk". None is read by any build file; verify-prune.sh would say so.
    find "$vendor_dir/$name" -type l -delete
  fi
done

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
