package com.whatsappv2.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Compiler settings shared by every module, Android and JVM alike.
 *
 * Configured on the compile tasks rather than through the Kotlin extension: the task
 * type has been stable across Kotlin releases, whereas the extension's shape has not.
 */
internal fun Project.configureKotlin() {
    val target = libs.intVersion("jvmToolchain")

    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(target.toString()))

            // Opt in on CI only. Locally a deprecation warning should not stop work;
            // in CI it should, so warnings cannot accumulate unnoticed.
            allWarningsAsErrors.set(
                providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false),
            )

            freeCompilerArgs.addAll(
                // Treat platform types from Java as strictly nullable.
                "-Xjsr305=strict",
                // Coroutines APIs the app relies on are still marked experimental.
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)?.apply {
        toolchain.languageVersion.set(JavaLanguageVersion.of(target))
    }
}

internal fun javaVersionOf(target: Int): JavaVersion = JavaVersion.toVersion(target)
