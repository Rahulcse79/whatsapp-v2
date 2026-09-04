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

        ndk {
            // The SIP stack ships native libraries. Only the ABIs Android still requires
            // are packaged: 32-bit x86 has been dead on real devices for years and only
            // adds to the APK.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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

    testImplementation(libs.kotlinx.coroutines.test)
}
