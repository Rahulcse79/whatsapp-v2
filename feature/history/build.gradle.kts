plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.feature.history"
}

dependencies {
    implementation(project(":core:designsystem"))
    // :domain only - never :data. A feature that reached into a data module would bypass
    // the repository interface and every test seam the domain layer exists to provide.
    implementation(project(":domain"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Paging is this module's alone. PagingData is an androidx type and :domain may not
    // hold one, so the PagingSource lives here and reads the repository a page at a time.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.androidx.paging.common)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
