// Root build file. No module logic lives here — that moves into the `build-logic`
// convention plugins in Task 3.
//
// Plugins are declared `apply false` so Gradle resolves their markers (proving the
// version catalog pins are real and reachable) without applying them to any project.
// Nothing here requires the Android SDK, which is why Task 2 builds on a bare runner.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // kotlin.android is deliberately absent: AGP 9 provides Kotlin for Android modules
    // itself, and applying the standalone plugin is an error. kotlin.jvm below still
    // puts the Kotlin Gradle plugin jar on the classpath, which the convention plugins
    // need for the KotlinCompile task type.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false

    // `apply false` here is what puts each plugin's jar on the build classpath. The
    // convention plugins in `build-logic` depend on these as `compileOnly`, so without
    // this the build fails at RUNTIME with NoClassDefFoundError when a convention
    // plugin tries to apply one.
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false

    // Applied, not merely resolved, so `./gradlew detekt` has something to run and the
    // root build script is itself analysed.
    alias(libs.plugins.whatsappv2.detekt)
}
