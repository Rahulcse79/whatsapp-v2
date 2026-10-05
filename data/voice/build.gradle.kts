plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.data.voice"

    // The model is vendored under third_party/ and copied in by :app, exactly as Lyra's
    // coefficients are — see app/build.gradle.kts. Nothing is downloaded at build time.
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
