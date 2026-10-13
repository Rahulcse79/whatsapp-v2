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

### Phase 1 — `:shared` Kotlin Multiplatform module *(in progress)*

A new module with `jvm()`, `androidTarget()` and the three iOS targets (`iosArm64`,
`iosX64`, `iosSimulatorArm64`), exporting an XCFramework.

The first slice is the video quality ladder — `VideoFrameRate`, `VideoQualityProfile`,
`VideoBudget`, `AdaptiveVideoPolicy` and their tests. It is chosen because it is pure
arithmetic over enums with no dependencies at all, so it proves the pipeline without
touching anything that could break the Android build.

**Verifiable without Xcode:** the common and JVM/Android compilations, every existing
Android test, and the APK. **Not verifiable without Xcode:** the iOS link step.

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
| Android APK | **works**, unchanged — verified on this branch |
| `./build.sh android` | **works**, verified |
| `./build.sh ios` | correctly refuses: no Xcode on this machine |
| `./build.sh all` | **works**: builds Android, reports iOS as failed, exits non-zero |
| `:shared` KMP module | in progress |
| PJSIP for iOS | not started |
| Swift SIP layer | not started |
| CallKit / PushKit | not started |
| SwiftUI app | not started |
| Any iOS feature verified on a device | **none** — nothing has been built for iOS yet |
