plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.feature.settings"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":domain"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
