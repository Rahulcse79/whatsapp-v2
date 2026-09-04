import com.whatsappv2.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Dependency injection for a module.
 *
 * Uses KSP rather than kapt: kapt runs a full Java annotation-processing round and is
 * markedly slower, and Hilt has supported KSP for some time.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        val hiltAndroid = libs.findLibrary("hilt-android").orElseThrow {
            IllegalStateException("hilt-android missing from gradle/libs.versions.toml")
        }
        val hiltCompiler = libs.findLibrary("hilt-compiler").orElseThrow {
            IllegalStateException("hilt-compiler missing from gradle/libs.versions.toml")
        }

        val hiltTesting = libs.findLibrary("hilt-android-testing").orElseThrow {
            IllegalStateException("hilt-android-testing missing from gradle/libs.versions.toml")
        }

        dependencies {
            add("implementation", hiltAndroid)
            add("ksp", hiltCompiler)

            // Lets a module assert its own graph resolves, rather than discovering a
            // missing binding on a device at run time.
            add("testImplementation", hiltTesting)
            add("kspTest", hiltCompiler)
        }
    }
}
