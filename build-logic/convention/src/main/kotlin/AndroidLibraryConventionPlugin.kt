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
        // NOT org.jetbrains.kotlin.android: AGP 9.0 has built-in Kotlin support and
        // applying that plugin is a hard error ("no longer required for Kotlin support
        // since AGP 9.0"). See https://kotl.in/gradle/agp-built-in-kotlin
        pluginManager.apply("whatsappv2.detekt")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

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

            // AGP registers src/<name>/java for every source set but only src/main/kotlin.
            // Variant-specific Kotlin (src/debug, src/release) is therefore invisible
            // unless registered here. The Kotlin compiler picks up .kt files from the
            // java source dirs, so this needs no Kotlin-specific DSL.
            sourceSets.configureEach {
                java.srcDir("src/$name/kotlin")
            }
        }

        configureAndroidCommon()
    }
}
