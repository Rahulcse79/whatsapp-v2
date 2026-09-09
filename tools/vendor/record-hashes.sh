#!/usr/bin/env bash
#
# Records a content hash of every vendored tree into pjsip/patches/vendored-tree.sha256,
# which is what architecture rule 12 compares against on every build.
#
# Run this after vendoring or after adding a patch — never to "fix" a rule 12 failure on a
# tree you edited by hand. A failing rule 12 means an edit with no patch; the fix is to
# write the patch, not to re-record the hash. Re-recording silently launders the edit,
# which is precisely the defect N-7 exists to prevent.
#
# The hash must match ArchitectureRules.hashTree: every file's path (with `/` separators)
# and bytes, sorted by path, with a NUL between the path and the content. A rename
# therefore changes the hash even when no byte does — because a rename is an edit.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

manifest="pjsip/patches/vendored-tree.sha256"
mkdir -p "$(dirname "$manifest")"

{
  echo "# Content hash of each vendored tree, checked by architecture rule 12 (N-7)."
  echo "#"
  echo "# Regenerate with tools/vendor/record-hashes.sh AFTER vendoring or adding a patch."
  echo "# Never regenerate it to silence a rule 12 failure: that failure means somebody"
  echo "# edited vendored source without recording a patch, and re-recording hides it."
  echo "#"
  echo "# <sha256>  <tree name>"
} > "$manifest"

for tree in third_party/*/; do
  [ -d "$tree" ] || continue
  name="$(basename "$tree")"

  # Sorted by path, path and bytes both hashed, NUL-separated — the same construction as
  # ArchitectureRules.hashTree. LC_ALL=C so the sort order is byte order on every machine
  # rather than the runner's locale, which would silently differ between macOS and Linux.
  hash="$(
    cd "$tree"
    find . -type f | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s' "${f#./}"
      printf '\0'
      cat "$f"
    done | shasum -a 256 | cut -d' ' -f1
  )"

  echo "$hash  $name" >> "$manifest"
  echo "    $name  $hash"
done
