package com.whatsappv2.data.sip.registration.stack

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model files reach the directory the codec is told about, once, and only the
 * right ones (ADR-008, Exit A).
 */
class LyraModelsTest {

    private val assets: Map<String, ByteArray> = LyraModels.FILES.associateWith { "$it-bytes".toByteArray() }
    private fun open(name: String): InputStream? = assets[name]?.let(::ByteArrayInputStream)

    // Under /tmp, not the JVM's default temp root: macOS puts that under
    // /var/folders/<two random segments>/T/, which is already past the 63 characters the
    // codec's path buffer holds, and the guard for that has its own test below.
    private fun tempDir(): File = Files.createTempDirectory(File("/tmp").toPath(), "l").toFile().resolve("lyra")

    @Test
    fun `a fresh directory receives all four files and the version marker`() {
        val dir = tempDir()

        assertNull(LyraModels.install(::open, dir))

        LyraModels.FILES.forEach { name ->
            assertEquals("$name-bytes", File(dir, name).readText(), name)
        }
        assertEquals(LyraModels.VERSION, File(dir, ".version").readText())
    }

    @Test
    fun `an installed directory is not copied again`() {
        val dir = tempDir()
        assertNull(LyraModels.install(::open, dir))
        var opened = 0

        assertNull(
            LyraModels.install(
                open = { name ->
                    opened++
                    open(name)
                },
                dir = dir,
            ),
        )

        assertEquals(0, opened, "nothing should be read from the APK when the marker matches")
    }

    @Test
    fun `a different version re-copies, so a bumped pin never runs on old weights`() {
        val dir = tempDir()
        assertNull(LyraModels.install(::open, dir))
        File(dir, ".version").writeText("0000000000000000000000000000000000000000")
        File(dir, "lyragan.tflite").writeText("stale")

        assertNull(LyraModels.install(::open, dir))

        assertEquals("lyragan.tflite-bytes", File(dir, "lyragan.tflite").readText())
        assertEquals(LyraModels.VERSION, File(dir, ".version").readText())
    }

    @Test
    fun `a missing asset is named, and nothing claims to be installed`() {
        val dir = tempDir()

        val problem = LyraModels.install({ if (it == "quantizer.tflite") null else open(it) }, dir)

        assertNotNull(problem)
        assertTrue("quantizer.tflite" in problem, problem)
        assertTrue(!File(dir, ".version").exists(), "no marker for a half-installed directory")
    }

    @Test
    fun `a path the codec cannot hold is refused before anything is written`() {
        // lyra.cpp copies the path into a 64-byte buffer with a truncating strncpy; a longer
        // one would be cut silently and fail at the first stream.
        val dir = File(tempDir(), "x".repeat(80))

        val problem = LyraModels.install(::open, dir)

        assertNotNull(problem)
        assertTrue("${LyraModels.MAX_PATH_LENGTH}" in problem, problem)
        assertTrue(!dir.exists())
    }
}
