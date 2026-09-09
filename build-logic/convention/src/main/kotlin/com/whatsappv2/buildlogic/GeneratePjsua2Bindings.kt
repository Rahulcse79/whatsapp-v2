package com.whatsappv2.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Stage 1 of the native build: the pjsua2 Java bindings, generated from vendored source.
 *
 * SWIG parses the pjsua2 headers and emits `org.pjsip.pjsua2`. It needs no NDK, no OpenSSL
 * and no cross-compile, and it takes about **15 seconds** — measured, not estimated
 * (`docs/reconciliation.md` B-7). That is what makes N-13 affordable: generating the
 * bindings on every build is as cheap as reading them from git, and it removes the drift
 * between the Java and the `.so` that a committed copy allows.
 *
 * ## Why this is a task class and not a `doLast { }`
 *
 * Correct up-to-date checking. The inputs are a 57 MB vendored tree and one header; without
 * declared inputs and outputs the generator either re-runs on every build (15 s × every
 * `assembleDebug`) or, far worse, goes UP-TO-DATE when `config_site.h` changes and emits an
 * API describing a library nobody is shipping.
 *
 * `@PathSensitive(RELATIVE)` on the vendored tree so the cache key is the content and the
 * layout, not the absolute path a runner happened to check out into.
 */
@CacheableTask
abstract class GeneratePjsua2Bindings @Inject constructor(
    private val exec: ExecOperations,
    private val layout: ProjectLayout,
) : DefaultTask() {

    /** The vendored pjproject tree. Read only — never written to (N-7, architecture rule 12). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pjprojectDir: DirectoryProperty

    /**
     * The directory holding `pj/config_site.h` — the declared feature set (N-8).
     *
     * A separate input from [pjprojectDir] on purpose: it is *this repository's* decision,
     * it changes on a different schedule from the vendored tree, and putting it first on
     * the include path is what lets `#include <pj/config_site.h>` resolve without anything
     * being written into `third_party/`.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configSiteDir: DirectoryProperty

    /** The generated Java source root: the `org.pjsip.pjsua2` package plus the five helpers. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * The SWIG version this build is pinned to.
     *
     * Asserted rather than accepted. The `.so` exports symbols named after the Java SWIG
     * generated beside it, so a different SWIG is a different JNI surface: between 4.2.0 and
     * 4.4.1 the typed vector classes rename `add`/`reserve` to `doAdd`/`doReserve` and 45
     * files differ. Left unchecked, a runner-image bump changes the bindings silently and
     * the failure arrives as an `UnsatisfiedLinkError` on a handset. Here it is a build
     * failure naming both versions.
     */
    @get:Input
    abstract val expectedSwigVersion: Property<String>

    @TaskAction
    fun generate() {
        val pjproject = pjprojectDir.get().asFile
        val output = outputDirectory.get().asFile

        checkSwigVersion()

        // A clean output root every run. A stale class from a previous feature set compiles
        // perfectly and describes a library that is no longer being built.
        output.deleteRecursively()
        val javaOut = File(output, "org/pjsip/pjsua2").apply { mkdirs() }
        val helperOut = File(output, "org/pjsip").apply { mkdirs() }
        val wrapper = File(layout.buildDirectory.get().asFile, "pjsua2-swig/pjsua2_wrap.cpp")
        wrapper.parentFile.mkdirs()

        // The include set upstream's own java/Makefile lists, with ONE addition that has to
        // come first: the directory holding this project's config_site.h. SWIG parses that
        // header, so the generated API follows the declared feature set (N-8).
        val includes = listOf(configSiteDir.get().asFile) + INCLUDE_SUBDIRS.map { File(pjproject, it) }
        includes.forEach { check(it.isDirectory) { "SWIG include directory is missing: $it" } }

        exec.exec {
            workingDir = File(pjproject, SWIG_DIR)
            commandLine(
                buildList {
                    add("swig")
                    includes.forEach { add("-I${it.absolutePath}") }
                    // -c++ and -D__ANDROID__ match the green workflow's invocation exactly
                    // (.github/workflows/build-pjsip.yml:110-117); the Android define is
                    // what selects the camera and audio device backends in the headers.
                    addAll(listOf("-c++", "-D__ANDROID__", "-java", "-package", "org.pjsip.pjsua2"))
                    addAll(listOf("-outdir", javaOut.absolutePath))
                    addAll(listOf("-o", wrapper.absolutePath))
                    add("pjsua2.i")
                },
            )
        }

        val generated = javaOut.listFiles { f -> f.extension == "java" }?.size ?: 0
        check(generated > 0) { "SWIG produced no Java. Nothing downstream can compile." }

        // The five classes SWIG does NOT generate. pjproject ships them beside the C they
        // are called from, and the AAR has always carried them, so an API without them is
        // not the pjsua2 API: `org.pjsip.PjCameraInfo2` is what the adapter calls before it
        // can enumerate a capture device, and without it there are no capture devices at all.
        val helpers = HELPER_DIRS.flatMap { dir ->
            File(pjproject, dir).listFiles { f -> f.extension == "java" }?.toList().orEmpty()
        }
        check(helpers.size >= EXPECTED_HELPERS) {
            "expected at least $EXPECTED_HELPERS hand-written org.pjsip helpers in the vendored " +
                "tree, found ${helpers.size}: ${helpers.map { it.name }}"
        }
        helpers.forEach { it.copyTo(File(helperOut, it.name), overwrite = true) }

        logger.lifecycle("pjsua2 bindings: $generated generated, ${helpers.size} helpers copied")
    }

    /** Fails with both versions named, rather than letting the JNI surface change quietly. */
    private fun checkSwigVersion() {
        val expected = expectedSwigVersion.get()
        val stdout = ByteArrayOutputStream()
        try {
            exec.exec {
                commandLine("swig", "-version")
                standardOutput = stdout
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "swig is not on PATH. The bindings are generated from third_party/pjproject " +
                    "on every build (N-13), so swig is a build tool this project requires — " +
                    "see docs/native-dependencies.md §3. On CI: apt-get install -y swig.",
                e,
            )
        }

        val found = VERSION.find(stdout.toString())?.groupValues?.get(1)
        check(found == expected) {
            "swig $found is on PATH and this build is pinned to $expected.\n" +
                "  The .so exports symbols named after the Java swig generates, so a different " +
                "swig is a different JNI surface — between 4.2.0 and 4.4.1 the typed vector " +
                "classes rename add/reserve to doAdd/doReserve and 45 files differ.\n" +
                "  Install $expected, or change pjsip.swig.version in gradle.properties " +
                "DELIBERATELY and rebuild the .so with the same one."
        }
    }

    private companion object {
        const val SWIG_DIR = "pjsip-apps/src/swig"
        const val EXPECTED_HELPERS = 5

        val INCLUDE_SUBDIRS = listOf(
            "pjlib/include",
            "pjlib-util/include",
            "pjmedia/include",
            "pjsip/include",
            "pjnath/include",
        )

        val HELPER_DIRS = listOf(
            "pjmedia/src/pjmedia-videodev/android",
            "pjmedia/src/pjmedia-audiodev/android",
        )

        val VERSION = Regex("""SWIG Version ([\d.]+)""")
    }
}
