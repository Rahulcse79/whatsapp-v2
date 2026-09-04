package com.whatsappv2.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project

/**
 * Android settings shared by application and library modules.
 *
 * Typed against [CommonExtension] from `com.android.build.api.dsl` — the supported
 * public DSL — rather than the legacy `com.android.build.gradle` classes, which are
 * the part of AGP most likely to move between major versions.
 *
 * Note [CommonExtension] takes NO type arguments in AGP 9; it was generic over six
 * parameters in AGP 8. Adding them back is a compile error, not a deprecation.
 */
internal fun Project.configureAndroid(extension: CommonExtension) {
    val javaTarget = javaVersionOf(libs.intVersion("jvmToolchain"))

    extension.apply {
        compileSdk = libs.intVersion("compileSdk")

        defaultConfig {
            minSdk = libs.intVersion("minSdk")
        }

        compileOptions {
            sourceCompatibility = javaTarget
            targetCompatibility = javaTarget
            // Lets minSdk 26 use modern java.time and stream APIs.
            isCoreLibraryDesugaringEnabled = true
        }

        lint {
            warningsAsErrors = true
            abortOnError = true
            checkDependencies = true
            // Reports are read from CI artifacts, so keep both formats.
            htmlReport = true
            xmlReport = true
        }

        packaging {
            resources {
                excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                    "/META-INF/*.version",
                    "/kotlin/**",
                    "DebugProbesKt.bin",
                )
            }
        }
    }

    // Required by isCoreLibraryDesugaringEnabled — without it the build fails at
    // dex time rather than at configuration time, which is a confusing place to learn.
    dependencies.add(
        "coreLibraryDesugaring",
        libs.findLibrary("android-desugarJdkLibs").orElseThrow {
            IllegalStateException("android-desugarJdkLibs missing from gradle/libs.versions.toml")
        },
    )

    configureKotlin()
}
