plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.android.compose")
}

android {
    namespace = "com.whatsappv2.feature.dialer"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":domain"))
}
