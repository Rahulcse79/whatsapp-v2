plugins {
    id("whatsappv2.jvm.library")
}

dependencies {
    // `api`, not `implementation`: Outcome appears in this module's public signatures.
    api(project(":core:common"))
}
