plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    // `api`, not `implementation`: Outcome appears in this module's public signatures.
    api(project(":core:common"))

    // FakeSipEngine (Task 11) is published from testFixtures so :feature:* and :app can
    // drive the whole app with no SIP server (DoD 4).
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(testFixtures(project(":core:common")))
}
