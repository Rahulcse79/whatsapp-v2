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
        // NOT org.jetbrains.kotlin.android: AGP 9.0 has built-in Kotlin support and
        // applying that plugin is a hard error ("no longer required for Kotlin support
        // since AGP 9.0"). See https://kotl.in/gradle/agp-built-in-kotlin
        pluginManager.apply("whatsappv2.detekt")
        // As in the library convention. Without it :app produces no Kover report, so the
        // coverage gate measured every module EXCEPT the one holding the service, the
        // navigation and the permission model - and reported nothing rather than zero,
        // which is why it went unnoticed until the gate first ran against Task 28's
        // com/whatsappv2/service threshold.
        pluginManager.apply("org.jetbrains.kotlinx.kover")

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
