plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.data.chat"

    buildFeatures {
        // The only reason this module has a BuildConfig at all: the Coral login endpoint
        // encrypts its two credential fields with a key agreed out of band.
        buildConfig = true
    }

    defaultConfig {
        // Task 32's pattern, applied to a second credential: supplied at build time and
        // never committed. Absent is a SUPPORTED state - the build compiles, the app
        // installs and places calls, and chat sign-in reports "not configured in this
        // build" rather than a wrong password. See CoralCipherMaterial.
        // No IV field: the server reads a random one from the front of each ciphertext,
        // so the client generates one per message rather than sharing a fixed one.
        buildConfigField("String", "CORAL_CIPHER_KEY", "\"${coralProperty("coral.cipher.key")}\"")
    }
}

/**
 * A build-time credential, from a Gradle property or the environment.
 *
 * Property first, environment second - a developer sets it once in
 * `~/.gradle/gradle.properties` (outside the repository, so it cannot be committed) and CI
 * supplies the same value as a secret, which arrives as an environment variable. Read
 * through `providers` rather than `findProperty` so the configuration cache records it as
 * an input and a changed value actually re-configures the build.
 *
 * Empty means absent. It must not fail the build: see the comment above.
 */
fun coralProperty(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name.uppercase().replace('.', '_')))
    .getOrElse("")

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))

    // For CredentialCipher and nothing else. Decision D3 says the chat token is stored the
    // way SIP passwords are — through the Keystore-backed AES-GCM cipher that module owns —
    // and a second cipher for the same job on the same device would be two things to get
    // right. This is a data→data dependency, which no architecture rule forbids and which
    // rule 3 (features never reach into :data) is unaffected by.
    implementation(project(":data:account"))

    // The ONLY module that may depend on the chat SDK. Architecture rule 13 enforces the
    // import side of that; this line is the build side, and both are needed — a rule that
    // forbids the import while the dependency is available everywhere is a rule somebody
    // works around without noticing.
    implementation(project(":chatsdk"))

    // :chatsdk's own dependencies are `implementation`, so none of these is inherited from
    // it. This module declares them because it talks to a SECOND backend — the Coral UC
    // platform under /services/ — with its own client (docs/chat-auth-and-contacts-plan.md §1.2).
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
