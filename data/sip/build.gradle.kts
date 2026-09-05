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
}
