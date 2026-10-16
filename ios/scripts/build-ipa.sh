#!/usr/bin/env bash
#
# Archives the app and exports a .ipa.
#
# ## The two kinds of IPA this produces, and which one you want
#
#   --signed    (default) xcodebuild archive + -exportArchive with a real signing
#               identity. Installs on a device through Xcode, Apple Configurator or
#               TestFlight. Needs an Apple Developer account and a provisioning profile.
#
#   --unsigned  A device-architecture build with code signing switched off, packed into
#               a Payload/ directory by hand. This is NOT installable on a device as it
#               stands — iOS refuses an unsigned binary — but it is what BrowserStack
#               and similar services want, because they re-sign with their own
#               certificate before installing. It is also the only form this repository
#               can produce without anybody's credentials.
#
# Either way the result is a genuine arm64 device build. `ios/scripts/verify-ipa.sh`
# checks which one you got rather than taking the filename's word for it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_root="$(cd "$here/.." && pwd)"
repo_root="$(cd "$ios_root/.." && pwd)"

APP_NAME="WhatsAppV2"
PROJECT="$ios_root/$APP_NAME.xcodeproj"
CONFIGURATION="${CONFIGURATION:-Release}"
OUT_DIR="${OUT_DIR:-$repo_root/build/ios-ipa}"
DERIVED="$OUT_DIR/DerivedData"

mode="signed"
TEAM_ID="${IOS_TEAM_ID:-}"
EXPORT_METHOD="${IOS_EXPORT_METHOD:-development}"

for arg in "$@"; do
  case "$arg" in
    --signed)   mode="signed" ;;
    --unsigned) mode="unsigned" ;;
    --adhoc)    mode="signed"; EXPORT_METHOD="ad-hoc" ;;
    --team=*)   TEAM_ID="${arg#--team=}" ;;
    -h|--help)  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- preflight
command -v xcodebuild >/dev/null 2>&1 || {
  echo "::error::xcodebuild is not available. This needs a full Xcode." >&2; exit 1; }
[ -d "$PROJECT" ] || {
  echo "::error::$PROJECT does not exist. Run: python3 ios/scripts/generate-xcodeproj.py" >&2; exit 1; }
[ -f "$ios_root/Vendor/pjsip/lib/iphoneos/libpjproject.a" ] || {
  echo "::error::no device PJSIP build. Run: ios/scripts/build-pjsip-ios.sh --device" >&2; exit 1; }
[ -d "$ios_root/Frameworks/Shared.xcframework" ] || {
  echo "::error::no Shared.xcframework. Run: ios/scripts/build-shared-framework.sh" >&2; exit 1; }

mkdir -p "$OUT_DIR"
ipa="$OUT_DIR/$APP_NAME-$mode.ipa"
rm -f "$ipa"

if [ "$mode" = "unsigned" ]; then
  echo "==> building unsigned for a device (arm64)"
  # `generic/platform=iOS` and not a named device: a generic destination builds for the
  # device architecture without a device being attached, which is what CI needs.
  xcodebuild \
    -project "$PROJECT" -scheme "$APP_NAME" -configuration "$CONFIGURATION" \
    -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath "$DERIVED" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
    build

  app="$DERIVED/Build/Products/$CONFIGURATION-iphoneos/$APP_NAME.app"
  [ -d "$app" ] || { echo "::error::no $app after a successful build" >&2; exit 1; }

  # The IPA layout, which is the whole format: a zip with the .app inside Payload/.
  staging="$OUT_DIR/staging"
  rm -rf "$staging"; mkdir -p "$staging/Payload"
  cp -R "$app" "$staging/Payload/"
  ( cd "$staging" && zip -qry "$ipa" Payload )
  rm -rf "$staging"
else
  [ -n "$TEAM_ID" ] || {
    echo "::error::a signed build needs a team. Pass --team=ABCDE12345 or set IOS_TEAM_ID." >&2
    echo "  Find it at https://developer.apple.com/account -> Membership." >&2
    echo "  No account? Build with --unsigned; BrowserStack re-signs." >&2
    exit 1; }

  archive="$OUT_DIR/$APP_NAME.xcarchive"
  rm -rf "$archive"

  echo "==> archiving (team $TEAM_ID, method $EXPORT_METHOD)"
  xcodebuild \
    -project "$PROJECT" -scheme "$APP_NAME" -configuration "$CONFIGURATION" \
    -sdk iphoneos -destination 'generic/platform=iOS' \
    -archivePath "$archive" \
    -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$TEAM_ID" \
    archive

  options="$OUT_DIR/ExportOptions.plist"
  cat > "$options" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key><string>$EXPORT_METHOD</string>
	<key>teamID</key><string>$TEAM_ID</string>
	<key>signingStyle</key><string>automatic</string>
	<key>stripSwiftSymbols</key><true/>
	<!-- Off. On, Xcode rebuilds bitcode from the archive, and the vendored static
	     libraries carry none - the export then fails with a message about an
	     unsupported architecture rather than about bitcode. -->
	<key>compileBitcode</key><false/>
	<key>uploadSymbols</key><false/>
	<key>destination</key><string>export</string>
</dict>
</plist>
PLIST

  echo "==> exporting"
  xcodebuild -exportArchive -archivePath "$archive" \
    -exportOptionsPlist "$options" -exportPath "$OUT_DIR/export" \
    -allowProvisioningUpdates

  exported="$(find "$OUT_DIR/export" -name '*.ipa' | head -1)"
  [ -n "$exported" ] || { echo "::error::-exportArchive produced no .ipa" >&2; exit 1; }
  mv "$exported" "$ipa"
fi

echo
echo "================================================================"
echo "  $ipa"
echo "  $(du -h "$ipa" | cut -f1)"
echo "================================================================"
"$here/verify-ipa.sh" "$ipa"
