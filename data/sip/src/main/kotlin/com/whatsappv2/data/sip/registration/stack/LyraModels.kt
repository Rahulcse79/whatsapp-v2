package com.whatsappv2.data.sip.registration.stack

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Puts Lyra's model files where the codec can open them (ADR-008, Exit A).
 *
 * The codec is compiled into `libpjsua2.so`; its weights are not. `pjmedia_codec_lyra`
 * takes a directory (`pjmedia-codec/lyra.h:69`) and opens four files from it when a
 * stream is created — not at init, so a wrong directory is a call that fails to
 * negotiate rather than a startup error. The four ship as APK assets, straight from
 * `third_party/lyra/lyra/model_coeffs` (the app module's Gradle file points the asset
 * source there; nothing is copied into the repository twice), and this copies them to
 * `filesDir` on first run, because an asset has no path and the codec wants one.
 *
 * ## Why a version marker and not a size check
 *
 * An asset compressed by aapt has no knowable length without reading it, and reading
 * 3.5 MB on every start to decide whether to write 3.5 MB is the copy without the
 * benefit. The marker holds the Lyra commit the weights came from; a build that bumps
 * the pin rewrites the marker, and the next start re-copies everything. The files are
 * trained weights — data, the one place N-1's "no prebuilt binaries" does not reach —
 * so their version is the pin's, not a build's.
 *
 * Pure JVM apart from [File], so it is tested with a temp directory and no device.
 */
internal object LyraModels {

    /** What `lyra.h:61-67` says the directory must contain. */
    val FILES: List<String> = listOf(
        "lyra_config.binarypb",
        "lyragan.tflite",
        "quantizer.tflite",
        "soundstream_encoder.tflite",
    )

    /** The asset directory the app module maps `model_coeffs/` onto. */
    const val ASSET_DIR = "lyra"

    /**
     * The Lyra commit the shipped weights come from — `LYRA_SHA` in `tools/vendor/pins.sh`.
     * Bumping the pin without changing this leaves old weights installed on upgraded
     * devices, which is exactly the "identifier mismatch" error the codec's own config
     * check exists to catch; the marker makes it a re-copy instead.
     */
    const val VERSION = "47698dadf0010abff6a848e02642f55f806d4842"

    private const val MARKER = ".version"

    /**
     * The longest path `pjmedia_codec_lyra_set_config` can hold: `lyra.cpp:33` sizes the
     * buffer at 64, and the copy is a truncating one (`pj_strncpy_with_null`). A path that
     * does not fit would be silently cut and then fail at the first stream.
     */
    const val MAX_PATH_LENGTH = 63

    /**
     * Installs the model files into [dir] from [open], which returns an asset stream by
     * file name or null when the asset is missing.
     *
     * @return null when every file is present and the directory path is usable, else one
     *   sentence saying what is wrong — the audit reports it against the codec.
     */
    fun install(open: (String) -> InputStream?, dir: File): String? = when {
        dir.absolutePath.length > MAX_PATH_LENGTH ->
            "model path ${dir.absolutePath} is ${dir.absolutePath.length} characters; " +
                "pjmedia_codec_lyra holds at most $MAX_PATH_LENGTH"
        isCurrent(dir) -> null
        else -> copyAll(open, dir)
    }

    /** True when all four files are present and were copied for this [VERSION]. */
    private fun isCurrent(dir: File): Boolean =
        FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } } &&
            runCatching { File(dir, MARKER).readText() }.getOrNull() == VERSION

    /** Copies every file, writing the marker only once all four are in place. */
    private fun copyAll(open: (String) -> InputStream?, dir: File): String? {
        if (!dir.isDirectory && !dir.mkdirs()) return "could not create ${dir.absolutePath}"
        File(dir, MARKER).delete()
        val problem = FILES.firstNotNullOfOrNull { name -> copyOne(open, dir, name) }
        if (problem == null) File(dir, MARKER).writeText(VERSION)
        return problem
    }

    /**
     * Copies one asset through a `.part` file, so a copy that dies half-way never leaves
     * a truncated model behind under the real name.
     */
    private fun copyOne(open: (String) -> InputStream?, dir: File, name: String): String? {
        val source = open(name) ?: return "asset $ASSET_DIR/$name is missing from the APK"
        val target = File(dir, name)
        val staging = File(dir, "$name.part")
        return try {
            source.use { input -> staging.outputStream().use { output -> input.copyTo(output) } }
            when {
                staging.length() == 0L -> "asset $ASSET_DIR/$name is empty"
                staging.renameTo(target) || (target.delete() && staging.renameTo(target)) -> null
                else -> "could not write ${target.absolutePath}"
            }
        } catch (e: IOException) {
            "copying $name to ${dir.absolutePath} failed: ${e.message}"
        }
    }
}
