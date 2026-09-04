plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.withType<Test>().configureEach {
    // Konsist reads source from disk, so the rules need the repository root. Captured
    // as a String rather than a File so the configuration cache can serialise it.
    systemProperty("whatsappv2.rootDir", rootProject.projectDir.absolutePath)
}
