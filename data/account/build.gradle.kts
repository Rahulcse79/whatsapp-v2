plugins {
    id("whatsappv2.android.library")
}

android {
    namespace = "com.whatsappv2.data.account"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
}
