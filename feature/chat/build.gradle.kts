plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.feature.chat"
}

dependencies {
    implementation(project(":core:designsystem"))
    // :domain only — never :data:chat, and never :chatsdk. This module must not be able
    // to name what owns the socket or the HTTP client; architecture rules 3 and 13 both
    // say so, and this is the line that makes them true rather than aspirational.
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
