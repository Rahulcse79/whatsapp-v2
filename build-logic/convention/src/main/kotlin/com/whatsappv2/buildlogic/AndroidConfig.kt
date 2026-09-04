package com.whatsappv2.buildlogic

import org.gradle.api.Project

/**
 * Android settings that are NOT expressed through the Android DSL.
 *
 * AGP 9 removed `defaultConfig`, `compileOptions`, `lint` and `packaging` from
 * [com.android.build.api.dsl.CommonExtension], which is no longer generic either.
 * There is therefore no shared supertype to configure them through, so each
 * convention plugin configures its own concrete extension and this file holds only
 * what genuinely is common.
 */
internal fun Project.configureAndroidCommon() {
    // Required by isCoreLibraryDesugaringEnabled. Without it the failure surfaces at
    // dex time, which is a confusing place to learn about a misconfiguration.
    dependencies.add(
        "coreLibraryDesugaring",
        libs.findLibrary("android-desugarJdkLibs").orElseThrow {
            IllegalStateException("android-desugarJdkLibs missing from gradle/libs.versions.toml")
        },
    )

    dependencies.add("testImplementation", libs.findLibrary("kotlin-test").get())
    dependencies.add("testImplementation", libs.findLibrary("kotlin-test-junit").get())
    dependencies.add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())

    configureKotlin()
}
