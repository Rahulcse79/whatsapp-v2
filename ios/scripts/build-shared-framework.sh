#!/usr/bin/env bash
#
# Builds the Kotlin Multiplatform `:shared` module into ios/Frameworks/Shared.xcframework.
#
# ## Why the framework is copied to a fixed path instead of referenced where Gradle puts it
#
# Gradle writes to `shared/build/XCFrameworks/<buildType>/Shared.xcframework`, and the
# build type is part of the path. An Xcode project pointing there would need its
# FRAMEWORK_SEARCH_PATHS to interpolate a Gradle build type from an Xcode configuration
# name — two naming schemes that do not line up (`Debug`/`Release` against
# `debug`/`release`, and Gradle's `assembleSharedDebugXCFramework` is not the same word
# either). One fixed path, chosen here, is simpler to read and impossible to get wrong.
#
# ## Static, so it is linked and not embedded
#
# `shared/build.gradle.kts` sets `isStatic = true`. A static framework is linked into the
# executable at build time: nothing to embed, nothing to sign, no `@rpath` at run time.
# Adding it to an "Embed Frameworks" phase would be a code-signing ERROR rather than a
# warning, so the generated project does not have one.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_root="$(cd "$here/.." && pwd)"
repo_root="$(cd "$ios_root/.." && pwd)"

CONFIGURATION="${1:-Release}"
case "$CONFIGURATION" in
  Debug|debug)     gradle_task="assembleSharedDebugXCFramework";   built="debug" ;;
  Release|release) gradle_task="assembleSharedReleaseXCFramework"; built="release" ;;
  *) echo "usage: $(basename "$0") [Debug|Release]" >&2; exit 2 ;;
esac

if ! xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
  echo "::error::no iOS SDK. The Kotlin/Native link step needs a full Xcode." >&2
  echo "  shared/build.gradle.kts declares the iOS targets only when one is present," >&2
  echo "  so without it the assemble task below does not even exist." >&2
  exit 1
fi

echo "==> ./gradlew :shared:$gradle_task"
( cd "$repo_root" && ./gradlew ":shared:$gradle_task" )

source_xcframework="$repo_root/shared/build/XCFrameworks/$built/Shared.xcframework"
[ -d "$source_xcframework" ] || {
  echo "::error::Gradle reported success but $source_xcframework is not there." >&2
  echo "  The usual cause is the iOS targets being skipped - check that" >&2
  echo "  'xcrun --sdk iphonesimulator --show-sdk-path' works for the user running Gradle." >&2
  exit 1; }

dest="$ios_root/Frameworks/Shared.xcframework"
mkdir -p "$ios_root/Frameworks"
rm -rf "$dest"
cp -R "$source_xcframework" "$dest"

echo "==> $dest"
# Say which slices it actually contains. An XCFramework missing the device slice builds
# for the simulator and fails at the very end of a device build, which is the most
# expensive moment to find out.
/usr/libexec/PlistBuddy -c "Print :AvailableLibraries" "$dest/Info.plist" 2>/dev/null \
  | grep -E "LibraryIdentifier|SupportedArchitectures|SupportedPlatform" | sed 's/^/    /' || true
