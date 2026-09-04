import com.android.build.api.dsl.ApplicationExtension
import com.whatsappv2.buildlogic.configureAndroid
import com.whatsappv2.buildlogic.intVersion
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

        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig.targetSdk = libs.intVersion("targetSdk")
        }
    }
}
