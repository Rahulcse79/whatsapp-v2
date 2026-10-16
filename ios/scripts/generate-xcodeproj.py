#!/usr/bin/env python3
"""Generates ios/WhatsAppV2.xcodeproj/project.pbxproj from the source tree.

WHY A GENERATOR AND NOT A HAND-WRITTEN PROJECT
----------------------------------------------
A `.pbxproj` is a 1000-line plist of 24-hex-digit object identifiers. Hand-editing one
is how merge conflicts become unresolvable, and a project written by a developer who
cannot open Xcode — which is the situation this port is in — cannot be checked by
opening it. A generator can be run, and its output can be linted (`plutil`), which makes
it the only part of the Xcode configuration that is verifiable without Xcode.

The generated file IS committed, so the project opens on a clean checkout with nothing
installed. Re-run this after adding or removing a source file.

DETERMINISM
-----------
Object identifiers are the first 24 hex digits of the MD5 of a stable string (the file's
path, or a fixed role name). So re-running with no source change produces a byte-identical
file and git sees nothing — which is the property that makes a generated, committed
artefact tolerable at all. MD5 here is a hash for naming, not a security primitive.

LAYOUT ASSUMPTIONS
------------------
* Sources live under ios/WhatsAppV2/.
* PJSIP's headers and one merged static library come from ios/Vendor/pjsip/, produced by
  build-pjsip-ios.sh. The library is found through LIBRARY_SEARCH_PATHS keyed on
  $(PLATFORM_NAME), which expands to `iphoneos` or `iphonesimulator` — the same two
  directory names that script writes.
* The Kotlin framework is ios/Frameworks/Shared.xcframework, produced by
  build-shared-framework.sh. It is STATIC, so it is linked and not embedded; an embedded
  static framework is a code-signing error, not a warning.
"""

import hashlib
import os
import pathlib
import sys

IOS_ROOT = pathlib.Path(__file__).resolve().parent.parent
APP_NAME = "WhatsAppV2"
BUNDLE_ID = os.environ.get("WAV_BUNDLE_ID", "com.coraltelecom.whatsappv2")
MARKETING_VERSION = "1.0"
PROJECT_VERSION = "1"
DEPLOYMENT_TARGET = "15.0"

# Source directories, in the order they should appear in the navigator.
GROUPS = ["App", "SIP", "Telephony", "Storage", "Features", "Support", "Bridge"]


def oid(seed: str) -> str:
    """A stable 24-hex-digit object identifier for `seed`."""
    return hashlib.md5(seed.encode("utf-8")).hexdigest()[:24].upper()


def q(value: str) -> str:
    """Quotes a value unless it is a bare old-style-plist token.

    The old-style plist grammar a .pbxproj uses accepts an unquoted string only when
    every character is alphanumeric or one of `_$/.-`. A `+` is not in that set, and
    `SipEngine+Bridge.swift` written bare makes the whole file unparseable at that line
    — Xcode reports it as "the project file cannot be opened", which names neither the
    file nor the character. The same trap waits for any path with a space in it.

    So: quote unless provably safe. An over-quoted value is correct; an under-quoted
    one is a project that will not open.
    """
    if value and all(c.isalnum() or c in "_$/." or c == "-" for c in value):
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def plist_string(value: str) -> str:
    """Encodes `value` as a quoted old-style-plist string.

    The reason this is its own function rather than an f-string with a couple of
    `.replace` calls: the preflight shell script below contains double quotes on almost
    every line (`"$SRCROOT/..."`), and an unescaped one closes the plist string early.
    The parser then reports "missing semicolon" at that line, which points at the plist
    grammar rather than at the quote, and the project will not open.

    Order matters: backslashes first, or the escapes introduced for the quotes get
    escaped a second time.
    """
    escaped = (value.replace("\\", "\\\\")
                    .replace('"', '\\"')
                    .replace("\n", "\\n")
                    .replace("\t", "\\t"))
    return f'"{escaped}"'


def collect_sources():
    """Every compilable source, grouped by its top-level directory."""
    app_dir = IOS_ROOT / APP_NAME
    grouped = {}
    for group in GROUPS:
        directory = app_dir / group
        if not directory.is_dir():
            continue
        files = sorted(
            p for p in directory.rglob("*")
            if p.suffix in {".swift", ".m", ".mm", ".c", ".cpp"} and p.is_file()
        )
        if files:
            grouped[group] = files
    return grouped


def main():
    grouped = collect_sources()
    if not grouped:
        print("error: no sources found under", IOS_ROOT / APP_NAME, file=sys.stderr)
        return 1

    all_sources = [p for files in grouped.values() for p in files]

    # Resources that are copied rather than compiled.
    resources = []
    assets = IOS_ROOT / APP_NAME / "Resources" / "Assets.xcassets"
    if assets.is_dir():
        resources.append(assets)

    objects = []

    def emit(identifier, body):
        objects.append(f"\t\t{identifier} = {body};")

    # ---------------------------------------------------------------- file references
    file_refs = {}
    for path in all_sources + resources:
        rel = path.relative_to(IOS_ROOT)
        ref = oid(f"ref:{rel}")
        file_refs[path] = ref
        if path.suffix == ".swift":
            kind = "sourcecode.swift"
        elif path.suffix == ".mm":
            kind = "sourcecode.cpp.objcpp"
        elif path.suffix == ".m":
            kind = "sourcecode.c.objc"
        elif path.suffix == ".cpp":
            kind = "sourcecode.cpp.cpp"
        elif path.suffix == ".xcassets":
            kind = "folder.assetcatalog"
        else:
            kind = "sourcecode.c.c"
        # `path` is relative to the PROJECT, with sourceTree = SOURCE_ROOT, and `name`
        # is the basename so the navigator still reads nicely.
        #
        # The first version used `path = <basename>` with `sourceTree = "<group>"`,
        # which resolves against the enclosing group's own path. That is correct only
        # when the group is flat, and Features/ is not — it has Dialer/, Call/,
        # History/, Accounts/ and Settings/ beneath it. Xcode therefore looked for
        # Features/AccountsView.swift and reported
        #     error: Build input files cannot be found:
        #       '.../ios/WhatsAppV2/Features/AccountsView.swift', ...
        # (CI run 38886188145, the first run in which xcodebuild got far enough to
        # compile anything.) A project-relative path has no such dependence on where
        # the reference is filed in the navigator.
        emit(ref, "{isa = PBXFileReference; lastKnownFileType = %s; name = %s; path = %s; "
                  "sourceTree = SOURCE_ROOT; }" % (kind, q(path.name), q(str(rel))))

    # Headers, Info.plist and entitlements appear in the navigator but are not built.
    extra_refs = {}
    for name, kind in [
        ("Info.plist", "text.plist.xml"),
        (f"{APP_NAME}.entitlements", "text.plist.entitlements"),
    ]:
        path = IOS_ROOT / APP_NAME / name
        if path.exists():
            ref = oid(f"ref:extra:{name}")
            extra_refs[name] = ref
            emit(ref, "{isa = PBXFileReference; lastKnownFileType = %s; name = %s; "
                      "path = %s; sourceTree = SOURCE_ROOT; }"
                 % (kind, q(name), q(str(path.relative_to(IOS_ROOT)))))

    header_refs = {}
    for header in sorted((IOS_ROOT / APP_NAME / "Bridge").glob("*.h")):
        ref = oid(f"ref:{header.relative_to(IOS_ROOT)}")
        header_refs[header] = ref
        emit(ref, "{isa = PBXFileReference; lastKnownFileType = sourcecode.c.h; name = %s; "
                  "path = %s; sourceTree = SOURCE_ROOT; }"
             % (q(header.name), q(str(header.relative_to(IOS_ROOT)))))

    product_ref = oid("ref:product")
    emit(product_ref,
         "{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; "
         f"path = {q(APP_NAME + chr(46) + chr(97) + chr(112) + chr(112))}; sourceTree = BUILT_PRODUCTS_DIR; }}")

    shared_ref = oid("ref:shared.xcframework")
    emit(shared_ref,
         "{isa = PBXFileReference; lastKnownFileType = wrapper.xcframework; "
         "name = Shared.xcframework; path = Frameworks/Shared.xcframework; "
         "sourceTree = \"<group>\"; }")

    # System frameworks. Each one is here because a specific feature needs it; a project
    # that links everything "just in case" grows a launch cost for frameworks it never
    # calls into.
    system_frameworks = [
        ("CallKit.framework", "the system call UI and audio-session handshake"),
        ("PushKit.framework", "VoIP wakes"),
        ("AVFoundation.framework", "AVAudioSession, capture"),
        ("AudioToolbox.framework", "PJSIP's CoreAudio backend"),
        ("CoreAudio.framework", "PJSIP's CoreAudio backend"),
        ("CoreMedia.framework", "video frame buffers"),
        ("CoreVideo.framework", "video frame buffers"),
        ("VideoToolbox.framework", "the H.264 codec aconfigure detects"),
        ("Metal.framework", "pjmedia's metal_dev renderer"),
        ("MetalKit.framework", "pjmedia's metal_dev renderer"),
        ("OpenGLES.framework", "pjmedia's ios_opengl_dev renderer"),
        ("QuartzCore.framework", "layer-backed video views"),
        ("Security.framework", "the Keychain, and OpenSSL's trust store"),
        ("Network.framework", "reachability"),
        ("UIKit.framework", "the app"),
        ("Foundation.framework", "the app"),
    ]
    framework_refs = {}
    for name, _why in system_frameworks:
        ref = oid(f"ref:system:{name}")
        framework_refs[name] = ref
        emit(ref, "{isa = PBXFileReference; lastKnownFileType = wrapper.framework; "
                  f"name = {name}; path = System/Library/Frameworks/{name}; "
                  "sourceTree = SDKROOT; }")

    # ---------------------------------------------------------------- build files
    build_files = {}
    for path in all_sources:
        bf = oid(f"build:{path.relative_to(IOS_ROOT)}")
        build_files[path] = bf
        emit(bf, "{isa = PBXBuildFile; fileRef = %s; }" % file_refs[path])

    resource_build_files = {}
    for path in resources:
        bf = oid(f"buildres:{path.relative_to(IOS_ROOT)}")
        resource_build_files[path] = bf
        emit(bf, "{isa = PBXBuildFile; fileRef = %s; }" % file_refs[path])

    link_build_files = {}
    shared_bf = oid("build:shared.xcframework")
    link_build_files["Shared"] = shared_bf
    emit(shared_bf, "{isa = PBXBuildFile; fileRef = %s; }" % shared_ref)
    for name, _ in system_frameworks:
        bf = oid(f"build:system:{name}")
        link_build_files[name] = bf
        emit(bf, "{isa = PBXBuildFile; fileRef = %s; }" % framework_refs[name])

    # ---------------------------------------------------------------- groups
    group_ids = {}
    for group, files in grouped.items():
        gid = oid(f"group:{group}")
        group_ids[group] = gid
        children = [file_refs[p] for p in files]
        if group == "Bridge":
            children += [header_refs[h] for h in sorted(header_refs)]
        # Name only, no path: the children carry project-relative paths, so a group
        # path would contribute nothing and would suggest a resolution rule that is
        # not in force.
        emit(gid, "{isa = PBXGroup; children = (\n%s\n\t\t\t); name = %s; "
                  "sourceTree = \"<group>\"; }" % (
                      "\n".join(f"\t\t\t\t{c}," for c in children), q(group)))

    resources_gid = oid("group:Resources")
    emit(resources_gid, "{isa = PBXGroup; children = (\n%s\n\t\t\t); name = Resources; "
                        "sourceTree = \"<group>\"; }" % (
                            "\n".join(f"\t\t\t\t{file_refs[p]}," for p in resources)))

    app_gid = oid("group:app")
    app_children = [group_ids[g] for g in GROUPS if g in group_ids]
    app_children.append(resources_gid)
    app_children += [extra_refs[k] for k in sorted(extra_refs)]
    emit(app_gid, "{isa = PBXGroup; children = (\n%s\n\t\t\t); name = %s; "
                  "sourceTree = \"<group>\"; }" % (
                      "\n".join(f"\t\t\t\t{c}," for c in app_children), q(APP_NAME)))

    frameworks_gid = oid("group:frameworks")
    emit(frameworks_gid, "{isa = PBXGroup; children = (\n%s\n\t\t\t); name = Frameworks; "
                         "sourceTree = \"<group>\"; }" % (
        "\n".join([f"\t\t\t\t{shared_ref},"] +
                  [f"\t\t\t\t{framework_refs[n]}," for n, _ in system_frameworks])))

    products_gid = oid("group:products")
    emit(products_gid, "{isa = PBXGroup; children = (\n\t\t\t\t%s,\n\t\t\t); name = Products; "
                       "sourceTree = \"<group>\"; }" % product_ref)

    root_gid = oid("group:root")
    emit(root_gid, "{isa = PBXGroup; children = (\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t); "
                   "sourceTree = \"<group>\"; }" % (app_gid, frameworks_gid, products_gid))

    # ---------------------------------------------------------------- phases
    sources_phase = oid("phase:sources")
    emit(sources_phase, "{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; "
                        "files = (\n%s\n\t\t\t); runOnlyForDeploymentPostprocessing = 0; }" % (
        "\n".join(f"\t\t\t\t{build_files[p]}," for p in all_sources)))

    frameworks_phase = oid("phase:frameworks")
    emit(frameworks_phase, "{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; "
                           "files = (\n%s\n\t\t\t); runOnlyForDeploymentPostprocessing = 0; }" % (
        "\n".join([f"\t\t\t\t{shared_bf},"] +
                  [f"\t\t\t\t{link_build_files[n]}," for n, _ in system_frameworks])))

    resources_phase = oid("phase:resources")
    emit(resources_phase, "{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; "
                          "files = (\n%s\n\t\t\t); runOnlyForDeploymentPostprocessing = 0; }" % (
        "\n".join(f"\t\t\t\t{resource_build_files[p]}," for p in resources)))

    # A build phase that fails the build when the native prerequisites are absent.
    #
    # Without it the first symptom is several hundred "Undefined symbol: _pjsua_create"
    # lines, which names neither the missing library nor the script that produces it.
    preflight_phase = oid("phase:preflight")
    preflight_script = """set -e
LIB="$SRCROOT/Vendor/pjsip/lib/$PLATFORM_NAME/libpjproject.a"
if [ ! -f "$LIB" ]; then
  echo "error: $LIB is missing. Run ios/scripts/build-pjsip-ios.sh" >&2
  exit 1
fi
if [ ! -d "$SRCROOT/Frameworks/Shared.xcframework" ]; then
  echo "error: Frameworks/Shared.xcframework is missing. Run ios/scripts/build-shared-framework.sh" >&2
  exit 1
fi
echo "note: linking libpjproject.a for $PLATFORM_NAME"
"""
    emit(preflight_phase,
         "{isa = PBXShellScriptBuildPhase; buildActionMask = 2147483647; files = (); "
         "inputPaths = (); name = \"Check the native prerequisites\"; outputPaths = (); "
         "runOnlyForDeploymentPostprocessing = 0; shellPath = /bin/sh; "
         f"shellScript = {plist_string(preflight_script)}; }}")

    # ---------------------------------------------------------------- build settings
    header_search = (
        '"$(SRCROOT)/Vendor/pjsip/include"',
        '"$(SRCROOT)/WhatsAppV2/Bridge"',
    )
    common = {
        "ALWAYS_SEARCH_USER_PATHS": "NO",
        "CLANG_ENABLE_MODULES": "YES",
        "CLANG_ENABLE_OBJC_ARC": "YES",
        "CLANG_CXX_LANGUAGE_STANDARD": '"gnu++17"',
        "CLANG_CXX_LIBRARY": '"libc++"',
        "GCC_C_LANGUAGE_STANDARD": "gnu11",
        "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
        "SDKROOT": "iphoneos",
        "TARGETED_DEVICE_FAMILY": '"1,2"',
        "SWIFT_VERSION": "5.0",
        # Stated, not inherited. SipEngine is @MainActor and conforms to
        # PjsipBridgeDelegate, an Objective-C protocol with no isolation; under
        # `complete` that is an error, under `minimal` a warning the code earns by
        # having PjsipBridge dispatch every callback to the main queue. Leaving it to
        # the toolchain's default means a future Xcode can turn a warning into a
        # build failure without anything in this project changing.
        "SWIFT_STRICT_CONCURRENCY": "minimal",
        "ENABLE_BITCODE": "NO",   # removed in Xcode 16; an archive built with it will not link
        "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES",
        "GCC_WARN_UNDECLARED_SELECTOR": "YES",
    }
    debug_only = {
        "DEBUG_INFORMATION_FORMAT": "dwarf",
        "ENABLE_TESTABILITY": "YES",
        "GCC_OPTIMIZATION_LEVEL": "0",
        "GCC_PREPROCESSOR_DEFINITIONS": '("DEBUG=1","$(inherited)")',
        "ONLY_ACTIVE_ARCH": "YES",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
        "SWIFT_OPTIMIZATION_LEVEL": '"-Onone"',
    }
    release_only = {
        "DEBUG_INFORMATION_FORMAT": '"dwarf-with-dsym"',
        "ENABLE_NS_ASSERTIONS": "NO",
        "SWIFT_COMPILATION_MODE": "wholemodule",
        "SWIFT_OPTIMIZATION_LEVEL": '"-O"',
        "VALIDATE_PRODUCT": "YES",
    }
    target_common = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
        "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
        "CODE_SIGN_ENTITLEMENTS": q(f"{APP_NAME}/{APP_NAME}.entitlements"),
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": PROJECT_VERSION,
        "MARKETING_VERSION": MARKETING_VERSION,
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": q(f"{APP_NAME}/Info.plist"),
        "PRODUCT_BUNDLE_IDENTIFIER": q(BUNDLE_ID),
        "PRODUCT_NAME": '"$(TARGET_NAME)"',
        "SWIFT_OBJC_BRIDGING_HEADER": q(f"{APP_NAME}/Bridge/{APP_NAME}-Bridging-Header.h"),
        "HEADER_SEARCH_PATHS": "(%s)" % ",".join(header_search),
        # The three defines pjproject compiles ITSELF with. Without them
        # `pj/config.h` refuses to compile at all:
        #
        #     config.h:265:9: error: Endianness must be declared for this processor
        #     config.h:1649:4: error: "PJ_IS_LITTLE_ENDIAN is not defined!"
        #
        # pjproject's generated build.mak puts them on every compile line
        # (`-DPJ_AUTOCONF=1 -DPJ_IS_BIG_ENDIAN=0 -DPJ_IS_LITTLE_ENDIAN=1`), and the app
        # includes the same headers, so it needs the same flags. PJ_AUTOCONF is what
        # makes config.h read the `pj/compat/*_auto.h` that aconfigure generated; the
        # endianness pair is not derivable from the preprocessor on Apple silicon,
        # which is why pjproject makes the build state it.
        #
        # Little-endian is correct for every architecture this project targets — arm64
        # and x86_64 on iOS — and pjproject's configure decided the same thing; the
        # value is read off its compile line rather than assumed.
        "GCC_PREPROCESSOR_DEFINITIONS": '("$(inherited)","PJ_AUTOCONF=1",'
                                        '"PJ_IS_BIG_ENDIAN=0","PJ_IS_LITTLE_ENDIAN=1")',
        # Keyed on $(PLATFORM_NAME) so the device and simulator slices are never mixed.
        # They cannot be lipo-ed together — same architecture, different platform — so
        # two directories and this variable is the only arrangement that works.
        "LIBRARY_SEARCH_PATHS": '("$(SRCROOT)/Vendor/pjsip/lib/$(PLATFORM_NAME)")',
        "FRAMEWORK_SEARCH_PATHS": '("$(SRCROOT)/Frameworks")',
        "OTHER_LDFLAGS": '("-lpjproject","-lc++","-lresolv","-ObjC")',
        "ENABLE_USER_SCRIPT_SANDBOXING": "NO",
        "LD_RUNPATH_SEARCH_PATHS": '("$(inherited)","@executable_path/Frameworks")',
    }

    def settings_block(mapping):
        return "{\n" + "".join(
            f"\t\t\t\t{k} = {v};\n" for k, v in sorted(mapping.items())
        ) + "\t\t\t}"

    cfgs = {}
    for name, extra, target_extra in [
        ("Debug", debug_only, {}),
        ("Release", release_only, {}),
    ]:
        project_cfg = oid(f"cfg:project:{name}")
        cfgs[f"project:{name}"] = project_cfg
        merged = dict(common); merged.update(extra)
        emit(project_cfg, "{isa = XCBuildConfiguration; buildSettings = %s; name = %s; }"
             % (settings_block(merged), name))

        target_cfg = oid(f"cfg:target:{name}")
        cfgs[f"target:{name}"] = target_cfg
        merged_target = dict(target_common); merged_target.update(target_extra)
        emit(target_cfg, "{isa = XCBuildConfiguration; buildSettings = %s; name = %s; }"
             % (settings_block(merged_target), name))

    project_cfg_list = oid("cfglist:project")
    emit(project_cfg_list,
         "{isa = XCConfigurationList; buildConfigurations = (\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t); "
         "defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }"
         % (cfgs["project:Debug"], cfgs["project:Release"]))

    target_cfg_list = oid("cfglist:target")
    emit(target_cfg_list,
         "{isa = XCConfigurationList; buildConfigurations = (\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t); "
         "defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }"
         % (cfgs["target:Debug"], cfgs["target:Release"]))

    # ---------------------------------------------------------------- target + project
    target_id = oid("target:app")
    emit(target_id,
         "{isa = PBXNativeTarget; buildConfigurationList = %s; buildPhases = (\n"
         "\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t\t%s,\n\t\t\t); "
         "buildRules = (); dependencies = (); name = %s; productName = %s; "
         "productReference = %s; productType = \"com.apple.product-type.application\"; }"
         % (target_cfg_list, preflight_phase, sources_phase, frameworks_phase,
            resources_phase, q(APP_NAME), q(APP_NAME), product_ref))

    project_id = oid("project:root")
    emit(project_id,
         "{isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = 1; "
         "LastSwiftUpdateCheck = 1540; LastUpgradeCheck = 1540; TargetAttributes = {%s = "
         "{CreatedOnToolsVersion = 15.4; }; }; }; buildConfigurationList = %s; "
         "compatibilityVersion = \"Xcode 14.0\"; developmentRegion = en; hasScannedForEncodings = 0; "
         "knownRegions = (en,Base,); mainGroup = %s; productRefGroup = %s; "
         "projectDirPath = \"\"; projectRoot = \"\"; targets = (\n\t\t\t\t%s,\n\t\t\t); }"
         % (target_id, project_cfg_list, root_gid, products_gid, target_id))

    body = "\n".join(objects)
    pbxproj = (
        "// !$*UTF8*$!\n"
        "{\n"
        "\tarchiveVersion = 1;\n"
        "\tclasses = {\n\t};\n"
        "\tobjectVersion = 56;\n"
        "\tobjects = {\n"
        f"{body}\n"
        "\t};\n"
        f"\trootObject = {project_id};\n"
        "}\n"
    )

    out_dir = IOS_ROOT / f"{APP_NAME}.xcodeproj"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "project.pbxproj").write_text(pbxproj)

    # A shared scheme, so `xcodebuild -scheme WhatsAppV2` works on a clean checkout.
    # Without one Xcode autocreates a scheme on first open, which is a user-specific file
    # and therefore absent in CI — and `xcodebuild -list` then shows no schemes at all.
    schemes = out_dir / "xcshareddata" / "xcschemes"
    schemes.mkdir(parents=True, exist_ok=True)
    (schemes / f"{APP_NAME}.xcscheme").write_text(SCHEME.format(
        target_id=target_id, app=APP_NAME, blueprint=f"{APP_NAME}.xcodeproj"))

    print(f"wrote {out_dir}/project.pbxproj")
    print(f"  {len(all_sources)} source file(s) in {len(grouped)} group(s)")
    for group, files in grouped.items():
        print(f"    {group:<12} {len(files)}")
    print(f"  bundle id: {BUNDLE_ID}")
    return 0


SCHEME = """<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "1540" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES"
                           buildForProfiling = "YES" buildForArchiving = "YES"
                           buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{target_id}"
               BuildableName = "{app}.app"
               BlueprintName = "{app}"
               ReferencedContainer = "container:{blueprint}">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
               selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables></Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
                 selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0"
                 useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO"
                 debugDocumentVersioning = "YES" debugServiceExtension = "internal"
                 allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target_id}"
            BuildableName = "{app}.app"
            BlueprintName = "{app}"
            ReferencedContainer = "container:{blueprint}">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES"
                  savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{target_id}"
            BuildableName = "{app}.app"
            BlueprintName = "{app}"
            ReferencedContainer = "container:{blueprint}">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug"></AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES"></ArchiveAction>
</Scheme>
"""


if __name__ == "__main__":
    sys.exit(main())
