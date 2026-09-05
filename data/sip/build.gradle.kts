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
            // liblinphone needs its .so files on disk to dlopen them.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)

    // ADR-001. Served from Belledonne's Maven repository, which settings.gradle.kts
    // scopes to org.linphone so it cannot answer for anything else.
    implementation(libs.linphone.sdk)

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
    )
    return keys.mapNotNull { (property, argument) ->
        val environmentName = property.uppercase().replace('.', '_')
        val value = providers.gradleProperty(property)
            .orElse(providers.environmentVariable(environmentName))
            .orNull
        value?.let { argument to it }
    }.toMap()
}

