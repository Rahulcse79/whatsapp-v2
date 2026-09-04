// Root build file. No module logic lives here — that moves into the `build-logic`
// convention plugins in Task 3.
//
// Plugins are declared `apply false` so Gradle resolves their markers (proving the
// version catalog pins are real and reachable) without applying them to any project.
// Nothing here requires the Android SDK, which is why Task 2 builds on a bare runner.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
