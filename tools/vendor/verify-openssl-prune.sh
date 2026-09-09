#!/usr/bin/env bash
#
# The OpenSSL `test/` prune is 89 MB — two thirds of the whole vendoring saving — and it is
# safe only while TWO things are simultaneously true. This asserts both.
#
#   1. OpenSSL's root build.info still guards `SUBDIRS=test` behind `!$disabled{tests}`.
#      If a future version makes it unconditional, Configure walks into a directory that
#      is not there and fails with a missing-build.info error nowhere near the cause.
#
#   2. Every `./Configure` invocation in this repository still passes `no-tests`. Without
#      it, `$disabled{tests}` is false, the guard opens, and the same failure occurs.
#
# A prune whose safety condition is not checked is a prune that breaks at the next bump.
# `doc/`, `demos/` and `fuzz/` are in the UNCONDITIONAL SUBDIRS line and are NOT pruned for
# exactly this reason — see tools/vendor/pins.sh.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
status=0

info="third_party/openssl/build.info"
if [ ! -f "$info" ]; then
  echo "third_party/openssl not vendored — nothing to verify"
  exit 0
fi

if [ -d third_party/openssl/test ]; then
  echo "third_party/openssl/test is present; the prune has been reverted and this check is moot"
else
  if ! grep -q 'IF\[{- !\$disabled{tests} -}\]' "$info"; then
    echo "::error::$info no longer guards SUBDIRS=test behind !\$disabled{tests}."
    echo "  The 89 MB test/ prune is no longer safe. Restore third_party/openssl/test/,"
    echo "  or re-establish the guard, before this build can configure."
    status=1
  fi

  # Every Configure invocation must carry no-tests.
  #
  # Backslash continuations are joined first: the flag is routinely on the line after
  # `./Configure`, and a naive per-line grep reports a false failure on a build that is
  # perfectly correct — which is worse than no check, because the fix is to weaken it.
  while IFS= read -r file; do
    joined="$(sed -e ':a' -e '/\\$/{N;s/\\\n//;ta' -e '}' "$file")"
    while IFS= read -r line; do
      case "$line" in
        *no-tests*) ;;
        *) echo "::error::$file: a ./Configure invocation does not pass no-tests:"
           echo "  $line"
           status=1 ;;
      esac
    done < <(printf '%s\n' "$joined" | grep '\./Configure' | grep -v '^[[:space:]]*#' || true)
    # tools/ is deliberately not scanned: this script mentions ./Configure in order to
    # look for it, and a checker that fails on its own source is a checker nobody keeps.
  done < <(grep -rl '\./Configure' .github/workflows/ pjsip/ 2>/dev/null || true)
fi

# The directories Configure walks unconditionally. Each must exist.
for d in crypto ssl apps util tools fuzz providers doc demos engines exporters; do
  if [ ! -d "third_party/openssl/$d" ]; then
    echo "::error::third_party/openssl/$d is missing and is an unconditional SUBDIR in $info"
    status=1
  fi
done

[ $status -eq 0 ] && echo "OpenSSL prune is safe: test/ is guarded, no-tests is passed, every unconditional SUBDIR present"
exit $status
