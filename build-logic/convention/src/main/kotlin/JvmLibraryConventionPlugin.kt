import com.whatsappv2.buildlogic.configureKotlin
import com.whatsappv2.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applied by `:domain` only.
 *
 * Deliberately applies NO Android plugin. `:domain` must stay a pure JVM module so the
 * dependency rule in §4.1 is enforced by the compiler rather than by convention — this
 * is what makes DoD 2 ("`:domain` has zero Android dependencies") a build failure
 * instead of a code-review note.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        pluginManager.apply("whatsappv2.detekt")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        configureKotlin()

        // Declared here, not per module, so a module's build file stays at the
        // five-line budget (Task 3 done-when #1).
        dependencies {
            add("testImplementation", libs.findLibrary("kotlin-test").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
