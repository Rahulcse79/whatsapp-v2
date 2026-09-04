import com.whatsappv2.buildlogic.libs
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Static analysis, applied to every module by the other convention plugins.
 *
 * ktlint's formatting rules arrive through `detekt-formatting`, which wraps them,
 * rather than through a second plugin: the jlleitschuh ktlint-gradle plugin is not
 * published to Maven Central, and one analysis tool is simpler than two.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<DetektExtension> {
            // Start from detekt's defaults and override only what we disagree with,
            // so new rules in a detekt upgrade are picked up instead of silently missed.
            buildUponDefaultConfig = true
            config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
            parallel = true
            // No baseline. A baseline is how suppressed findings become permanent.
            ignoreFailures = false
        }

        val formatting = libs.findLibrary("detekt-formatting").orElseThrow {
            IllegalStateException("detekt-formatting missing from gradle/libs.versions.toml")
        }
        dependencies {
            add("detektPlugins", formatting)
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget = libs.findVersion("jvmToolchain").get().requiredVersion
            reports {
                html.required.set(true)
                xml.required.set(true)
                sarif.required.set(true)
                txt.required.set(false)
                md.required.set(false)
            }
        }
    }
}
