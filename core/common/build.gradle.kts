plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    // `api`, not `implementation`: `Outcome`, `Secret`, `Logger`, `redact` and `Clock`
    // all moved into the multiplatform module and they appear in this module's public
    // signatures — and, far more importantly, in those of every module that depends on
    // this one. Their packages did not change on the way across
    // (`com.whatsappv2.core.common.result`, `.logging`, `.secret`, `.time`), so no
    // import anywhere in the build was edited. See `shared/build.gradle.kts`.
    api(project(":shared"))

    api(libs.kotlinx.coroutines.core)

    // MutableClock ships from testFixtures so every module gets the same test clock.
    testImplementation(testFixtures(project(":core:common")))
}
