plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    // MutableClock ships from testFixtures so every module gets the same test clock.
    testImplementation(testFixtures(project(":core:common")))
}
