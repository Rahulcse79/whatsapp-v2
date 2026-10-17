#!/usr/bin/env bash
#
# Says what an .ipa actually is, rather than what its name claims.
#
# Written because "the IPA was generated" is not the same statement as "the IPA will
# install on a device", and the four things that separate them are all checkable with
# tools that ship in the Command Line Tools — no Xcode required. This script therefore
# runs on the development machine that cannot build the thing it is checking.
#
#   1. Is the Mach-O arm64, and is it a DEVICE build? A simulator build is also arm64 on
#      Apple silicon, and the only thing that distinguishes them is the platform in the
#      LC_BUILD_VERSION load command. An IPA full of simulator slices uploads happily to
#      BrowserStack and fails at install.
#   2. Is it signed, and with what?
#   3. Is there an embedded provisioning profile, and which devices does it allow?
#   4. Does the Info.plist carry the things a VoIP app needs — the background modes and
#      the usage descriptions — without which iOS kills the app at the first call.
set -uo pipefail

ipa="${1:-}"
[ -f "$ipa" ] || { echo "usage: $(basename "$0") <path to .ipa>" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$ipa" -d "$work"

app="$(find "$work/Payload" -maxdepth 1 -name '*.app' | head -1)"
[ -n "$app" ] || { echo "FAIL: no Payload/*.app inside the archive - this is not an IPA" >&2; exit 1; }

name="$(basename "$app" .app)"
binary="$app/$name"
[ -f "$binary" ] || binary="$(find "$app" -maxdepth 1 -type f -perm +111 | head -1)"

echo "app bundle     : $(basename "$app")"
echo "uncompressed   : $(du -sh "$app" | cut -f1)"

# ---------------------------------------------------------------- 1. architecture
echo
echo "--- architecture ---"
lipo -info "$binary" 2>/dev/null | sed 's/^/  /' || file "$binary" | sed 's/^/  /'

# The platform, read ONCE into a file and then parsed.
#
# This was three separate `otool -l "$binary" | grep …` pipelines, and it reported
# `platform: unknown` for an IPA that was demonstrably an iOS device build — twice,
# both times on the first run after downloading a fresh artifact, and never on a
# re-run. The root cause was never pinned down; the candidates were all properties of
# the construct rather than of the IPA (`set -o pipefail` turning a short-circuiting
# `grep -q` into a failed pipeline when otool is still writing, and running otool
# three times on a 14 MB binary for one answer).
#
# A verifier that intermittently says "not ready" about a good artifact is worse than
# no verifier, because the next person re-runs it until it agrees. So: one invocation,
# one file, no pipeline, no second opinion needed.
load_commands="$work/load-commands.txt"
otool -l "$binary" > "$load_commands" 2>/dev/null || true

platform="unknown"
if command -v vtool >/dev/null 2>&1 && vtool -show-build "$binary" >/dev/null 2>&1; then
    # vtool prints the platform symbolically (IOS / IOSSIMULATOR / MACOS), which is
    # both clearer in the output and immune to the numbering changing.
    case "$(vtool -show-build "$binary" 2>/dev/null | awk '/platform/{print $2; exit}')" in
        IOS)           platform="2" ;;
        IOSSIMULATOR)  platform="7" ;;
        MACOS)         platform="1" ;;
    esac
fi
if [ "$platform" = "unknown" ] && [ -s "$load_commands" ]; then
    platform="$(awk '/LC_BUILD_VERSION/{f=1} f && /platform/ {print $2; exit}' "$load_commands")"
    # Pre-LC_BUILD_VERSION binaries carry LC_VERSION_MIN_IPHONEOS instead, which exists
    # only on iOS and therefore answers the question by its presence.
    if [ -z "$platform" ] && grep -q "LC_VERSION_MIN_IPHONEOS" "$load_commands"; then
        platform="2"
    fi
    [ -n "$platform" ] || platform="unknown"
fi

case "$platform" in
  1)  echo "  platform: macOS                 -> WRONG" ;;
  2)  echo "  platform: iOS device            -> OK" ;;
  7)  echo "  platform: iOS Simulator         -> WILL NOT INSTALL ON A DEVICE" ;;
  *)  echo "  platform: could not be determined"
      echo "            (no LC_BUILD_VERSION and no LC_VERSION_MIN_IPHONEOS — see $load_commands)" ;;
esac
if lipo -info "$binary" 2>/dev/null | grep -q arm64; then
  echo "  arm64 present: yes"
else
  echo "  arm64 present: NO -> will not run on any current iPhone"
fi

# ---------------------------------------------------------------- 2. signature
echo
echo "--- signature ---"
signed="unknown"
signinfo="$(codesign -dvvv "$app" 2>&1 || true)"
if echo "$signinfo" | grep -q "code object is not signed"; then
  signed="no"
  echo "  UNSIGNED."
  echo "  Fine for BrowserStack, which re-signs. NOT installable through Xcode as it is."
elif echo "$signinfo" | grep -q "Signature=adhoc"; then
  signed="adhoc"
  echo "  AD-HOC signed (no identity, no team)."
  echo "  Same practical status as unsigned: a device will refuse it until it is re-signed."
elif [ -n "$signinfo" ]; then
  signed="yes"
  # Fall back to printing everything rather than nothing if the grep matches no line —
  # an empty "signature" section reads as "unsigned", which is a different claim.
  matched="$(echo "$signinfo" | grep -E "Authority|TeamIdentifier|Identifier=|Signature=" || true)"
  if [ -n "$matched" ]; then echo "$matched" | sed 's/^/  /'
  else echo "$signinfo" | head -6 | sed 's/^/  /'; fi
else
  signed="unknown"
  echo "  codesign said nothing at all - treat as unsigned."
fi

# ---------------------------------------------------------------- 3. profile
echo
echo "--- provisioning profile ---"
profile="$app/embedded.mobileprovision"
if [ -f "$profile" ]; then
  # A .mobileprovision is CMS-wrapped; the plist inside is what carries the device list.
  security cms -D -i "$profile" > "$work/profile.plist" 2>/dev/null || true
  if [ -s "$work/profile.plist" ]; then
    for key in Name TeamName ExpirationDate; do
      value="$(/usr/libexec/PlistBuddy -c "Print :$key" "$work/profile.plist" 2>/dev/null || echo '-')"
      printf "  %-16s %s\n" "$key" "$value"
    done
    devices="$(/usr/libexec/PlistBuddy -c "Print :ProvisionedDevices" "$work/profile.plist" 2>/dev/null \
               | grep -c '^ ' || echo 0)"
    echo "  devices          $devices"
    [ "$devices" = "0" ] && echo "  (no device list: an App Store or enterprise profile)"
  fi
else
  echo "  none. Expected for an unsigned build; a signed one is missing something."
fi

# ---------------------------------------------------------------- 3b. contents
#
# "An IPA was produced" and "an IPA with the SIP stack in it was produced" are
# different claims, and only the second one is worth making. A link line that quietly
# dropped libpjproject.a, or a Shared.xcframework that was searched for and not found,
# both yield a perfectly valid IPA of an app that cannot place a call.
#
# `nm -U` lists defined symbols. Matching without anchoring on T/t deliberately: static
# linking and LTO move symbols between external and local, and this is asking whether
# the code is in the binary, not what its linkage is.
echo
echo "--- what is linked in ---"
contents_fail=0
check_symbol() {
    local label="$1" pattern="$2" requirement="$3"
    local n; n="$(nm -U "$binary" 2>/dev/null | grep -cE "$pattern" || true)"
    if [ "${n:-0}" -gt 0 ]; then
        printf "  %-34s %s symbol(s)\n" "$label" "$n"
    elif [ "$requirement" = "required" ]; then
        printf "  %-34s MISSING - the app cannot work without it\n" "$label"
        contents_fail=$((contents_fail + 1))
    else
        printf "  %-34s absent\n" "$label"
    fi
}

check_symbol "PJSIP (pjsua)"        "_pjsua_(create|call_make_call|acc_add)$"   required
check_symbol "PJSIP video"          "_pjsua_call_set_vid_strm$"                 required
check_symbol "shared Kotlin module" "_SharedSharedFacade"                       required
check_symbol "OpenSSL"              "_SSL_CTX_new$"                             optional
check_symbol "SRTP"                 "_srtp_protect$"                            optional

echo "  codecs registered:"
nm -U "$binary" 2>/dev/null \
  | grep -oE "_pjmedia_codec_[a-z0-9_]+_init$" \
  | sed -E 's/_pjmedia_codec_(.*)_init/\1/' | sort -u | tr '\n' ' ' | sed 's/^/     /'
echo

# One capture, read twice — by the listing and by the per-framework check. Having the
# two read different sources is how the previous version printed CallKit in its own
# list and then reported CallKit as not linked, in adjacent lines.
linked="$work/linked.txt"
otool -L "$binary" > "$linked" 2>/dev/null || true

echo "  frameworks:"
grep -oE "/System/Library/Frameworks/[A-Za-z]+\.framework" "$linked" 2>/dev/null \
  | sed 's|.*/||;s|\.framework||' | sort -u | tr '\n' ' ' | sed 's/^/     /'
echo
for required_framework in CallKit PushKit AVFoundation; do
  if grep -q "/$required_framework\.framework" "$linked" 2>/dev/null; then
    continue
  fi
  echo "  $required_framework is NOT linked - a VoIP app needs it"
  contents_fail=$((contents_fail + 1))
done

# ---------------------------------------------------------------- 4. Info.plist
echo
echo "--- Info.plist ---"
plist="$app/Info.plist"
for key in CFBundleIdentifier CFBundleShortVersionString CFBundleVersion MinimumOSVersion; do
  printf "  %-28s %s\n" "$key" "$(/usr/libexec/PlistBuddy -c "Print :$key" "$plist" 2>/dev/null || echo '-')"
done

echo "  UIBackgroundModes:"
/usr/libexec/PlistBuddy -c "Print :UIBackgroundModes" "$plist" 2>/dev/null \
  | grep -E '^\s+\S' | sed 's/^/     /' || echo "     (none)"

missing=0
for key in NSMicrophoneUsageDescription NSCameraUsageDescription NSLocalNetworkUsageDescription; do
  if /usr/libexec/PlistBuddy -c "Print :$key" "$plist" >/dev/null 2>&1; then
    printf "  %-34s present\n" "$key"
  else
    printf "  %-34s MISSING - iOS will terminate the app when it is needed\n" "$key"
    missing=$((missing + 1))
  fi
done

echo
if [ "$platform" = "2" ] && [ "$missing" -eq 0 ] && [ "${contents_fail:-0}" -eq 0 ]; then
  echo "VERDICT: a genuine iOS device build."
  if [ -f "$profile" ] && [ "$signed" = "yes" ]; then
    echo "         Signed and provisioned - installable through Xcode or Configurator."
  else
    echo "         Not installable as it stands (signed=$signed, profile=$([ -f "$profile" ] && echo yes || echo no))."
    echo "         Upload to BrowserStack, which re-signs, or re-sign it locally."
  fi
else
  echo "VERDICT: NOT ready. See the lines above."
  exit 1
fi
