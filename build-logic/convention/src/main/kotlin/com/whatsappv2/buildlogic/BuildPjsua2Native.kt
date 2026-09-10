package com.whatsappv2.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Stage 2: cross-compiles the native stack, once per ABI, through the CMake entry point.
 *
 * ## Why this drives CMake itself rather than going through `externalNativeBuild`
 *
 * That was the first shape, and AGP could not model it. `externalNativeBuild` expects a
 * CMake project whose **targets** it can enumerate — it reads them from CMake's file API and
 * packages the libraries they declare. This project declares none: pjproject, OpenSSL, Opus
 * and libvpx are built by their own autotools, and the result is a `.so` that CMake never
 * knew about. AGP's answer was
 * `Execution failed for task ':pjsip:configureCMakeDebug[arm64-v8a]' > java.lang.NullPointerException`
 * — no message, no target named, nothing to act on.
 *
 * Adding a dummy `add_library` to satisfy the model would mean packaging a library that does
 * nothing, next to the one that does. That is a worse lie than this task.
 *
 * **N-4 is satisfied literally, not by exception.** The mandate asks for "the NDK plus
 * CMake, driven by CMake as the single entry point, reached so that one `./gradlew`
 * invocation from a clean tree produces every `.so`". This invokes
 * `cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=<abi>`
 * — which is the exact command line §2.3 option (a) specifies — from a Gradle task that
 * `assemble` depends on. One `./gradlew`, one CMake entry point, one NDK toolchain, no
 * manual step. What it does not do is hand AGP a model AGP cannot represent.
 *
 * ## The build is incremental where it matters
 *
 * OpenSSL is the expensive tree and it changes only when its pin does. `build-native.sh`
 * keeps a per-tree stamp keyed on the vendored tree's content, so a rebuild redoes
 * pjproject and not the three dependencies underneath it.
 */
abstract class BuildPjsua2Native @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {

    /**
     * `pjsip/` — what CMake is pointed at with `-S`.
     *
     * `@Internal`, and the three real inputs are declared separately below. Declaring this
     * directory as an input instead is what the first version did, and Gradle refused it:
     * `pjsip/` CONTAINS `pjsip/api/`, whose `build/` is another task's output, so the task
     * graph read as *"buildPjsua2Native consumes compileDebugJavaWithJavac's output without
     * declaring a dependency"*. Overlapping a subproject's output directory is an
     * ordering bug waiting to happen even when it is not, in this case, real.
     */
    @get:Internal
    abstract val nativeSourceDir: DirectoryProperty

    /** The CMake entry point. Changing it must rebuild. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cmakeLists: RegularFileProperty

    /** The per-ABI build script CMake drives. Changing it must rebuild. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScript: RegularFileProperty

    /**
     * `pjsip/config/`, holding `pj/config_site.h` — the declared feature set (N-8).
     *
     * A real input and not an afterthought: the feature set decides what is compiled in, so
     * a change here that did not rebuild would leave a `.so` describing a different library
     * from the one the generated Java describes — the exact drift N-13 exists to remove.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configDir: DirectoryProperty

    /** `third_party/` — the vendored trees. Read only; rule 12 hashes them. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vendoredDir: DirectoryProperty

    /** `jniLibs`-shaped output: `<abi>/libpjsua2.so` and `<abi>/libc++_shared.so`. */
    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    /** The ABIs to build. Must match `:app`'s `abiFilters` (N-6). */
    @get:Input
    abstract val abis: ListProperty<String>

    /**
     * The NDK root.
     *
     * Resolved from the environment rather than from AGP so this task can run in a job that
     * has an NDK and no Android SDK — which is what the egress-blocked offline test is.
     */
    @get:Input
    abstract val ndkRoot: Property<String>

    /**
     * The `swig` stage 2 must use — **the same one stage 1 used**.
     *
     * pjproject's `swig/java/Makefile` runs `swig` from `PATH` to emit the C++ JNI wrapper,
     * while stage 1 runs it separately to emit the Java. The `.so` exports symbols named
     * after that Java, so two different versions produce a library and a binding that
     * disagree — the drift N-13 exists to make impossible, reintroduced through `PATH`.
     *
     * An input, so a changed swig rebuilds rather than silently reusing a `.so` built
     * against different bindings.
     */
    @get:Input
    abstract val swigExecutable: Property<String>

    /** Asserted inside the script, so stage 2 cannot quietly use a different version. */
    @get:Input
    abstract val expectedSwigVersion: Property<String>

    @TaskAction
    fun build() {
        val ndk = File(ndkRoot.get())
        check(ndk.isDirectory) {
            "ANDROID_NDK_ROOT does not point at a directory: $ndk\n" +
                "  The native stack is built from source and there is no prebuilt fallback " +
                "(N-14). CI installs NDK r27c; a local build needs the same one, which is " +
                "why builds run in CI rather than on a laptop."
        }
        val toolchain = File(ndk, "build/cmake/android.toolchain.cmake")
        check(toolchain.isFile) { "the NDK at $ndk has no CMake toolchain file at $toolchain" }

        val source = nativeSourceDir.get().asFile
        val out = jniLibsDir.get().asFile

        abis.get().forEach { abi ->
            logger.lifecycle("pjsua2: configuring $abi")
            val buildDir = File(temporaryDir, "cmake/$abi")
            val abiOut = File(out, abi).apply { mkdirs() }

            exec.exec {
                commandLine(
                    "cmake",
                    "-S", source.absolutePath,
                    "-B", buildDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-$ANDROID_API",
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${abiOut.absolutePath}",
                    "-DPJSIP_SWIG=${swigExecutable.get()}",
                    "-DPJSIP_SWIG_VERSION=${expectedSwigVersion.get()}",
                    // Ninja is not required and is not assumed: the entry point drives
                    // upstream's build systems, so the generator has one custom target to
                    // run and its choice buys nothing.
                    "-G", "Unix Makefiles",
                )
            }

            logger.lifecycle("pjsua2: building $abi (2-3 minutes from cold)")
            exec.exec { commandLine("cmake", "--build", buildDir.absolutePath) }

            // N-6, at the point the libraries are produced rather than only at packaging.
            // A missing library here names the ABI and the file; discovered later it is an
            // UnsatisfiedLinkError on a handset.
            EXPECTED.forEach { lib ->
                check(File(abiOut, lib).isFile) {
                    "the native build produced no $lib for $abi. Expected $EXPECTED in $abiOut"
                }
            }
        }

        logger.lifecycle("pjsua2: ${abis.get().size} ABIs, ${EXPECTED.size} libraries each")
    }

    private companion object {
        /** Must match `:app`'s `minSdk`. An ABI built for a different level fails in between. */
        const val ANDROID_API = 26

        /**
         * `libpjsua2.so` is the stack; `libc++_shared.so` is the NDK runtime it links
         * against. Without the second, `System.loadLibrary("pjsua2")` fails with
         * "library libc++_shared.so not found" — an APK that installs and cannot load.
         */
        val EXPECTED = listOf("libpjsua2.so", "libc++_shared.so")
    }
}
