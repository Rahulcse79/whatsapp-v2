package com.whatsappv2.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Access to the `libs` version catalog from inside a convention plugin.
 *
 * Convention plugins are compiled separately from the main build, so the generated
 * type-safe `libs` accessor is not available to them; the catalog has to be looked up
 * by name instead. Every version the build uses still comes from one file.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version '$alias' is missing from gradle/libs.versions.toml")
    }.requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int =
    version(alias).toIntOrNull()
        ?: error("Version '$alias' must be an integer, but was '${version(alias)}'")
