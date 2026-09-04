plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.data.settings"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
