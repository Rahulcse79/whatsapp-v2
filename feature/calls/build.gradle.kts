plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.feature.calls"
}

dependencies {
    implementation(project(":core:designsystem"))
    // :domain only - never :data. A feature that reached into a data module would bypass
    // the repository interface and every test seam the domain layer exists to provide.
    implementation(project(":domain"))
    // The clock. A call timer read from System.currentTimeMillis() is a timer no test can
    // assert, and the duration is the one thing on this screen that changes by itself.
    implementation(project(":core:common"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
