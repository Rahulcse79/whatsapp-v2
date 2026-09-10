plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.data.sip"

    defaultConfig {
        // R8 runs in :app, not here, so the SDK keep rules have to travel with this
        // module. Without this the release build strips classes the JNI bridge resolves
        // by name, and the failure is a NoSuchMethodError on first use.
        consumerProguardFiles("consumer-rules.pro")

        // The ABI filter for the SIP stack's native libraries lives in :app, not here:
        // abiFilters in a library module governs only that module's own native build and
        // does nothing about which .so a consuming APK packages.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Task 32 / ADR-005: where the integration tests point, supplied at build time
        // and never committed. A host is a LAN address that changes with the network and
        // a password is a credential; either one in git is a leak that outlives the
        // commit that removed it.
        //
        // Read through `providers` rather than `findProperty` so the configuration cache
        // records them as inputs and a changed value actually re-configures the build.
        //
        // Absent means absent: the arguments come through empty and the instrumented
        // tests skip themselves with a message naming what to set, rather than failing as
        // though the code were broken. See docs/testing.md.
        testInstrumentationRunnerArguments.putAll(
            sipTestTargetArguments(),
        )
    }

    packaging {
        jniLibs {
            // PJSIP's .so files must be on disk for System.loadLibrary to find them.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)

    // ADR-006. PJSIP publishes no Android artifact, so the binaries are built from
    // source in CI and wrapped by :pjsip rather than resolved from a repository.
    implementation(project(":pjsip"))

    // :pjsip carries BOTH halves and there is exactly one way to consume it (N-14):
    // the generated org.pjsip.pjsua2 API from third_party/pjproject, and the libpjsua2.so
    // built from the same tree by the same build.
    //
    // The `if (!rootProject.file("pjsip/libs/pjsua2.aar").exists())` fallback that used to
    // live here is DELETED, condition and all. It compiled and could not run: on a fresh
    // clone the AAR was always absent, so every build anyone ever ran from a clean checkout
    // took the fallback, assembled an APK, installed it, and raised UnsatisfiedLinkError on
    // the first SIP call. A build whose native stage produced no .so must FAIL — it must not
    // quietly produce an APK that dies on the first call (DoD 24).

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Task 33. Only artifacts the catalog already pins: the integration suite cannot run
    // in `ci.yml` at all - it needs a device and a reachable registrar - so a new,
    // unverified version pin here would risk the push gate for code the push gate never
    // executes. If the instrumentation runner turns out to be missing at run time,
    // docs/testing.md says to add androidx.test:runner.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
    // Task 46 drives the engine, which needs a platform registry and an app-settings
    // store. The fakes stand in for Telecom and DataStore; what is under test is the
    // SIP, not the platform seams the JVM suite already covers exactly.
    androidTestImplementation(testFixtures(project(":domain")))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The integration target's coordinates, from Gradle properties or the environment.
 *
 * Property first, environment second: a developer sets these once in
 * `~/.gradle/gradle.properties` (outside the repo, so it cannot be committed), and CI
 * supplies the same values as secrets, which arrive as environment variables.
 */
fun sipTestTargetArguments(): Map<String, String> {
    val keys = mapOf(
        // Gradle property -> instrumentation argument
        "sip.test.host" to "sipTestHost",
        "sip.test.domain" to "sipTestDomain",
        "sip.test.port" to "sipTestPort",
        "sip.test.extension" to "sipTestExtension",
        "sip.test.extension.secondary" to "sipTestExtensionSecondary",
        "sip.test.password" to "sipTestPassword",
        // The conference room to dial (Tasks 60, 61). Optional: absent means 3000, which
        // is what docs/testing.md reserves and what FreeSWITCH's stock dialplan maps.
        "sip.test.conference" to "sipTestConference",
    )
    return keys.mapNotNull { (property, argument) ->
        val environmentName = property.uppercase().replace('.', '_')
        val value = providers.gradleProperty(property)
            .orElse(providers.environmentVariable(environmentName))
            .orNull
        value?.let { argument to it }
    }.toMap()
}
