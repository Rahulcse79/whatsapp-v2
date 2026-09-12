#!/usr/bin/env bash
#
# Finishes the release that merging to `main` opened: builds the APK here, checks it,
# attaches it to the draft, and publishes.
#
# `./release.sh` at the repository root runs this as its second half, after bumping the
# version, pushing and opening the draft. Run this one directly to finish a draft the
# Release workflow already opened for a commit you have checked out.
#
#   ./tools/release.sh                  build all three ABIs, upload, publish
#   ./tools/release.sh --reuse-native   skip the cross-compile, reuse the libraries built
#   ./tools/release.sh --abi arm64-v8a  one ABI — a build that installs on ARM64 only
#   ./tools/release.sh --no-publish     upload the assets, leave the release a draft
#   ./tools/release.sh --skip-r8        do not build the minified release variant
#   ./tools/release.sh --yes            answer every "continue anyway?" with yes
#
# ## Why the APK is built here and not by CI
#
# `:pjsip` compiles libpjsua2.so from the vendored source in third_party/ and there is no
# prebuilt fallback (N-14) — so an APK is either a native cross-compile or it is an APK
# that dies on the first call. That cross-compile is ~3 minutes per ABI on top of the
# Lyra closure, and running it on a hosted runner for every merge is the cost this
# project decided not to pay. `.github/workflows/release.yml` therefore opens a DRAFT
# with notes and no artifact, and this script is the other half.
#
# ## What it checks, and why those checks are here
#
# Both checks below used to live in ci.yml and cannot any more, because neither has
# anything to look at without a native build:
#
#   * every ABI carries libpjsua2.so AND libc++_shared.so (N-6). An APK missing either
#     installs cleanly, launches, and raises UnsatisfiedLinkError on the first SIP call.
#   * R8 ran on the release variant and produced a mapping (Task 64, DoD 1). A release
#     build that silently skipped minification is one nobody has tested the shape of,
#     and the mapping is the only way a crash from it is ever deobfuscated.
#
# The signing identity is printed rather than assumed: a debug APK built here is signed
# with THIS machine's debug keystore, and Android refuses to install one over an APK
# signed by a different key.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
confirm() {
  [ "$assume_yes" -eq 1 ] && { echo "  (--yes) continuing."; return 0; }
  printf 'continue anyway? [y/N] '
  read -r reply
  case "$reply" in [yY]*) return 0 ;; *) die "stopped." ;; esac
}
note() { printf '\033[36m%s\033[0m\n' "$1"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$1" >&2; }

abi=""          # empty means all three
extra_args=()
publish=1
run_r8=1
assume_yes=0

usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --reuse-native) extra_args+=(--reuse-native); shift ;;
    # Replaces --all-abis rather than joining it: asking build.sh for both is a
    # contradiction and it would silently honour whichever came last.
    --abi) [ $# -ge 2 ] || die "--abi needs a value"; abi="$2"; shift 2 ;;
    --no-publish) publish=0; shift ;;
    --skip-r8) run_r8=0; shift ;;
    --yes|-y) assume_yes=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ------------------------------------------------------------------ preconditions

command -v gh >/dev/null 2>&1 || die "the GitHub CLI (gh) is not installed."
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"

version="$(sed -n -E 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts)"
code="$(sed -n -E 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts)"
[ -n "$version" ] || die "could not read versionName from app/build.gradle.kts"
tag="v$version"

note "releasing $tag (versionCode $code)"

if [ -n "$(git status --porcelain)" ]; then
  # The tag names a commit. Uncommitted work would ship inside the APK under a tag that
  # does not contain it, and nobody could ever reproduce the artifact from the tag.
  die "the working tree is dirty. Commit or stash before cutting a release."
fi

# The draft is normally opened by the Release workflow when the merge lands. Creating it
# here as well means the script also works when that run has not finished, or when a
# release is being cut by hand — the workflow's own step is idempotent for the same reason.
if ! gh release view "$tag" >/dev/null 2>&1; then
  warn "no release for $tag yet — creating the draft here."
  gh release create "$tag" --title "$tag" --draft \
    --notes "Release $tag. Notes pending." --target "$(git rev-parse HEAD)"
fi

target="$(gh release view "$tag" --json targetCommitish -q .targetCommitish)"
head="$(git rev-parse HEAD)"
if [ "$target" != "$head" ] && ! git merge-base --is-ancestor "$head" "$target" 2>/dev/null; then
  # Not fatal on its own — the tag may point at the merge commit and the checkout at the
  # same tree — but an APK built from a different commit than the one the release names
  # is the kind of mismatch that is only ever discovered from a bug report.
  warn "the release targets $target but HEAD is $head."
  warn "The APK you are about to upload is NOT built from the commit this release names."
  confirm
fi

# Publishing is the outward-facing half, so it is the half that should care whether the
# commit passed. The draft is opened by the workflow regardless — notes on a red commit
# cost nothing — but an APK with a download link is a claim that this build is good.
if [ "$publish" -eq 1 ]; then
  ci="$(gh run list --commit "$head" --workflow CI --limit 1 \
          --json conclusion,status,url -q '.[0]' 2>/dev/null || true)"
  case "$ci" in
    "") warn "no CI run found for $head — it may not have been pushed yet." ; confirm ;;
    *'"conclusion":"success"'*) echo "  ok  CI is green for $head" ;;
    *'"status":"completed"'*)   warn "CI did NOT pass for this commit: $ci" ; confirm ;;
    *)                          warn "CI has not finished for this commit: $ci" ; confirm ;;
  esac
fi

# ------------------------------------------------------------------ build

if [ -n "$abi" ]; then
  build_args=(--abi "$abi")
else
  build_args=(--all-abis)
fi
build_args+=("${extra_args[@]+"${extra_args[@]}"}")

note "building the debug APK (${build_args[*]})"
./build.sh "${build_args[@]}"

apk="$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)"
[ -n "$apk" ] || die "the build produced no APK."

# N-6, checked on the artifact itself rather than on the build that made it.
#
# The listing is taken ONCE into a variable and matched with bash's own `case`, rather
# than `unzip -l | grep -q` per library. Under `set -o pipefail` that pipeline reports
# FAILURE when the library is found early: `grep -q` exits at the first match, `unzip`
# gets SIGPIPE, and pipefail returns the signal. It "worked" only for whichever ABI
# happened to sort last in the archive.
note "checking the APK carries the native SIP stack"
listing="$(unzip -l "$apk")"
abis="$(printf '%s\n' "$listing" | sed -n -E 's#.*lib/([^/]+)/libpjsua2\.so.*#\1#p' | sort -u)"
[ -n "$abis" ] || die "$apk carries no libpjsua2.so — this APK cannot place a call."
for packaged in $abis; do
  for lib in libpjsua2.so libc++_shared.so; do
    case "$listing" in
      *"lib/$packaged/$lib"*) ;;
      *) die "$apk is missing lib/$packaged/$lib" ;;
    esac
  done
  echo "  ok  $packaged (libpjsua2.so, libc++_shared.so)"
done

# Said out loud rather than left implicit: a one-ABI APK is a legitimate thing to release
# for testing and a surprising thing to hand somebody with an x86_64 emulator.
count="$(printf '%s\n' $abis | wc -l | tr -d ' ')"
[ "$count" -eq 3 ] || warn "this APK carries $count ABI(s), not three: $(echo $abis | tr '\n' ' ')"

# §3: every 64-bit .so must be 16 KB page-size aligned. Android 15+ refuses to load one
# that is not, and the refusal arrives as a crash on somebody's specific handset rather
# than as anything the build said.
#
# 64-bit only, deliberately. Every device that ships 16 KB pages is 64-bit-only and a
# 32-bit process is 4 KB-paged on every device that has ever existed, so armeabi-v7a is
# built at 0x1000 by everyone shipping a SIP stack. Requiring 0x4000 of it asserts only
# that the stack is broken — which is what one CI run did to all 13 of its 32-bit
# libraries while all 32 of the 64-bit ones passed.
note "checking 16 KB page alignment (§3)"
readelf_bin="$(command -v llvm-readelf || true)"
if [ -z "$readelf_bin" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  readelf_bin="$(ls -1 "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
fi
[ -n "$readelf_bin" ] || die "no llvm-readelf to verify alignment with (looked on PATH and in the NDK)."

work="$(mktemp -d)"
unzip -q -o "$apk" 'lib/*' -d "$work"
checked=0
misaligned=0
while IFS= read -r so; do
  packaged="$(basename "$(dirname "$so")")"
  # `|| true`: a readelf that cannot read a file must produce an empty alignment and be
  # REPORTED below, not kill the script through `set -e` with nothing on screen.
  align="$("$readelf_bin" -lW "$so" 2>/dev/null | awk '/LOAD/ {print $NF}' | sort -u | head -1 || true)"
  [ -n "$align" ] || align="(unreadable)"
  case "$packaged" in
    arm64-v8a|x86_64)
      checked=$((checked + 1))
      case "$align" in
        0x4000|0x10000|0x40000) echo "  ok   $align  $packaged/$(basename "$so")" ;;
        *) echo "  BAD  $align  $packaged/$(basename "$so")"; misaligned=1 ;;
      esac
      ;;
    *) echo "  n/a  $align  $packaged/$(basename "$so")  (32-bit, always 4 KB-paged)" ;;
  esac
done < <(find "$work" -name '*.so')
rm -rf "$work"

[ "$misaligned" -eq 0 ] || die "a 64-bit native library is not 16 KB page-size aligned (§3)."
# An assertion that examined nothing passes for the wrong reason.
[ "$checked" -gt 0 ] || die "no 64-bit native library was examined — did the ABI names change?"
echo "  ok  all $checked 64-bit libraries are 16 KB page-size aligned"

note "APK: $(du -h "$apk" | cut -f1), $(basename "$apk")"

mapping=""
if [ "$run_r8" -eq 1 ]; then
  # Task 64, DoD 1. The native libraries are already built by now, so this is R8 and
  # packaging rather than a second cross-compile.
  #
  # Same ABI set as the debug APK, and the native tasks excluded: without the override
  # this variant asks :pjsip for all three ABIs and an NDK path it was never given, and a
  # one-ABI release stops here with "property 'ndkRoot' doesn't have a configured value".
  # The libraries it packages are the ones the debug build just produced or reused, and
  # the N-6 check above has already examined every one of them.
  note "building the minified release variant (R8)"
  ./gradlew :app:assembleRelease "-Ppjsip.abis=$(printf '%s\n' $abis | paste -sd, -)" \
    -x :pjsip:api:generatePjsua2Bindings -x :pjsip:buildPjsua2Native --stacktrace
  mapping=app/build/outputs/mapping/release/mapping.txt
  [ -s "$mapping" ] || die "R8 produced no mapping at $mapping. Was minification switched off?"
  echo "  ok  R8 ran; mapping is $(wc -l < "$mapping" | tr -d ' ') lines"
fi

# ------------------------------------------------------------------ upload

staged="${RUNNER_TEMP:-}"
[ -n "$staged" ] && [ -d "$staged" ] || staged="$(mktemp -d)"
asset="$staged/whatsapp-v2-$tag-debug.apk"
cp "$apk" "$asset"

note "signing identity of the APK being uploaded"
signer="$(command -v apksigner || true)"
if [ -z "$signer" ]; then
  # apksigner ships in build-tools and is rarely on PATH; the newest copy in the SDK is
  # as good as any for reading a certificate.
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  signer="$(ls -1 "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -n "$signer" ]; then
  "$signer" verify --print-certs "$asset" | grep -i "SHA-256 digest" || true
else
  # apksigner lives in build-tools and is not always on PATH; the digest is a nicety,
  # not a gate, so its absence must not stop a release.
  warn "apksigner is not on PATH or in the SDK — cannot print the signing certificate."
fi
if [ -z "${DEBUG_KEYSTORE_FILE:-}" ]; then
  # :app reads DEBUG_KEYSTORE_FILE from the environment (app/build.gradle.kts). Without
  # it AGP uses this machine's per-user debug key — which is NOT the key CI used for
  # v1.0.1 and v1.0.2, so this APK will not install over one of those.
  warn "DEBUG_KEYSTORE_FILE is not set: this APK is signed with this machine's own"
  warn "debug key, not the shared one earlier releases were signed with. Users"
  warn "updating from such a release need 'adb uninstall com.whatsappv2' first."
  warn "To keep the old identity, export DEBUG_KEYSTORE_FILE (and its password vars)"
  warn "pointing at the keystore behind the DEBUG_KEYSTORE_BASE64 repository secret."
fi

assets=("$asset")
if [ -n "$mapping" ]; then
  assets+=("$mapping")
fi

note "uploading $(basename "$asset")$([ -n "$mapping" ] && echo " and mapping.txt")"
gh release upload "$tag" "${assets[@]}" --clobber

if [ "$publish" -eq 1 ]; then
  gh release edit "$tag" --draft=false
  # A draft release has no git tag — GitHub creates it at the moment it is published —
  # so the local clone learns about it here rather than at some confusing later point.
  git fetch --tags --quiet || true
  note "published"
else
  note "left as a draft (--no-publish)"
fi

gh release view "$tag" --json url -q .url
