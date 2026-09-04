import com.android.build.api.dsl.LibraryExtension
import com.whatsappv2.buildlogic.configureAndroidCommon
import com.whatsappv2.buildlogic.intVersion
import com.whatsappv2.buildlogic.javaVersionOf
import com.whatsappv2.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applied by every `:core:*`, `:data:*` and `:feature:*` Android module.
 *
 * The configuration below duplicates the application plugin's rather than sharing a
 * helper: AGP 9 moved these members off `CommonExtension`, so no shared supertype
 * exposes them.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("whatsappv2.detekt")

        val javaTarget = javaVersionOf(libs.intVersion("jvmToolchain"))

        extensions.configure<LibraryExtension> {
            compileSdk = libs.intVersion("compileSdk")

            defaultConfig {
                minSdk = libs.intVersion("minSdk")
            }

            compileOptions {
                sourceCompatibility = javaTarget
                targetCompatibility = javaTarget
                isCoreLibraryDesugaringEnabled = true
            }
        }

        configureAndroidCommon()
    }
}
