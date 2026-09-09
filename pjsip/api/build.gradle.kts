/**
 * The pjsua2 Java API — **generated from `third_party/pjproject` on every build** (N-13).
 *
 * ## What changed, and why the old reasoning stopped applying
 *
 * Until ADR-007 this module held **318 committed `.java` files**: 313 SWIG output plus the
 * five hand-written `org.pjsip` camera and audio helpers. That was the right decision for a
 * repository that did not contain pjproject's source — CI could compile `:data:sip` against
 * the real API while the native build was brought up, instead of stopping on 259 unresolved
 * references before detekt, lint or the architecture rules ever ran.
 *
 * The source is now in the tree, so the problem dissolves. SWIG needs no NDK, no OpenSSL and
 * no cross-compile — it parses the pjsua2 headers and emits Java in about **15 seconds**,
 * measured (`docs/reconciliation.md` B-7). Generating the bindings is now as cheap as
 * reading them from git, and the reason to commit them is gone.
 *
 * ## And generating them removes a whole class of crash
 *
 * The `.so` exports `Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java SWIG
 * generated beside it. A committed copy can drift from the binary: between SWIG 4.2.0 and
 * 4.4.1 the typed vector classes rename `add`/`reserve` to `doAdd`/`doReserve`, and 45 files
 * differ. That drift used to be prevented by a comment asking people to be careful.
 * Generating both halves in one build, from one source tree, against one `config_site.h`,
 * makes it **structurally impossible**.
 *
 * ## The two things that make this real work rather than a one-line `swig` call
 *
 * 1. **SWIG parses `config_site.h`**, so the generated Java depends on the declared feature
 *    set — the same header stage 2 compiles against. `pjsip/config/config_site.h` is that
 *    single file, and it is put FIRST on the include path so `#include <pj/config_site.h>`
 *    resolves to it. Upstream ships no `config_site.h`, only `config_site_sample.h`, so
 *    there is nothing to shadow and nothing is written into the vendored tree — which rule
 *    12 hashes and N-7 forbids editing.
 * 2. **Five classes are not SWIG output.** `PjCamera`, `PjCamera2`, `PjCameraInfo`,
 *    `PjCameraInfo2` and `PjAudioDevInfo` are hand-written Java that pjproject ships beside
 *    the C it is called from, in `pjmedia/src/pjmedia-{video,audio}dev/android`. They are
 *    copied from the vendored tree, not regenerated — there is nothing to generate — and
 *    they are why this is an Android library rather than a JVM one: they use
 *    `android.hardware.camera2`. `PjCameraInfo2` is the one the adapter calls, and without
 *    it there are no capture devices at all.
 *
 * ## Upstream's Makefile is deliberately not used
 *
 * `third_party/pjproject/pjsip-apps/src/swig/java/Makefile:1` opens with
 * `include ../../../../build.mak`, a file `configure` writes — so using it would mean
 * running a host `./configure` first. `swig` is invoked directly instead, with the include
 * set that Makefile lists, which is exactly what the green workflow already does
 * (`.github/workflows/build-pjsip.yml:110-117`).
 */
plugins {
    id("whatsappv2.android.library")
}

val generateBindings = tasks.register<GeneratePjsua2Bindings>("generatePjsua2Bindings") {
    val root = rootProject.layout.projectDirectory

    pjprojectDir.set(root.dir("third_party/pjproject"))
    configSiteDir.set(root.dir("pjsip/config"))
    outputDirectory.set(layout.buildDirectory.dir("generated/pjsua2"))

    // The SWIG this build is pinned to. An unpinned generator is a silently different JNI
    // surface (docs/native-dependencies.md §3), so the version is asserted rather than
    // accepted: a runner-image bump becomes a build failure naming the two versions,
    // instead of an UnsatisfiedLinkError on somebody's handset.
    expectedSwigVersion.set(providers.gradleProperty("pjsip.swig.version").orElse("4.2.0"))
}

android {
    namespace = "org.pjsip"

    sourceSets.named("main") {
        // The generated tree, not a checked-in one. `git ls-files pjsip/` returns no
        // .java file, which is DoD 23 stated as a command.
        java.srcDir(generateBindings.flatMap { it.outputDirectory })
    }
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

// detekt reads Kotlin, and there is none here — but the task would still walk the
// generated tree looking for it, on every build, for nothing.
tasks.matching { it.name.startsWith("detekt") }.configureEach {
    enabled = false
}
