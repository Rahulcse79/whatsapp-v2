#!/usr/bin/env bash
#
# Every check the iOS half can run WITHOUT an iOS SDK.
#
# ## What a pass here means, precisely
#
# It means: the Swift parses, the shell parses, the Python compiles, every plist and
# JSON file is well formed, the Xcode project is a valid plist with no dangling object
# references, the generator is deterministic, and every Objective-C method the Swift
# calls actually exists.
#
# It does NOT mean the app builds. Type checking needs the iOS SDK — `swiftc -parse`
# resolves no imports — so a wrong argument type, a missing protocol conformance or a
# Kotlin/Native export name that differs from what Swift expects will all pass here and
# fail on a Mac with Xcode. That is a real limit and not a soft one; see
# docs/ios-port.md §4 for the list of what remains unverified.
#
# Run on any Mac, including one with only the Command Line Tools.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_root="$(cd "$here/.." && pwd)"
repo_root="$(cd "$ios_root/.." && pwd)"
failures=0

step() { printf '\n\033[36m── %s\033[0m\n' "$1"; }
check() { if "$@"; then :; else failures=$((failures + 1)); fi; }

step "Swift syntax (swiftc -parse)"
check "$here/check-swift-syntax.sh"

step "Shell scripts (bash -n)"
ok=1
for f in "$here"/*.sh "$repo_root/build.sh"; do
  bash -n "$f" || { echo "  FAIL $f"; ok=0; }
done
[ "$ok" -eq 1 ] && echo "  $(ls "$here"/*.sh "$repo_root/build.sh" | wc -l | tr -d ' ') scripts OK" \
                || failures=$((failures + 1))

step "Python (py_compile)"
check python3 -m py_compile "$here/generate-xcodeproj.py" "$here/check-bridge-contract.py"
echo "  OK"

step "Property lists (plutil -lint)"
check plutil -lint \
  "$ios_root/WhatsAppV2/Info.plist" \
  "$ios_root/WhatsAppV2/WhatsAppV2.entitlements" \
  "$ios_root/WhatsAppV2.xcodeproj/project.pbxproj"

step "Asset catalogues and the scheme"
ok=1
while IFS= read -r f; do
  python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$f" || { echo "  FAIL $f"; ok=0; }
done < <(find "$ios_root" -name "Contents.json")
python3 -c "import sys,xml.etree.ElementTree as ET; ET.parse(sys.argv[1])" \
  "$ios_root/WhatsAppV2.xcodeproj/xcshareddata/xcschemes/WhatsAppV2.xcscheme" \
  || { echo "  FAIL scheme"; ok=0; }
[ "$ok" -eq 1 ] && echo "  OK" || failures=$((failures + 1))

step "The Xcode project is structurally sound"
check python3 - "$ios_root/WhatsAppV2.xcodeproj/project.pbxproj" <<'PY'
import plistlib, subprocess, sys
raw = subprocess.run(["plutil", "-convert", "xml1", "-o", "-", sys.argv[1]],
                     capture_output=True, check=True).stdout
d = plistlib.loads(raw)
objs = d["objects"]

# A dangling reference is the characteristic failure of a generated pbxproj, and Xcode
# reports it as "the project file cannot be opened" with no indication of which id is
# missing. Checking it here is the difference between a one-line fix and an afternoon.
missing = []
def walk(v):
    if isinstance(v, str) and len(v) == 24 and all(c in "0123456789ABCDEF" for c in v):
        if v not in objs: missing.append(v)
    elif isinstance(v, list):
        for i in v: walk(i)
    elif isinstance(v, dict):
        for i in v.values(): walk(i)
for obj in objs.values():
    for key, val in obj.items():
        if key != "buildSettings":
            walk(val)

target = next(v for v in objs.values() if v.get("isa") == "PBXNativeTarget")
sources = next(v for v in objs.values() if v.get("isa") == "PBXSourcesBuildPhase")
print(f"  {len(objs)} objects, {len(sources['files'])} sources, "
      f"{len(target['buildPhases'])} phases, {len(missing)} dangling")
if d["rootObject"] not in objs:
    print("  FAIL: rootObject does not resolve"); sys.exit(1)
if missing:
    print("  FAIL: dangling ids:", missing[:5]); sys.exit(1)
sys.exit(0)
PY

step "The generator is deterministic"
before="$(shasum -a 256 "$ios_root/WhatsAppV2.xcodeproj/project.pbxproj" | cut -d' ' -f1)"
python3 "$here/generate-xcodeproj.py" >/dev/null
after="$(shasum -a 256 "$ios_root/WhatsAppV2.xcodeproj/project.pbxproj" | cut -d' ' -f1)"
if [ "$before" = "$after" ]; then
  echo "  OK (committed project is up to date)"
else
  echo "  FAIL: regenerating changed the project. Commit the regenerated file."
  failures=$((failures + 1))
fi

step "Kotlin common portability (JVM-only APIs in commonMain)"
check python3 "$here/check-common-portability.py"

step "Bridge contract (header / .mm / Swift)"
check python3 "$here/check-bridge-contract.py"

printf '\n\033[36m══ result ══\033[0m\n'
if [ "$failures" -eq 0 ]; then
  echo "all static checks passed"
  echo
  echo "This does NOT mean the app builds. Type checking, linking and everything"
  echo "at run time need Xcode - see docs/ios-port.md section 4."
else
  echo "$failures check(s) failed"
fi
exit "$failures"
