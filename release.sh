#!/usr/bin/env bash
#
# One command from the checkout you are on to a published GitHub release with an APK in it.
#
#   ./release.sh                  1.0.3 -> 1.0.4: bump, commit, push, CI, arm64-v8a APK, publish
#   ./release.sh --minor          1.0.3 -> 1.1.0
#   ./release.sh --major          1.0.3 -> 2.0.0
#   ./release.sh --version 1.2.0  exactly this version; the current one means "retry, no bump"
#   ./release.sh --all-abis       arm64-v8a, armeabi-v7a and x86_64 -- three native builds
#   ./release.sh --reuse-native   skip the native cross-compile, reuse pjsip/build/generated/jniLibs
#   ./release.sh --skip-r8        do not build the minified release variant
#   ./release.sh --skip-ci        publish without waiting for CI to pass
#   ./release.sh --no-publish     everything but the last step; the release stays a draft
#   ./release.sh --dry-run        print what would happen and change nothing
#
# ## What one run does, in order
#
#   1. bumps versionCode and versionName in app/build.gradle.kts, and commits that
#   2. pushes the branch, and on any branch but main dispatches the CI workflow for it
#      (CI runs on its own only for main and for pull requests)
#   3. opens the draft release for the new tag, with notes generated from the commits
#   4. builds, checks and uploads the APK -- tools/release.sh, which asserts the SIP stack
#      is in every ABI packaged, 16 KB page alignment, and that R8 ran and left a mapping
#   5. waits for CI on the pushed commit, and publishes only when it is green
#
# A failure before the push undoes the bump commit, so the tree is as it was. A failure
# after it leaves the commit and the draft where they are: fix the cause and run again with
# --version <that same version>, which skips the bump and carries on from the build.
#
# ## Why arm64-v8a alone by default
#
# Every handset this is tested on is arm64, and each further ABI is another native
# cross-compile of PJSIP and the Lyra closure -- about half an hour each the first time.
# tools/release.sh says so when the APK carries fewer than three; --all-abis builds them.
#
# ## Why it releases from whatever branch you are on
#
# The tag names a commit, and the commit should be the one the APK was built from. Insisting
# on main would put a merge nobody has tested on a phone between "it works on mine" and "it
# is released". The branch is printed, so releasing from the wrong one is something you see,
# not something you discover from the tag later.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
note() { printf '\033[36m%s\033[0m\n' "$1"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$1" >&2; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

bump="patch"
explicit_version=""
all_abis=0
tools_args=()
skip_ci=0
publish=1
dry_run=0

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0; }
is_semver() { case "$1" in [0-9]*.[0-9]*.[0-9]*) return 0 ;; *) return 1 ;; esac; }

while [ $# -gt 0 ]; do
  case "$1" in
    --minor) bump="minor"; shift ;;
    --major) bump="major"; shift ;;
    --version)
      [ $# -ge 2 ] || die "--version needs a value"
      is_semver "$2" || die "--version '$2' is not MAJOR.MINOR.PATCH"
      explicit_version="$2"; shift 2 ;;
    --all-abis) all_abis=1; shift ;;
    --reuse-native|--skip-r8) tools_args+=("$1"); shift ;;
    --skip-ci) skip_ci=1; shift ;;
    --no-publish) publish=0; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ------------------------------------------------------------------ preconditions

command -v gh >/dev/null 2>&1 || die "the GitHub CLI (gh) is not installed."
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"
[ -x tools/release.sh ] || die "tools/release.sh is missing or not executable; this script is its first half."

branch="$(git branch --show-current)"
[ -n "$branch" ] || die "HEAD is detached. Check out the branch you are releasing from."

if [ -n "$(git status --porcelain)" ]; then
  # The tag names a commit. Uncommitted work would ship inside the APK under a tag that
  # does not contain it, and nobody could ever reproduce the artifact from the tag.
  [ "$dry_run" -eq 1 ] && warn "the working tree is dirty; a real run would stop here." \
    || die "the working tree is dirty. Commit or stash first -- the release must be reproducible from its tag."
fi

file="app/build.gradle.kts"
version_re='s/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p'
code_re='s/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p'
current="$(sed -n -E "$version_re" "$file")"
code="$(sed -n -E "$code_re" "$file")"
[ -n "$current" ] && [ -n "$code" ] || die "could not read versionName/versionCode from $file"

is_semver "$current" || die "versionName '$current' in $file is not MAJOR.MINOR.PATCH"

# ------------------------------------------------------------------ the version

IFS=. read -r major minor patch <<<"$current"
retry=0
if [ -n "$explicit_version" ]; then
  if [ "$explicit_version" = "$current" ]; then
    # The retry path: the bump commit already exists, so nothing is bumped or committed and
    # the run picks up from the push.
    retry=1
  fi
  version="$explicit_version"
else
  case "$bump" in
    patch) version="$major.$minor.$((patch + 1))" ;;
    minor) version="$major.$((minor + 1)).0" ;;
    major) version="$((major + 1)).0.0" ;;
  esac
fi
tag="v$version"
if [ "$retry" -eq 1 ]; then new_code="$code"; else new_code="$((code + 1))"; fi

# Released means a tag, or a release that is not a draft. A draft is reusable -- it is what
# the Release workflow opens on a merge, and what a failed earlier run of this script leaves.
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
  die "$tag is already a tag in this clone. Pick another version, or delete the tag if it was a mistake."
fi
existing="$(gh release view "$tag" --json isDraft,targetCommitish,url -q '"\(.isDraft) \(.targetCommitish) \(.url)"' 2>/dev/null || true)"
if [ -n "$existing" ]; then
  case "$existing" in
    false*) die "$tag is already published: ${existing#false * }" ;;
  esac
fi

# The Release workflow opens a draft for the version in the file whenever a merge lands on
# main, so the version being bumped past here may have an empty draft waiting. Bumping past
# it is usually right -- it was never shipped -- but it should not be quiet.
if [ "$retry" -eq 0 ]; then
  stale="$(gh release view "v$current" --json isDraft,assets -q 'select(.isDraft and (.assets | length == 0)) | "v'"$current"'"' 2>/dev/null || true)"
  if [ -n "$stale" ]; then
    warn "v$current is an unpublished draft with no APK in it. This run bumps past it to $tag."
    warn "  to release $current instead:  ./release.sh --version $current"
    warn "  to drop the empty draft:       gh release delete v$current --yes"
  fi
fi

# ------------------------------------------------------------------ the plan

abi_note="arm64-v8a"
[ "$all_abis" -eq 1 ] && abi_note="all three ABIs"
ci_note="wait for CI on the pushed commit, publish only if green"
[ "$skip_ci" -eq 1 ] && ci_note="publish WITHOUT waiting for CI (--skip-ci)"
[ "$publish" -eq 0 ] && ci_note="leave the release a draft (--no-publish)"

step "release $tag from $branch"
printf '  version   %s (code %s)  ->  %s (code %s)%s\n' "$current" "$code" "$version" "$new_code" \
  "$([ "$retry" -eq 1 ] && echo '   [retry: no bump, no commit]')"
printf '  commit    %s\n' "$(git log -1 --format='%h %s')"
printf '  branch    %s%s\n' "$branch" "$([ "$branch" != main ] && echo '   (not main: CI will be dispatched for it)')"
printf '  apk       %s%s\n' "$abi_note" "$([ ${#tools_args[@]} -gt 0 ] && echo "  ${tools_args[*]}")"
printf '  then      %s\n' "$ci_note"
[ "$branch" = main ] || warn "releasing from '$branch', not main -- the tag will point at a commit main does not have yet."

if [ "$dry_run" -eq 1 ]; then
  note "--dry-run: nothing changed."
  exit 0
fi

# ------------------------------------------------------------------ bump and commit

start_sha="$(git rev-parse HEAD)"
committed=0
pushed=0

# Undo the bump commit if the run dies before it was pushed. The tree was clean at the
# start (checked above), so a hard reset to the recorded commit restores exactly that
# state. After the push there is nothing to undo -- the commit is public -- and the
# message says how to carry on instead.
cleanup() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "$committed" -eq 1 ] && [ "$pushed" -eq 0 ]; then
    git reset --hard "$start_sha" >/dev/null
    warn "undid the local bump commit; the tree is back at $(git log -1 --format=%h)."
  elif [ "$status" -ne 0 ] && [ "$pushed" -eq 1 ]; then
    warn "the bump commit is pushed. Fix the cause, then:  ./release.sh --version $version"
  fi
}
trap cleanup EXIT

if [ "$retry" -eq 0 ]; then
  step "bumping $file to $version (code $new_code)"
  sed -i '' -E \
    -e "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$new_code/" \
    -e "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*\")[^\"]+\"/\1$version\"/" \
    "$file"
  # Read it back: a sed that matched nothing exits 0, and a release tagged with a version
  # the manifest does not carry is the exact mistake this whole flow exists to prevent.
  [ "$(sed -n -E "$version_re" "$file")" = "$version" ] || die "failed to write versionName into $file"
  [ "$(sed -n -E "$code_re" "$file")" = "$new_code" ] || die "failed to write versionCode into $file"
  git add "$file"
  git commit -q -m "chore(release): $tag" -m "versionCode $code -> $new_code, versionName $current -> $version."
  committed=1
  echo "  $(git log -1 --format='%h %s')"
fi
sha="$(git rev-parse HEAD)"

# ------------------------------------------------------------------ push, and start CI

step "pushing $branch"
git push -u origin "$branch"
pushed=1

if [ "$skip_ci" -eq 0 ] && [ "$publish" -eq 1 ] && [ "$branch" != main ]; then
  # CI's own triggers are main and pull requests. A feature branch gets a run only if
  # somebody asks, so ask now -- it runs on GitHub while the APK builds here.
  if gh workflow run ci.yml --ref "$branch"; then
    echo "  CI dispatched for $branch (${sha:0:7})"
  else
    warn "could not dispatch CI for $branch; the wait below will say so if no run appears."
  fi
fi

# ------------------------------------------------------------------ the draft

step "the draft release"
if [ -z "$existing" ]; then
  # `|| true` on the create: on main, the Release workflow opens the same draft when the
  # push lands, and losing that race is not a failure -- the check below is the truth.
  gh release create "$tag" --draft --title "$tag" --target "$sha" --generate-notes >/dev/null 2>&1 \
    || gh release create "$tag" --draft --title "$tag" --target "$sha" --notes "Release $tag." >/dev/null 2>&1 \
    || true
  gh release view "$tag" >/dev/null 2>&1 || die "could not open the draft release for $tag."
  echo "  opened $tag"
else
  # A draft has no tag yet, so its target can still be moved to the commit this APK is
  # built from -- which on a retry, or a draft the workflow opened for main, it is not.
  target="${existing#true }"; target="${target%% *}"
  if [ "$target" != "$sha" ]; then
    gh release edit "$tag" --target "$sha" >/dev/null
    echo "  retargeted the existing draft from ${target:0:7} to ${sha:0:7}"
  else
    echo "  reusing the existing draft"
  fi
fi

# ------------------------------------------------------------------ build, check, upload

step "building and checking the APK (tools/release.sh)"
half_args=(--no-publish)
[ "$all_abis" -eq 0 ] && half_args+=(--abi arm64-v8a)
half_args+=("${tools_args[@]+"${tools_args[@]}"}")
./tools/release.sh "${half_args[@]}"

# ------------------------------------------------------------------ CI, then publish

if [ "$publish" -eq 0 ]; then
  step "done: $tag is a draft with the APK attached (--no-publish)"
  gh release view "$tag" --json url -q .url
  exit 0
fi

if [ "$skip_ci" -eq 0 ]; then
  step "waiting for CI on ${sha:0:7}"
  run_id=""
  # The run is created a few seconds after the push or the dispatch; the build above took
  # minutes, so it is normally there already. Two minutes of patience covers a slow queue.
  for _ in $(seq 1 24); do
    run_id="$(gh run list --workflow ci.yml --commit "$sha" --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)"
    [ -n "$run_id" ] && break
    sleep 5
  done
  [ -n "$run_id" ] || die "no CI run appeared for $sha. The draft has the APK; publish by hand once CI is green:
  gh release edit $tag --draft=false
  or publish without it:  ./release.sh --version $version --skip-ci"
  if ! gh run watch "$run_id" --exit-status --interval 20; then
    die "CI did not pass for ${sha:0:7}. The APK is attached to the draft; it stays unpublished.
  $(gh run view "$run_id" --json url -q .url)"
  fi
  echo "  CI is green for ${sha:0:7}"
fi

step "publishing $tag"
gh release edit "$tag" --draft=false >/dev/null
# GitHub creates the tag at the moment of publishing; learn about it here rather than at
# some confusing later point.
git fetch --tags --quiet || true
printf '\n\033[32mreleased\033[0m  %s\n' "$(gh release view "$tag" --json url -q .url)"
gh release view "$tag" --json assets -q '.assets[] | "  \(.name)  \(.size) bytes"'
