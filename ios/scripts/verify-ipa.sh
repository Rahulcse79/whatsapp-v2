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

platform="unknown"
if otool -l "$binary" 2>/dev/null | grep -q "LC_BUILD_VERSION"; then
  platform="$(otool -l "$binary" | awk '/LC_BUILD_VERSION/{f=1} f&&/platform/{print $2; exit}')"
elif otool -l "$binary" 2>/dev/null | grep -q "LC_VERSION_MIN_IPHONEOS"; then
  platform="2"
fi
case "$platform" in
  1)  echo "  platform: macOS                 -> WRONG" ;;
  2)  echo "  platform: iOS device            -> OK" ;;
  7)  echo "  platform: iOS Simulator         -> WILL NOT INSTALL ON A DEVICE" ;;
  *)  echo "  platform: $platform (see 'otool -l | grep -A4 LC_BUILD_VERSION')" ;;
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
if [ "$platform" = "2" ] && [ "$missing" -eq 0 ]; then
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
