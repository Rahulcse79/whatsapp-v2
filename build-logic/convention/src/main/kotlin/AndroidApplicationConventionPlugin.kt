import com.android.build.api.dsl.ApplicationExtension
import com.whatsappv2.buildlogic.configureAndroidCommon
import com.whatsappv2.buildlogic.intVersion
import com.whatsappv2.buildlogic.javaVersionOf
import com.whatsappv2.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Applied by `:app`. The only module that produces an APK. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("whatsappv2.detekt")

        val javaTarget = javaVersionOf(libs.intVersion("jvmToolchain"))

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")

            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
            }

            compileOptions {
                sourceCompatibility = javaTarget
                targetCompatibility = javaTarget
                // Lets minSdk 26 use modern java.time and stream APIs.
                isCoreLibraryDesugaringEnabled = true
            }
        }

        configureAndroidCommon()
    }
}
