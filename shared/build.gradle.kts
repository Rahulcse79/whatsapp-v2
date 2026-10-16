import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

/**
 * Business logic that both platforms run, and the only module in this build that is not
 * Android-only.
 *
 * ## What is in here
 *
 * Moved across with their package names preserved, so not one import anywhere else in
 * the build had to change:
 *
 *   `com.whatsappv2.domain.model`         SIP URIs, accounts, codecs, transports
 *   `com.whatsappv2.domain.call`          the call state machine, hold, controls
 *   `com.whatsappv2.domain.engine`        the `SipEngine` port and its value types
 *   `com.whatsappv2.domain.registration`  retry backoff, expiry refresh, recovery
 *   `com.whatsappv2.domain.validation`    `AccountValidator` and its violations
 *   `com.whatsappv2.domain.video`         the adaptive quality ladder
 *   `com.whatsappv2.core.common.*`        `Outcome`, `Secret`, logging, redaction, `Clock`
 *   `com.whatsappv2.shared.ios`           `SharedFacade`, the Swift-shaped surface
 *
 * ## Why a new module rather than making `:domain` multiplatform
 *
 * `:domain` is already pure Kotlin with no Android plugin, so it looks like the obvious
 * module to convert. It cannot be: it publishes Java **test fixtures** — `FakeSipEngine`,
 * `FakeAppSettingsRepository` — and `:app` and every `:feature:*` module consume them
 * through `testFixtures(project(":domain"))`. Kotlin Multiplatform does not support the
 * `java-test-fixtures` plugin, so converting in place would take the test setup of ten
 * modules down with it, and not breaking the Android build is the hard constraint of this
 * port. The same applies to `:core:common`, which publishes `MutableClock`.
 *
 * So code moves *here* and those modules depend on this one. The tests did NOT move:
 * `:domain` still has 740 of them and `:core:common` 51, they still compile against the
 * moved classes through the `api` dependency, and they are therefore the regression
 * proof that the move changed nothing. Only the facade's tests were written here, where
 * they also run on iOS.
 *
 * ## Why only the JVM target is built on a machine without Xcode
 *
 * The iOS targets are declared only when there is an Xcode to compile them with. Without
 * one there is no iPhoneOS SDK, and Kotlin/Native cannot link a target whose SDK is
 * absent — declaring them anyway would make *every* Gradle invocation on such a machine
 * carry a target that cannot work, including the Android build, which is exactly the
 * breakage this port is not allowed to cause. See `docs/ios-port.md`.
 */
kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmToolchain.get()))
    }

    // Declared behind the toolchain check described above. `iosArm64` is the device,
    // `iosX64` the simulator on an Intel Mac, `iosSimulatorArm64` the simulator on Apple
    // silicon — all three, because which simulator is needed is a property of the machine
    // the app is being run from rather than of this build.
    if (providers.of(XcodePresent::class.java) {}.get()) {
        // One XCFramework over all three slices, because that is the shape Xcode wants to
        // link: a single artefact carrying device and simulator rather than a framework
        // per architecture for the app to choose between.
        val xcframework = XCFramework("Shared")
        listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "Shared"
                // Static: one less thing to embed and sign, and nothing else links it.
                isStatic = true
                xcframework.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `SipEngine` and `ConferenceSession` expose
            // `Flow` and `StateFlow` in their public signatures, so every consumer of
            // this module needs coroutines on its own compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

/**
 * Whether this machine has a full Xcode, as opposed to the Command Line Tools.
 *
 * A `ValueSource` rather than a bare `exec` so the configuration cache stays valid: a
 * plain external process at configuration time is exactly what the configuration cache
 * refuses, and `org.gradle.configuration-cache=true` is set for this build.
 */
abstract class XcodePresent : org.gradle.api.provider.ValueSource<Boolean, org.gradle.api.provider.ValueSourceParameters.None> {
    override fun obtain(): Boolean {
        if (System.getProperty("os.name") != "Mac OS X") return false
        return runCatching {
            val process = ProcessBuilder("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }
}
