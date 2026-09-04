plugins {
    `kotlin-dsl`
}

group = "com.whatsappv2.buildlogic"

// Must match the toolchain the main build uses, or Gradle refuses to load the plugins.
kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    // compileOnly: these plugins are on the build classpath of the CONSUMING build,
    // not shipped by build-logic itself. Using `implementation` would put a second
    // copy of AGP on the classpath and break plugin resolution.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "whatsappv2.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "whatsappv2.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "whatsappv2.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "whatsappv2.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = "whatsappv2.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("detekt") {
            id = "whatsappv2.detekt"
            implementationClass = "DetektConventionPlugin"
        }
    }
}
