/**
 * The native stack, **built from vendored source by this build** (ADR-007, N-1…N-6, N-14).
 *
 * ## What this module used to be
 *
 * A wrapper around a binary. It published `libs/pjsua2.aar` through its `default`
 * configuration — a ~19 MB file that was `.gitignore`d, produced by a CI workflow, and
 * carried to a developer's machine **by hand**. When it was absent, which it always was on
 * a fresh clone, `:data:sip` silently fell back to a Java-only module and produced an APK
 * that compiled, installed, and raised `UnsatisfiedLinkError` on the first SIP call.
 *
 * Both of those are gone. N-1 forbids a binary this repository did not compile; N-5 forbids
 * the manual step; N-14 forbids the fallback.
 *
 * ## What it is now — two stages, one source of truth
 *
 * ```
 * third_party/pjproject ──► stage 1: SWIG          ──► generated org.pjsip.pjsua2 Java
 *    (vendored, patched)     no NDK, measured 15 s      + 5 org.pjsip classes copied
 *             │                                          (:pjsip:api)
 *             └───────────► stage 2: CMake + NDK   ──► libpjsua2.so × 3 ABIs
 *                           measured 2m46s–3m23s/ABI     + libc++_shared.so
 * ```
 *
 * Both stages read the same `pjsip/config/pj/config_site.h` (N-8), which is what makes the
 * generated Java and the `.so` describe the same library **by construction** rather than by
 * a comment asking people to be careful.
 *
 * ## The native stage is a Gradle task, not `externalNativeBuild`

`externalNativeBuild` was the first shape and AGP could not model it: it enumerates a CMake
project's **targets** and this project declares none — the `.so` is produced by upstream's
autotools, which CMake never sees. AGP's answer was
`':pjsip:configureCMakeDebug[arm64-v8a]' > java.lang.NullPointerException`, with no message
and no target named. [com.whatsappv2.buildlogic.BuildPjsua2Native] explains the trade in
full; the short version is that N-4 is still satisfied literally — one `./gradlew`, one
CMake entry point invoked with the NDK toolchain file, no manual step — without handing AGP
a model it cannot represent.

## No cache, and that is a measurement rather than an omission
 *
 * The master prompt's §2.3 designs a content-hash cache on an estimate of "roughly an hour
 * per ABI". The measured figure is **2m46s–3m23s**, and 7m18s end to end
 * (`docs/reconciliation.md` B-7) — so the cache would solve a problem that does not exist,
 * and a cache that is not needed is one that can go stale, thrash, or be populated by hand.
 * All three are how a prebuilt binary comes back. `build-native.sh` keeps a per-tree stamp
 * so an incremental build does not redo OpenSSL, which is the part that actually costs.
 * Full reasoning: `docs/system-design.md` §2.5.
 *
 * ## There is no `if`
 *
 * If stage 2 produces no `.so` for the target ABI, the build **fails**. It does not quietly
 * assemble an APK that dies on the first call (DoD 24).
 */
import com.whatsappv2.buildlogic.BuildPjsua2Native

plugins {
    id("whatsappv2.android.library")
}

android {
    namespace = "com.whatsappv2.pjsip"

    packaging {
        jniLibs {
            // The .so files must be on disk for System.loadLibrary to find them.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Stage 1. `:data:sip` takes `:pjsip` and gets both halves — the generated API and the
    // library — from one dependency, which is what N-14's "exactly one way to consume
    // PJSIP" means in a build file.
    api(project(":pjsip:api"))
}

// No Kotlin and no Java of this module's own: the sources are :pjsip:api's, generated.
tasks.matching { it.name.startsWith("lint") || it.name.startsWith("detekt") }.configureEach {
    enabled = false
}

/**
 * Stage 2 — the cross-compile, per ABI, through `pjsip/CMakeLists.txt`.
 *
 * The ABI list must match `:app`'s `abiFilters`: one built here and not packaged there is
 * wasted build time; one packaged and not built is a crash on that device (N-6).
 */
val buildNative = tasks.register<BuildPjsua2Native>("buildPjsua2Native") {
    nativeSourceDir.set(layout.projectDirectory)
    cmakeLists.set(layout.projectDirectory.file("CMakeLists.txt"))
    buildScript.set(layout.projectDirectory.file("build-native.sh"))
    configDir.set(layout.projectDirectory.dir("config"))
    vendoredDir.set(rootProject.layout.projectDirectory.dir("third_party"))
    jniLibsDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))

    // From the environment, so this task can also run in a job that has an NDK and no
    // Android SDK — which is exactly what the egress-blocked offline test of §2.1.2 is.
    ndkRoot.set(
        providers.environmentVariable("ANDROID_NDK_ROOT")
            .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
            .orElse(providers.gradleProperty("android.ndkPath")),
    )

    // The native stage is expensive and nothing in the JVM half of the build needs it, so
    // it is opt-in per invocation rather than on the path of `./gradlew :domain:test`.
    //
    // This is NOT the fallback N-14 deletes, and the difference is the whole point: skipping
    // it produces NO library and therefore NO APK — `assertNativeLibraries` fails the
    // packaging. What N-14 forbids is a build that quietly assembles an APK anyway.
    onlyIf {
        providers.gradleProperty("pjsip.native").orNull != "false"
    }
}

/**
 * The expected native library set per ABI, asserted at packaging time (N-6).
 *
 * `libpjsua2.so` is the stack and `libc++_shared.so` is the NDK runtime it links against;
 * an APK missing either installs and dies on the first call. `build-native.sh` copies both
 * and this is the second line of defence — the one that catches an ABI split or a packaging
 * change rather than a build failure.
 *
 * Deliberately a hard failure with the ABI named. "The ABI split dropped a library" must be
 * a build failure, never an `UnsatisfiedLinkError` on somebody's phone.
 */
val assertNativeLibraries = tasks.register("assertNativeLibraries") {
    val expected = setOf("libpjsua2.so", "libc++_shared.so")
    val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val jniRoot = layout.buildDirectory.dir("generated/jniLibs")

    inputs.dir(jniRoot).optional(true)
    outputs.upToDateWhen { false }

    doLast {
        val root = jniRoot.get().asFile
        if (!root.isDirectory) {
            error(
                "the native stage produced no output at $root.\n" +
                    "  There is no prebuilt fallback (N-14): a build with no .so fails rather " +
                    "than assembling an APK that raises UnsatisfiedLinkError on the first call.",
            )
        }
        val missing = abis.flatMap { abi ->
            val found = root.walkTopDown()
                .filter { it.isFile && it.name in expected && "/$abi/" in it.path.replace('\\', '/') }
                .map { it.name }
                .toSet()
            (expected - found).map { "$abi/$it" }
        }
        check(missing.isEmpty()) {
            "the native build is short ${missing.size} library/libraries: ${missing.sorted()}\n" +
                "  Every supported ABI must carry its full expected set (N-6)."
        }
        logger.lifecycle("native libraries: ${abis.size} ABIs × ${expected.size} libraries, all present")
    }
}

/**
 * The generated `.so` files are this module's jniLibs.
 *
 * `addGeneratedSourceDirectory` rather than `sourceSets`: it carries the task dependency
 * with it, so packaging cannot run before the libraries exist — and AGP 9 removed the
 * source-set DSL the old form used.
 */
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildNative) { it.jniLibsDir }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    dependsOn(buildNative)
    finalizedBy(assertNativeLibraries)
}
