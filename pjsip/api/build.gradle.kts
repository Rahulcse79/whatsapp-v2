/**
 * The pjsua2 Java API, as source, so the tree compiles without the native binary.
 *
 * ## Why this exists
 *
 * `:pjsip` publishes `libs/pjsua2.aar`, and that file is ~30 MB of native code which is
 * `.gitignore`d and built by `.github/workflows/build-pjsip.yml`. Until that workflow
 * produces one, `org.pjsip.pjsua2.Endpoint` does not resolve, `:data:sip` does not
 * compile, and CI cannot check a single line of the adapter written against it — which
 * is what happened between ADR-006 landing and this module: 259 unresolved references in
 * one file, and no gate on any of the rest of the tree.
 *
 * The Java half of that AAR needs none of the native build. SWIG generates it from the
 * pjsua2 headers alone — no NDK, no OpenSSL, no Opus, no cross-compile — so it can live
 * in git, be reviewed in a diff, and be compiled against today. That is all this module
 * is: the API, pinned to a pjproject tag, with the `.so` still coming from the AAR.
 *
 * ## What is in here, and where it came from
 *
 * Generated from **pjproject 2.17** by SWIG 4.4.1, by the `bindings` job in
 * `.github/workflows/build-pjsip.yml`. Regenerate by running that workflow and taking
 * the `pjsua2-java-api-<version>` artifact; do not hand-edit anything under `src`.
 *
 *  - `org.pjsip.pjsua2` — 313 files, SWIG's output. Pure JVM Java: no import statements
 *    at all, so nothing here depends on the Android SDK.
 *  - `org.pjsip` — 5 files, NOT generated. Hand-written Java that pjproject ships beside
 *    the C it is called from, copied out of `pjmedia/src/pjmedia-{video,audio}dev/android`.
 *    These are why the module is an Android library rather than a JVM one: they use
 *    `android.hardware.camera2`. `PjCameraInfo2` is the one the adapter calls, and
 *    without it there are no capture devices at all.
 *
 * ## This is not a second source of truth
 *
 * `:data:sip` takes this module ONLY when the AAR is absent — the two carry the same
 * classes, and having both on one classpath is a duplicate-class failure at dex time.
 * The condition is in `data/sip/build.gradle.kts`, in one place, and the AAR always wins.
 *
 * ## What this does NOT do
 *
 * It does not make the app runnable. There is no `libpjsua2.so` here, so an APK built
 * this way installs and then dies on the first SIP call with an `UnsatisfiedLinkError`.
 * That is not gated by a build failure, because `./gradlew build` assembles the release
 * variant and gating it would break the CI run this module exists to unblock; `:pjsip`
 * warns at configuration time instead, and P-1 in `docs/pjsip-migration.md` is still the
 * thing that has to go green before anything can be shipped or tested on a handset.
 */
plugins {
    id("whatsappv2.android.library")
}

android {
    namespace = "org.pjsip"
}

// Generated and vendored third-party source: lint findings here belong to pjproject, are
// not actionable in this repository, and cannot be parked in a baseline because CI fails
// the build if one exists. Linting our own code is the point; linting SWIG's output is
// noise that would either break the gate or teach everyone to ignore it.
//
// Disabled by task rather than through `android { lint { } }` because AGP 9 moved these
// blocks off the generic extension, and a build script that does not compile is a worse
// failure than the one this module exists to fix.
tasks.matching { it.name.startsWith("lint") }.configureEach {
    enabled = false
}
