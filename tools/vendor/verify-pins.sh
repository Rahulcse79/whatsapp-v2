#!/usr/bin/env bash
#
# Asserts that tools/vendor/pins.sh and docs/native-dependencies.md agree (N-10), and that
# docs/native-dependencies.md lists exactly the directories under third_party/.
#
# Two failure directions and both matter. A directory with no row is an undocumented
# dependency. A row with no directory is a document describing something that no longer
# exists, which is DoD 14.
#
# NOTE the subtlety this check must not fall into: the "Toolchain" heading in that document
# lists the NDK, SWIG, CMake and the rest, and those are NOT third_party/ directories. They
# are listed precisely because they are exempt from vendoring (master prompt §2.1.1). A
# naive implementation that scans every table in the file fails on them, so this one reads
# only the pinned-commit table.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

source tools/vendor/pins.sh
doc="docs/native-dependencies.md"
status=0

check_pin() {
  local name="$1" sha="$2"
  if ! grep -q "$sha" "$doc"; then
    echo "::error::$doc does not record the pinned commit $sha for $name"
    status=1
  fi
}

check_pin pjproject "$PJPROJECT_SHA"
check_pin openssl   "$OPENSSL_SHA"
check_pin opus      "$OPUS_SHA"
check_pin libvpx    "$LIBVPX_SHA"
for entry in "${LYRA_TREES[@]}"; do
  name="${entry%%:*}"; sha_var="${entry##*:}_SHA"
  check_pin "$name" "${!sha_var}"
done

# No dependency may float. This is the check that makes N-11 measurable at all: a
# `branch =` is not a reproducibility finding, it is an unpinned input (master prompt
# §2.4.3), and it must be caught before two builds are ever compared.
if grep -nE '^\s*[A-Z_]+_BRANCH=' tools/vendor/pins.sh; then
  echo "::error::a dependency is pinned to a branch. Pin it to a commit hash (N-11)."
  status=1
fi

if [ -d third_party ]; then
  for tree in third_party/*/; do
    name="$(basename "$tree")"
    if ! grep -q "third_party/$name\|\*\*$name\*\*\|^| \*\*$name\*\*" "$doc"; then
      echo "::error::third_party/$name is vendored but has no row in $doc (N-10)"
      status=1
    fi
  done
fi

[ $status -eq 0 ] && echo "pins and $doc agree"
exit $status
