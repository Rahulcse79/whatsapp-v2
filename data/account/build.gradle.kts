plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.whatsappv2.data.account"
}

room {
    // Exported schemas are committed. Without them Room cannot verify a migration, and a
    // schema change ships as a silent data-loss bug instead of a build failure.
    //
    // Configured through the Room Gradle plugin rather than the raw `room.schemaLocation`
    // KSP argument: with Room 2.8 the KSP-argument form fails during schema export.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
