# Running this project on iOS as well as Android

The goal is one repository that produces a working Android APK and a native iOS app. This
document is the plan and the honest status. **Nothing here claims a thing is done until it
has been built and run**; everything not yet built says so, by name.

Branch: `feature/ios-port`.

---

## 1. Where the project starts from

A single-platform Android codebase. Measured on the branch point, not estimated:

| | |
|---|---|
| Gradle modules | 22, of which **20 apply an Android plugin** |
| Kotlin source files | 895 (422 in `src/main`) |
| Kotlin Multiplatform | **none anywhere in the build** |
| Jetpack Compose | 94 files |
| Hilt | 69 files |
| Room | 13 files |
| Android Telecom (`ConnectionService`) | 7 files |
| DataStore | 7 files |
| Firebase (FCM) | 3 files |
| Android camera | 3 files |

So "build it for iOS" is not a build-configuration change. There is no target to add; the
app layer has to be written.

### What is genuinely portable

Two things, and they matter more than the list above suggests.

**The business logic is already platform-neutral.** `:domain` and `:core:common` are pure
Kotlin/JVM modules — no Android plugin. Of their 130 source files, only **18** touch a JVM
API at all: 17 `javax.inject` annotations and one `java.util.UUID`. The adaptive video
ladder, the call state machines, the reducers and the settings model are ordinary Kotlin
and move to `commonMain` with small, mechanical edits.

**The native stack already supports iOS.** The vendored pjproject ships
`pjmedia-audiodev/coreaudio_dev.m` — PJSIP's CoreAudio backend — and PJSIP supports iOS
officially. Lyra and libvpx build for Apple targets too. What does *not* carry over is the
binding layer: the Java API in use today is SWIG-generated `pjsua2` Java, and iOS calls
`pjsua2`'s C++ API from Swift through Objective-C++ instead.

### Why the shared code goes in a new `:shared` module

The obvious move — convert `:domain` to Kotlin Multiplatform in place — **breaks the
Android build**, and that is a hard constraint here. `:domain` publishes Java *test
fixtures* (`FakeSipEngine`, `FakeAppSettingsRepository`), consumed through
`testFixtures(project(":domain"))` by `:app` and the `:feature:*` modules. Kotlin
Multiplatform does not support the `java-test-fixtures` plugin, so the conversion would
take the test setup of ten modules down with it.

So shared code moves *into* a new `:shared` KMP module and `:domain` depends on it. Package
names are preserved on the way across, which keeps every existing import working, and the
move happens a slice at a time rather than in one commit.

---

## 2. Blockers

### Blocker 1 — no Xcode on this machine (hard)

The iOS half **cannot be built here at all**, and this is not a matter of effort:

```
xcode-select -p            ->  /Library/Developer/CommandLineTools
xcodebuild -version        ->  error: tool 'xcodebuild' requires Xcode
xcrun --sdk iphoneos       ->  error: SDK "iphoneos" cannot be located
xcrun simctl list runtimes ->  (no iOS runtimes)
/Applications/Xcode*.app   ->  none
```

The Command Line Tools carry the macOS SDK only. Without an iPhoneOS SDK there is no
Kotlin/Native iOS link step and no `xcodebuild`, so neither the shared framework nor the
app can be produced. Installing Xcode is a multi-gigabyte download that needs the user's
Apple ID and an admin password; it is not something the build can do for itself.

### Blocker 2 — macOS 12.7.6 caps the usable Xcode (consequential)

This Mac runs macOS 12.7.6 (Monterey, Intel). Xcode 15 needs macOS 13+ and Xcode 16 needs
macOS 14+, so the newest version installable here is **Xcode 14.2**, which carries the
iOS 16.2 SDK.

That is enough to build, run on a simulator and run on a device. It is **not** enough to
submit to the App Store — Apple requires a current SDK, which today means Xcode 16/iOS 18.
So the decision is:

- **Install Xcode 14.2** → local development and on-device testing work; shipping does not.
- **Upgrade macOS** (and ideally to Apple silicon) → everything works, including release.

Development can start under the first option. Release cannot.

### Blocker 3 — PJSIP for iOS is its own build (large, not yet started)

`pjsip/build-native.sh` drives an NDK/CMake cross-compile for three Android ABIs. iOS needs
a separate configure (`configure-iphone`) producing device arm64 and simulator slices, with
Lyra, libvpx and OpenSSL built the same way, assembled into an XCFramework. The vendored
sources support it; the build script does not exist yet.

---

## 3. The plan, in phases

Each phase ends in something that can be run and checked. Phases 2 onward need Xcode.

### Phase 0 — build system *(done, verified)*

`./build.sh` takes a target: `android`, `ios`, or `all`.

- `android` is the previous behaviour exactly, wrapped in a function rather than rewritten.
- `ios` runs a real preflight and names the one missing piece — full Xcode, a simulator
  SDK, or the app project — rather than reporting a success it has not earned.
- `all` runs both **even when the first fails**, in subshells so a failing target becomes a
  status rather than the end of the run, prints a per-platform summary, and exits non-zero
  if either failed.

### Phase 1 — `:shared` Kotlin Multiplatform module *(first slice done, verified)*

A new module with a `jvm()` target, and the three iOS targets (`iosArm64`, `iosX64`,
`iosSimulatorArm64`) declared only on a machine that has an Xcode to compile them.

The whole `com.whatsappv2.domain.video` package has moved across: `VideoFrameRate`,
`VideoQualityProfile`, `VideoBudget`, `VideoConditions`, `DevicePressureSource` and
`AdaptiveVideoPolicy`, with their tests. It was chosen as the first slice because it is a
closed set — arithmetic over enums, no imports at all in any of the six files — so it
proves the pipeline without touching anything that could break Android.

**The package name did not change on the way across.** What was
`com.whatsappv2.domain.video` still is, so not one import anywhere else in the build was
edited. That is the property that makes the remaining slices cheap.

The tests moved to `commonTest` and swapped `org.junit.Test` for `kotlin.test.Test`, so
they are no longer JVM-only: the same 73 assertions will run on the iOS targets the first
time there is an Xcode to run them with.

Verified: 73 tests pass on the JVM target; `:domain`, `:data:sip`, `:feature:calls` and
`:feature:settings` suites all pass; the APK builds and its dex contains the moved
classes, so the module is genuinely linked in rather than silently dropped.

**Still not verifiable without Xcode:** the iOS compilation and link.

### Phase 1.5 — CI as the iOS toolchain *(done, verified)*

`.github/workflows/ios-build.yml`, on pushes and pull requests to this branch and on
demand. A GitHub macOS runner has the Xcode this development machine cannot install, so CI
is not a formality on top of local verification here — it is the only place the iOS half
can be verified at all.

Measured on run 38491167735 (Xcode 26.6, iPhoneSimulator 26.5 SDK, arm64 runner):

| Job | Result |
|---|---|
| Shared KMP module compiled for `iosArm64` and the simulator | **passed** |
| Its tests run **on an iOS simulator** | **73 tests, 0 failures** |
| `Shared.xcframework` assembled | **passed**, 22.9 MB artifact |
| Android APK via `./build.sh android`, full native build | **passed**, 8m 53s, 109.1 MB |
| `iOS app` | **skipped** — no `.xcodeproj` of ours exists |

The iOS app job is skipped rather than passed on purpose: a job that printed "nothing to
build" and went green would read as "iOS builds fine". Its condition is a filesystem check,
so it begins running by itself on the commit that adds a project.

### What CI found that no local run could

Four defects, none of them visible on a machine without Xcode or without a clean checkout:

1. **Eighteen test names contained a comma.** Legal on the JVM, rejected by Kotlin/Native —
   these become Objective-C selectors. Renamed with a dash.
2. **`String.format` is JVM-only.** One use in a test message; replaced with rounding.
3. **`third_party/libvpx/build/` was missing** — 17 files, from both disk and git, so
   `./configure` could not find its own `build/make/configure.sh`. Restored from the pinned
   v1.17.0 tarball.
4. **`third_party/pjproject/build/` was missing** — 60 files including `os-auto.mak.in`,
   which every other pjproject makefile includes. Six of pjproject's seven `build/`
   directories were present; the top-level one was not. Restored from the pinned 2.17.

3 and 4 are the same defect the `.gitignore` comment on line 16 already describes, left
behind after the negation rule was added. **A clean checkout has not been able to build the
native stack**, and nobody saw it because every local build uses `--reuse-native` and skips
the native stage. The CI Android job is the first thing in this repository to compile PJSIP
from vendored source end to end.

### Phase 2 — PJSIP for iOS *(written, NOT yet compiled)*

`ios/scripts/build-pjsip-ios.sh`, a sibling of `pjsip/build-native.sh`. Cross-compiles
from the same vendored sources:

| | |
|---|---|
| OpenSSL 3.5.0 | `ios64-xcrun`, `iossimulator-arm64-xcrun`, `iossimulator-x86_64-xcrun` |
| Opus | autotools, with every tool named — an Apple toolchain has no `${CROSS}gcc` |
| libvpx 1.17.0 | `arm64-darwin-gcc` for the device (which *is* libvpx's iOS target), and `*-iphonesimulator-gcc` for the simulator |
| pjproject 2.17 | `aconfigure --host=arm-apple-darwin_ios`, against all three |

Three things it does that are specific to Apple and were got wrong first:

* **It does not use upstream's `configure-iphone`.** That script hardcodes
  `/Applications/XCode.app`, picks "the latest SDK" by sorting directory names, and has
  no notion of the simulator — its `ARCH` defaults to arm64 and its only min-version
  flag is `-miphoneos-version-min`, so a simulator build made through it produces device
  objects against a simulator sysroot. Everything it would export is set per slice from
  `xcrun` instead.
* **The simulator is a *platform*, not an architecture.** A device arm64 slice and a
  simulator arm64 slice have the same `lipo` architecture and different
  `LC_BUILD_VERSION` load commands, so they cannot be `lipo`-ed into one file. They go
  into `lib/iphoneos/` and `lib/iphonesimulator/`, and the Xcode project's
  `LIBRARY_SEARCH_PATHS` is keyed on `$(PLATFORM_NAME)`.
* **`arm64-iphonesimulator-gcc` does not exist in libvpx 1.17.0.** It is added to the
  *work copy* of `configure`, with NEON disabled for that slice only: the generic
  `*-iphonesimulator-*` case supplies the sysroot for any architecture, but the ARM
  branch of `case ${tgt_os}` knows only `darwin`, `android` and `linux`, so
  `asm_conversion_cmd` is never set and the NEON `.asm` sources would reach clang
  unconverted. `third_party/` is untouched; rule 12 still hashes it.

Twenty-odd archives become **one `libpjproject.a` per platform** via `libtool -static`,
so the Xcode project carries a single `-lpjproject` and no link-order list that goes
stale whenever pjproject gains a module.

**Lyra is NOT part of it.** The nineteen-tree TFLite closure has not been cross-compiled
for Apple, so `ios/config/pj/config_site.h` sets `PJMEDIA_HAS_LYRA_CODEC 0` — stated as
zero rather than left undefined, so the codec report shows it as NotCompiled instead of
omitting the row. An iPhone and an Android handset on the same call negotiate G.711 or
Opus, which works and costs the bandwidth Lyra exists to save.

### Phase 3 — the SIP layer *(written, NOT yet compiled)*

`ios/WhatsAppV2/Bridge/PjsipBridge.{h,mm}`, 1,500 lines of Objective-C++ over pjsua's
**C** API.

Upstream's C++ `pjsua2` was the plan in the first draft of this document and is not what
was built. pjsua2 is a thin C++ wrapper over the same C API; using it would mean
subclassing `Account` and `Call` across an Objective-C++ boundary and carrying
`libpjsua2` for no capability this app needs. The C API is the same stack with a smaller
surface and no C++ exception discipline to get wrong at the ARC boundary.

**The threading rule is the design.** `thread_cnt = 0`, one pthread of our own pumping
`pjsua_handle_events`, and every call into pjsua posted to that thread — `pj_sync` and
`pj_async` are the only two ways in. This is the discipline `PjsipEventPump` enforces on
Android, adopted for the same reason: PJLIB's thread-local descriptor outlives the
thread that owns it, and a pool that recycles the OS thread underneath it — GCD's, or
the JVM's — is the SIGSEGV inside `DetachCurrentThread` that closed on 2026-09-13.

Implemented: registration with granted-expiry reporting, outgoing and incoming calls,
answer/reject/hangup, hold with both-parties semantics, mute via the conference
transmit level, RFC 2833 DTMF with a SIP INFO fallback, blind and attended transfer,
the local N-way mix, video add/remove/camera-switch, codec enumeration and ordered
priorities, media statistics, and the PushKit token as Contact parameters.

### Phase 4 — platform integrations *(written, NOT yet run)*

* **CallKit** — `CallKitController`. Every call goes through it, including ones started
  in the app's own UI, because it is the only thing that can hold a call when a cellular
  call arrives and the only thing that activates the audio session. The direction of
  control is therefore inverted: tapping Answer asks CallKit, CallKit grants, then PJSIP
  is told. This is the same requirement Android's `ConnectionService` meets.
* **PushKit** — `PushKitController`. Reports a call to CallKit **from the push payload,
  before touching the stack**, because iOS terminates an app that takes a VoIP wake and
  does not — and after a few such kills stops delivering VoIP pushes at all. The
  placeholder becomes the real call when the INVITE arrives, and times out after ten
  seconds if none does.
* **AVAudioSession** — `AudioSessionController`. Configures, never activates: activation
  is `provider(_:didActivate:)`'s. `config_site.h` sets `PJSUA_DONT_RELEASE_SND_DEV` and
  the bridge calls `pjsua_set_no_snd_dev()` at startup, so the stack holds a null device
  until CallKit hands the session over.

### Phase 5 — the SwiftUI app *(written, NOT yet run)*

Dialler with live URI resolution from the shared module, in-call screen with the full
control set and a local-conference strip, history with redial from the stored URI,
account list and editor validated by the shared `AccountValidator`, settings, a SIP
trace viewer and a codec report. 21 source files.

### Phase 6 — parity and verification *(not started)*

Nothing has been run on a device or a simulator. See §4.

---

## 4. Status, 16 October 2026

### Verified on this machine

Everything in this block was actually executed and its output read.

| | |
|---|---|
| 62 files moved into `:shared`, packages preserved | `BUILD SUCCESSFUL` |
| `:shared:jvmTest` | **93 tests, 0 failures** (73 video ladder + 20 new facade) |
| `:core:common:test` | **51 tests, 0 failures** |
| `:domain:test` | **740 tests, 0 failures** |
| `:app:compileDebugKotlin` and every `:data:*` / `:feature:*` module | `BUILD SUCCESSFUL` — the Android app still compiles against the moved code |
| 21 Swift files, `swiftc -parse` | 0 failures |
| 5 shell scripts, `bash -n` | 0 failures |
| `Info.plist`, entitlements, `project.pbxproj`, `plutil -lint` | OK |
| `project.pbxproj` structure | 108 objects, **0 dangling references**, 21 sources, 17 frameworks |
| The generator is deterministic | regenerating twice is byte-identical |
| `verify-ipa.sh` against a synthetic IPA | correctly rejected a macOS binary and two missing usage descriptions |
| `./build.sh ios` on this machine | refuses, naming Xcode; exit 1 |
| `./build.sh android --ipa` | refuses, naming the target |

### NOT verified, and cannot be here

Everything below needs an iOS SDK. None of it has been compiled, linked or run.

| | What would prove it |
|---|---|
| PJSIP/OpenSSL/Opus/libvpx cross-compile | `ios/scripts/build-pjsip-ios.sh` on a Mac with Xcode |
| `PjsipBridge.mm` compiles and links | the same, then an Xcode build |
| The Swift code **type-checks** | `swiftc -parse` is syntax only — no imports are resolved |
| Kotlin/Native's exported Swift names match what `SipEngine.swift` calls | the first Xcode build; `SharedFacade` is the only interop surface, so a mismatch is one file to fix |
| The app launches, registers, places a call | a simulator or a device |
| CallKit, PushKit, audio routing, video | a **device** — the simulator has no PushKit and no cellular call to be interrupted by |
| An installable `.ipa` | `./build.sh ios --ipa-signed` with an Apple team |

### The honest summary

The iOS half is **written and statically checked, not built**. No `.ipa` exists, and
this document will not claim one does until one has been produced and
`verify-ipa.sh` has reported it as a device build.

The one machine in this project that could produce it has no Xcode and cannot install a
current one (§2). The two ways forward are a Mac running macOS 14+ with Xcode 15+, or
the `ios-app` CI job, which now builds the project and packs an unsigned device IPA on a
GitHub macOS runner — and which, before this work, was skipped because no project
existed for it to build.

---

## 5. Known gaps against the Android app

Deliberate, and each one stated rather than left to be discovered:

| Feature | iOS | Why |
|---|---|---|
| Chat | **absent** | `:chatsdk` is a vendored **Java** SDK. Porting it is a separate piece of work of comparable size to this one. |
| Lyra codec | **absent** | The nineteen-tree TFLite closure is not cross-compiled for Apple. Calls fall back to G.711 or Opus. |
| Voice gate / speaker verification | **absent** | ONNX Runtime and a 24 MB model, and the feature ships **off** on Android anyway. |
| Call recording | **absent** | Server-side Lyra recording is a FreeSWITCH module and is unaffected; the client-side half is not ported. |
| Adaptive video ladder | **present but not wired** | `AdaptiveVideoPolicy` compiles for iOS and its tests run there. Feeding it live `VideoConditions` is not done, so iOS encodes at the fixed rate the user chose — which is what Android did before the ladder landed. |
| Contacts | **absent** | `NSContactsUsageDescription` is declared; nothing reads the store yet. |
