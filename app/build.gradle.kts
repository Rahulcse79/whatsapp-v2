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

    buildTypes {
        release {
            // Task 64, DoD 1. R8 in full mode - the AGP default since 8.0 and stated
            // explicitly in gradle.properties so an upgrade cannot quietly change it.
            //
            // Shrinking resources as well as code: the SIP stack's native libraries
            // dominate this APK, but the Compose and Material resources behind them are
            // not free either, and an unshrunk release build is a release build nobody
            // has actually tested the shape of.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                // AGP's own optimised defaults, not the plain ones: the difference is
                // whether R8 is allowed to inline and merge, which is most of the point.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Deliberately NOT signed here. A signing config needs a keystore, and a
            // keystore in git is a compromised keystore - `assembleRelease` therefore
            // produces an unsigned APK, which is what CI verifies and what a release
            // pipeline signs with credentials it holds itself.
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
    implementation(project(":data:calllog"))
    implementation(project(":data:contacts"))
    implementation(project(":data:settings"))
    implementation(project(":data:sip"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:calls"))
    implementation(project(":feature:dialer"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    // Task 38 / ADR-004. The SDK only - the google-services PLUGIN is deliberately not
    // applied, because it requires a google-services.json, and a checked-in one would tie
    // every build to one Firebase project and put deployment configuration in git. Adding
    // that file and the plugin is a deployment step; without it the app builds, runs, and
    // simply has no push wake path, which PushTokenPublisher logs rather than hides.
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Task 65, DoD 16. Debug only, and that is the whole arrangement: LeakCanary installs
    // itself, dumps the heap in-process and holds every watched object weakly, which is a
    // profiling cost nobody should pay in a release build. It finds the leak class this
    // app is most exposed to - a Compose screen or a Telecom connection holding a call
    // that ended - which is exactly what §6 forbids and what no unit test can see.
    debugImplementation(libs.leakcanary)

    // Proves the fake is consumable from an Android module (Task 11 done-when #2) and
    // is what lets the whole app run with no SIP server (DoD 4).
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
