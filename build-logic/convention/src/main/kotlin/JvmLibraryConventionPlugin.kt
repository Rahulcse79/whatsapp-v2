import com.whatsappv2.buildlogic.configureKotlin
import org.gradle.api.Plugin
import org.gradle.api.Project

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

        configureKotlin()
    }
}
