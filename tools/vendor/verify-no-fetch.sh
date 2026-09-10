#!/usr/bin/env bash
#
# Asserts that no vendored tree fetches anything at build time (N-2, §2.1.2).
#
# ## The one this was written for
#
# `third_party/opus/autogen.sh:12` called `dnn/download_model.sh`, which does
#
#     wget https://media.xiph.org/opus/models/opus_data-735117b.tar.gz
#
# A network fetch from inside a vendored tree defeats N-2 outright: the whole claim is that
# the source is IN this repository and the build reads what is checked out. And it would
# have passed CI indefinitely — every ubuntu runner has wget, so it would simply have
# downloaded, silently, on every build. It surfaced on a laptop that happens not to.
#
# The fix there was to vendor Opus from the RELEASE tarball rather than the GitHub tag
# archive: the release ships a generated `configure`, so `autogen.sh` never runs, and it
# carries the DNN weights as ten `dnn/*_data.c` files instead of downloading them.
#
# ## What this checks, and what it cannot
#
# It greps the build entry points for fetch commands. It will not catch a fetch buried in a
# Makefile rule that only fires for a target we never build, and it will not catch one
# spelled in a way it does not recognise. **The authoritative check is the egress-blocked CI
# job of §2.1.2** — a job that fails when a process opens a socket. This one is the cheap
# version that runs in seconds and catches the ordinary case before that job spends ten
# minutes proving the same thing.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
[ -d third_party ] || { echo "third_party/ does not exist — nothing to verify"; exit 0; }

status=0

# Entry points a build actually runs. Deliberately NOT every file: upstream test suites and
# documentation mention curl constantly, and flagging those trains people to ignore this.
entry_points() {
  find third_party -maxdepth 3 -type f \( \
      -name 'autogen.sh' -o -name 'configure' -o -name 'configure-*' \
      -o -name 'Makefile' -o -name 'Makefile.am' -o -name 'bootstrap*' \) 2>/dev/null
}

while IFS= read -r f; do
  [ -n "$f" ] || continue
  hits="$(grep -nE '^[^#]*\b(wget|curl)\b|git[[:space:]]+clone|download_model' "$f" 2>/dev/null | head -3 || true)"
  if [ -n "$hits" ]; then
    echo "::error::$f fetches at build time — a vendored tree must not (N-2):"
    printf '  %s\n' "$hits"
    status=1
  fi
done < <(entry_points)

if [ $status -eq 0 ]; then
  echo "no vendored build entry point fetches at build time"
fi
exit $status
