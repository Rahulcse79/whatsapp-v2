import com.android.build.api.dsl.LibraryExtension
import com.whatsappv2.buildlogic.configureAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Applied by every `:core:*`, `:data:*` and `:feature:*` module. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("whatsappv2.detekt")

        extensions.configure<LibraryExtension> {
            configureAndroid(this)
        }
    }
}
