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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Proves the fake is consumable from an Android module (Task 11 done-when #2) and
    // is what lets the whole app run with no SIP server (DoD 4).
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
