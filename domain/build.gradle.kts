plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    // `api`, not `implementation`: Outcome appears in this module's public signatures.
    api(project(":core:common"))

    // @Inject on use-case constructors. javax.inject is a plain JVM annotation library,
    // so this does not put Android on :domain's classpath - the architecture test still
    // passes, and it is what lets the DI layer construct a use case with no module.
    api(libs.javax.inject)

    // FakeSipEngine (Task 11) is published from testFixtures so :feature:* and :app can
    // drive the whole app with no SIP server (DoD 4).
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(testFixtures(project(":core:common")))
}
