plugins {
    id("whatsappv2.jvm.library")
}

tasks.withType<Test>().configureEach {
    // These tests read Kotlin sources from across the repository at RUNTIME, so Gradle
    // cannot infer them as task inputs. Left alone, the task goes UP-TO-DATE (or is
    // restored from the build cache) whenever this module's own sources have not
    // changed - which is almost always - and the architecture rules silently stop
    // running. The gate then reads as protection while providing none.
    //
    // This was caught by the CI step that plants a violation and requires a failure.
    // The suite is small and fast; always running it is the right trade.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
