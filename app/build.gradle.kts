plugins {
    id("whatsappv2.android.application")
    id("whatsappv2.android.compose")
    id("whatsappv2.hilt")
}

// The four Lyra weight files, staged under build/ so they land in the APK as assets/lyra/*
// (ADR-008, Exit A). Sourced from the vendored tree rather than copied into app/src: the
// weights have one home, the pin in tools/vendor/pins.sh, and `LyraModels.VERSION` names
// the same commit. A task of its own rather than a raw asset srcDir on model_coeffs: a
// srcDir contributes its contents at the asset root, and the codec is handed a directory,
// not four files — and AGP's variant API wants a task it can wire, not a path.
abstract class StageLyraAssets : DefaultTask() {
    @get:InputDirectory
    abstract val modelDir: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val target = output.get().asFile.resolve("lyra").apply { mkdirs() }
        listOf("lyra_config.binarypb", "lyragan.tflite", "quantizer.tflite", "soundstream_encoder.tflite")
            .forEach { name -> modelDir.get().asFile.resolve(name).copyTo(target.resolve(name), overwrite = true) }
    }
}

val lyraAssets = tasks.register<StageLyraAssets>("stageLyraAssets") {
    modelDir.set(rootProject.layout.projectDirectory.dir("third_party/lyra/lyra/model_coeffs"))
    output.set(layout.buildDirectory.dir("generated/lyra-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(lyraAssets, StageLyraAssets::output)
    }
}

android {
    namespace = "com.whatsappv2"

    defaultConfig {
        applicationId = "com.whatsappv2"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The SIP stack ships prebuilt native libraries. This has to be declared in
            // the application module: abiFilters in a library only governs that library's
            // own native build, not which .so the APK ends up carrying. Only the ABIs
            // Android still requires are packaged - 32-bit x86 has been dead on real
            // devices for years and only adds to the APK. CI asserts the packaged set.
            //
            // `-Ppjsip.abis` narrows this too, and it MUST. Building one ABI while still
            // declaring three produces an APK that Android happily installs on an
            // armeabi-v7a phone — because `lib/armeabi-v7a/` exists, carrying androidx's
            // libraries — and which then dies on `System.loadLibrary("pjsua2")`. That is
            // exactly the UnsatisfiedLinkError N-14 exists to remove, reintroduced by a
            // convenience flag. Narrowing the filter makes the APK honest: it declares
            // only the ABI it can actually run, so an incompatible device refuses to
            // install rather than installing and failing on the first call.
            abiFilters += providers.gradleProperty("pjsip.abis")
                .map { it.split(",").map(String::trim).filter(String::isNotEmpty).toSet() }
                .getOrElse(setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        // A debug key shared across machines, when one is supplied.
        //
        // AGP's default is `~/.android/debug.keystore`, generated per machine. On CI that
        // means a different key on every runner, so an APK from one run will not install
        // over an APK from another: the package manager sees a different signer and
        // refuses with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The only way past it is
        // `adb uninstall`, which erases the SIP accounts, their credentials and the call
        // log - so every build under test began from an empty app, which is not the state
        // any of the bugs worth finding live in.
        //
        // Supplied through the environment rather than a file in the tree: `ci.yml` fails
        // the build if a keystore is ever committed, and that gate is correct. CI decodes
        // one from a secret into the runner's temp directory and names it here.
        //
        // Absent - a local build, a fork, a clone - nothing is configured and AGP's
        // default applies, exactly as before.
        val keystore = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
        if (!keystore.isNullOrBlank()) {
            getByName("debug") {
                storeFile = file(keystore)
                storePassword =
                    providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull ?: "android"
                keyAlias =
                    providers.environmentVariable("DEBUG_KEY_ALIAS").orNull ?: "androiddebugkey"
                keyPassword =
                    providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // Task 64, DoD 1. R8 in full mode - the AGP default since 8.0 and stated
            // explicitly in gradle.properties so an upgrade cannot quietly change it.
            //
            // Shrinking resources as well as code: the SIP stack's native libraries
            // dominate this APK, but the Compose and Material resources behind them are
            // not free either, and an unshrunk release build is a release build nobody
            // has actually tested the shape of.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                // AGP's own optimised defaults, not the plain ones: the difference is
                // whether R8 is allowed to inline and merge, which is most of the point.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Deliberately NOT signed here. A signing config needs a keystore, and a
            // keystore in git is a compromised keystore - `assembleRelease` therefore
            // produces an unsigned APK, which is what CI verifies and what a release
            // pipeline signs with credentials it holds itself.
        }
    }

    testOptions {
        // Robolectric needs the merged resources and manifest to build a real
        // Application and Activity on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:designsystem"))
    // The composition root wires the data modules in: their Hilt modules must be on the
    // graph, and :app is the only module permitted to know about every layer.
    implementation(project(":data:account"))
    implementation(project(":data:calllog"))
    implementation(project(":data:contacts"))
    implementation(project(":data:settings"))
    implementation(project(":data:sip"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:calls"))
    implementation(project(":feature:dialer"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    // Task 38 / ADR-004. The SDK only - the google-services PLUGIN is deliberately not
    // applied, because it requires a google-services.json, and a checked-in one would tie
    // every build to one Firebase project and put deployment configuration in git. Adding
    // that file and the plugin is a deployment step; without it the app builds, runs, and
    // simply has no push wake path, which PushTokenPublisher logs rather than hides.
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Task 65, DoD 16. Debug only, and that is the whole arrangement: LeakCanary installs
    // itself, dumps the heap in-process and holds every watched object weakly, which is a
    // profiling cost nobody should pay in a release build. It finds the leak class this
    // app is most exposed to - a Compose screen or a Telecom connection holding a call
    // that ended - which is exactly what §6 forbids and what no unit test can see.
    debugImplementation(libs.leakcanary)

    // Proves the fake is consumable from an Android module (Task 11 done-when #2) and
    // is what lets the whole app run with no SIP server (DoD 4).
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
