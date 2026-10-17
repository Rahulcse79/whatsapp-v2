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

### Phase 2 — PJSIP, Lyra and libvpx for iOS

A sibling to `pjsip/build-native.sh` producing device and simulator slices and an
XCFramework, with the same "the libraries are checked, not assumed" rule the Android build
already enforces.

### Phase 3 — the SIP layer in Swift

`pjsua2`'s C++ API through an Objective-C++ bridge, with the executor discipline the
Android gateway already documents: one thread owns PJSIP, everything else posts to it.

### Phase 4 — platform integrations

CallKit (for Android's Telecom `ConnectionService`), PushKit (for FCM), AVFoundation
capture and rendering, AVAudioSession routing, and the permission flows.

**The hard constraint to design around, not bolt on:** iOS kills an app that takes a
PushKit VoIP wake and does not report a call to CallKit almost immediately. That is
stricter than the FCM push-wake path this app uses today and it shapes the call lifecycle.

### Phase 5 — the SwiftUI app

Dialler, in-call screen, history, settings, chat — against the shared module.

### Phase 6 — parity and verification

SIP registration, audio and video calling, messaging and conferencing, each verified on a
device and written down with the evidence, the way the Android features are.

---

## 4. Status

| Area | State |
|---|---|
| Android APK | **works** — 109.1 MB, built after the KMP move |
| `./build.sh android` | **works**, verified |
| `./build.sh ios` | correctly refuses: no Xcode on this machine, exits 1 |
| `./build.sh all` | **works**: builds Android, reports iOS failed, exits 1 |
| `:shared` KMP module, JVM target | **works** — 73 tests pass |
| `:shared` linked into the APK | **verified** — the moved classes are in the dex |
| Android test suites after the move | **pass** — `:domain`, `:data:sip`, `:feature:calls`, `:feature:settings` |
| `:shared` iOS targets | **compiled and tested on CI** — 73 tests pass on a simulator |
| Android APK on CI, full native build | **works** — 8m 53s, uploaded as an artifact |
| PJSIP for iOS | not started |
| Swift SIP layer | not started |
| CallKit / PushKit | not started |
| SwiftUI app | not started |
| Any iOS feature verified on a device | **none** — nothing has been built for iOS yet |

### Known-failing, and not caused by this branch

`:test:arch` rule 5 ("no LiveData, RxJava, AsyncTask or raw Thread") fails on four files:
`VoiceGateController` in the main tree, whose raw thread arrived with the voice gate in
`83bdb9b8`, and three inside `.claude/worktrees/`, another session's git worktree that the
rule walks because it scans the filesystem rather than git. CI has been red on `main`
since 22 September for unrelated reasons — a committed lab `.pem` and ~150 detekt issues
— so this branch inherits a red baseline rather than creating one.
