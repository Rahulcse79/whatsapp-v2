plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
}

android {
    namespace = "com.whatsappv2.core.designsystem"
}

dependencies {
    // `api`: every feature module consumes these types through the design system, so
    // re-declaring them per module would be noise and would let versions drift.
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material.icons.extended)

    // A contact photo arrives as a content:// URI; nothing in Compose decodes one.
    // implementation, not api: no feature should be loading images of its own (Task 49).
    implementation(libs.coil.compose)

    // Preview rendering only; never in a release APK.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
