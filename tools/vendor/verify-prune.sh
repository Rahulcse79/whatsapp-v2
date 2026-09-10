#!/usr/bin/env bash
#
# Asserts that no pruned directory is one its own tree's build files mention.
#
# ## Why this exists: the first prune rule was wrong, and it was wrong three times
#
# "Remove each tree's tests, docs and build scratch" sounds safe and is not. An autotools
# project routinely DECLARES those directories, and declaring one is enough to require it:
#
#   * OpenSSL's `doc/`, `demos/` and `fuzz/` sit in its unconditional `SUBDIRS` line, so
#     Configure walks into each and reads a `build.info` that is not there.
#   * Opus's `configure.ac:1039` declares `doc/Makefile`, so `autoreconf` stops with
#     "required file 'doc/Makefile.in' not found" — three minutes into a cross-compile, and
#     naming a file nobody deleted.
#   * pjproject's root `Makefile` names `pjsip-apps/src/pjsua/android`.
#   * pjproject's `configure-android:102` runs
#     `ndk-build -C pjsip-apps/src/samples/android_sample` as its NDK PROBE, and reads
#     `pjsip-apps/src/swig/java/android/jni/Application.mk`. Deleting either stopped the
#     build with "failed to run ndk-build, check ANDROID_NDK_ROOT env var" — a message
#     about an environment variable that was set correctly.
#
# That last one got through the FIRST version of this check, because `configure-android`
# is a hand-written shell script and the file list only looked for `configure`. Hence
# `-name 'configure-*'` below: a build system is whatever the tree calls its build system,
# not whatever autotools would have called it.
#
# Every one of those is discovered minutes in, with a message that points at a symptom. So
# the question "does this tree's build know about the directory I am deleting?" is asked
# here, in seconds, before anything is committed.
#
# **The real rule: prune only what the build files never mention.** Not "tests and docs".
#
# ## What this check CANNOT see, stated so nobody trusts it further than it goes
#
# It greps for pruned PATHS. A build file that names a directory as a bare word — pjproject's
# `swig/Makefile:5` says `LANG = java csharp`, and `csharp` is a directory — is invisible to
# it. That one cost a fifth failed run, four minutes in, after the Java wrapper had already
# linked. The remedy there was to name the target (`make java`) rather than restore a binding
# nobody consumes; the general remedy is to read the tree's own build files when pruning
# something whose name could also be a target.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
source tools/vendor/pins.sh

status=0

# The files a tree's build system actually lives in. Not exhaustive and cannot be — the
# point is to catch the ordinary ones, so that pruning something load-bearing takes a
# deliberate act nobody can call an accident.
build_files() {
  find "third_party/$1" -type f \( \
      -name Makefile -o -name 'Makefile.*' -o -name 'configure' -o -name 'configure.ac' \
      -o -name 'configure-*' -o -name 'aconfigure.ac' -o -name 'autogen.sh' \
      -o -name 'build.info' -o -name 'CMakeLists.txt' \
      -o -name '*.mk' -o -name 'build.mak.in' \) 2>/dev/null
}

check_tree() {
  local tree="$1"; shift
  [ -d "third_party/$tree" ] || return 0
  local files; files="$(build_files "$tree")"
  [ -n "$files" ] || return 0

  local path
  for path in "$@"; do
    # Skip a path that is still present — it was not pruned after all.
    [ -e "third_party/$tree/$path" ] && continue

    # The ONE deliberate exception, named rather than the check being loosened.
    #
    # OpenSSL's `test/` is referenced from `build.info` — but the reference is GUARDED:
    # `IF[{- !$disabled{tests} -}] SUBDIRS=test`, and every Configure in this repository
    # passes `no-tests`, which `verify-openssl-prune.sh` asserts separately along with the
    # guard's continued existence. The `demos/*/Makefile` references are in demo programs
    # that are never built. Proven empirically: OpenSSL cross-compiled green with `test/`
    # absent before this check was written.
    #
    # 89 MB — two thirds of the whole saving — rides on this exception, which is why it has
    # its own checker rather than a line in an allowlist.
    if [ "$tree/$path" = "openssl/test" ]; then
      echo "  (openssl/test: referenced but GUARDED — see tools/vendor/verify-openssl-prune.sh)"
      continue
    fi
    local hits
    hits="$(printf '%s\n' "$files" | xargs grep -lE "(^|[[:space:]=:/])${path}([[:space:]/]|\$)" 2>/dev/null | head -3 || true)"
    if [ -n "$hits" ]; then
      echo "::error::third_party/$tree/$path is pruned, and the tree's own build files name it:"
      printf '  %s\n' $hits
      echo "  A pruned directory the build declares is a build that fails minutes in, with a"
      echo "  message about a file nobody deleted. Restore it, or prove the reference is dead."
      status=1
    fi
  done
}

check_tree pjproject "${PJPROJECT_PRUNE[@]}"
check_tree openssl   "${OPENSSL_PRUNE[@]}"
check_tree opus      "${OPUS_PRUNE[@]}"
check_tree libvpx    "${LIBVPX_PRUNE[@]}"

# OpenSSL's test/ is the deliberate exception and it is checked properly elsewhere: its
# SUBDIRS entry is GUARDED and every Configure passes no-tests.
if [ $status -eq 0 ]; then
  echo "every pruned directory is unreferenced by its tree's build files"
fi
exit $status
