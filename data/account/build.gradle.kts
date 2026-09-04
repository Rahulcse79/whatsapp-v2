plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android {
    namespace = "com.whatsappv2.data.account"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
}
