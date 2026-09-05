plugins {
    id("whatsappv2.android.application")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2"

    defaultConfig {
        applicationId = "com.whatsappv2"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The SIP stack ships prebuilt native libraries. This has to be declared in
            // the application module: abiFilters in a library only governs that library's
            // own native build, not which .so the APK ends up carrying. Only the ABIs
            // Android still requires are packaged - 32-bit x86 has been dead on real
            // devices for years and only adds to the APK. CI asserts the packaged set.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    testOptions {
        // Robolectric needs the merged resources and manifest to build a real
        // Application and Activity on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:designsystem"))
    // The composition root wires the data modules in: their Hilt modules must be on the
    // graph, and :app is the only module permitted to know about every layer.
    implementation(project(":data:account"))
    implementation(project(":data:settings"))
    implementation(project(":data:sip"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:calls"))
    implementation(project(":feature:dialer"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Proves the fake is consumable from an Android module (Task 11 done-when #2) and
    // is what lets the whole app run with no SIP server (DoD 4).
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
