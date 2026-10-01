// The chat SDK (com.chatserver.sdk), vendored from
// https://github.com/sameergupta12281998/chatSDK at 8135f632 ("update sdk", 2026-09-24).
//
// **Its Java is upstream's and is not modified.** This file is the one and only change
// made inside this directory, and it exists because CI refuses what the original was:
// a Groovy build file ("Assert no Groovy build files") carrying six inline versions
// ("Assert no inline dependency versions"). Converting without moving the versions to
// the catalog turns one failing gate into the other, so the two are one change.
//
// `whatsappv2.android.library` is deliberately NOT applied. That plugin brings detekt and
// kover, which analyse Kotlin; on a Java-only module they would report nothing while
// adding tasks to every build.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.chatserver.sdk"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // MUST be preserved. The wire DTOs are Java records populated by Gson through
        // reflection; R8 runs in :app, so without these the release build renames their
        // fields and every frame silently fails to parse. Debug builds never show it.
        // Consumer rules propagate from a project dependency, not only from an AAR.
        consumerProguardFiles("consumer-rules.pro")

        // `targetSdk` is dropped: it is meaningless in a library and AGP 9 warns on it.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        // Not in the original. java.time is API 26+ so it is not strictly required at
        // minSdk 26, but every other module in this repo desugars and a mismatch is a
        // trap for whoever raises a language level later.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugarJdkLibs)

    // Upstream's own pins, carried over rather than written from memory — the catalog's
    // header records that guessing produced versions several major releases stale.
    // `implementation`, as upstream had them: a host must not inherit Retrofit or Gson
    // on its classpath by accident.
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.ulid.creator)
    implementation(libs.androidx.annotation)
}
