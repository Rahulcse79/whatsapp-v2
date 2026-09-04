import com.android.build.api.dsl.CommonExtension
import com.whatsappv2.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Adds Jetpack Compose to a module that already applies an Android convention plugin.
 *
 * Kotlin 2.x moved the Compose compiler into its own Gradle plugin versioned in
 * lockstep with Kotlin, which is why no separate compiler version is pinned.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val extension = extensions.findByType(CommonExtension::class.java)
            ?: error(
                "whatsappv2.android.compose requires an Android plugin. Apply " +
                    "whatsappv2.android.library or whatsappv2.android.application first.",
            )

        extension.buildFeatures.compose = true

        val bom = libs.findLibrary("compose-bom").orElseThrow {
            IllegalStateException("compose-bom missing from gradle/libs.versions.toml")
        }
        dependencies {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
        }
    }
}
