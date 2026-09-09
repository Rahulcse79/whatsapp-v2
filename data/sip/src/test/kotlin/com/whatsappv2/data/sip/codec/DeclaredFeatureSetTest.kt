package com.whatsappv2.data.sip.codec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds [DeclaredFeatureSet] and `pjsip/config/config_site.h` together.
 *
 * ## Why this test is the point of the whole arrangement
 *
 * The feature set is written twice: once as C preprocessor defines the native build
 * compiles against, and once as Kotlin the audit compares against. **Two copies of one
 * decision is exactly the defect N-13 removes elsewhere** — it is how the committed
 * bindings drifted from the binary — and restating it in Kotlin without a check would be
 * the same mistake in a new place.
 *
 * It cannot be generated away: the header is compiled by clang and the set is read by the
 * JVM, and no build step bridges the two without a code generator nobody wants to maintain
 * for eleven lines. So the two are written separately and **asserted equal**, which is the
 * next best thing and is cheap.
 *
 * A failure here means somebody changed the build's codecs and not the audit's, or the
 * reverse. Either way the audit would then report a codec state that is not this build's,
 * which is worse than no audit — it would launder a guess into a fact.
 */
class DeclaredFeatureSetTest {

    private val configSite: String by lazy {
        val header = File(repositoryRoot(), "pjsip/config/config_site.h")
        assertTrue(header.isFile, "the declared feature set is missing: $header")
        // Comments carry example values and prose about codecs that are NOT enabled — the
        // Lyra block alone names PJMEDIA_HAS_LYRA_CODEC three times. Stripping them first
        // is what stops this test passing on a sentence.
        header.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `every codec that needs a flag has it set to 1`() {
        DeclaredFeatureSet.requiredFlags.forEach { (codec, flag) ->
            assertTrue(
                Regex("""^\s*#define\s+$flag\s+1\s*$""", RegexOption.MULTILINE).containsMatchIn(configSite),
                "DeclaredFeatureSet says '$codec' is compiled in, but config_site.h does not " +
                    "set $flag to 1. One of the two is wrong, and the audit would report a " +
                    "codec state that is not this build's.",
            )
        }
    }

    @Test
    fun `every codec declared absent has its flag set to 0`() {
        // The other direction, and the one that catches a codec silently ARRIVING: somebody
        // enables OpenH264 in the header and the audit keeps reporting H264 as NotCompiled.
        DeclaredFeatureSet.forbiddenFlags.forEach { (codec, flag) ->
            assertTrue(
                Regex("""^\s*#define\s+$flag\s+0\s*$""", RegexOption.MULTILINE).containsMatchIn(configSite),
                "config_site.h does not set $flag to 0, but nothing in DeclaredFeatureSet " +
                    "declares '$codec'. If the build now contains it, add it to `declared` " +
                    "and `compiledIn` — otherwise the audit reports it as NotCompiled for ever.",
            )
        }
    }

    @Test
    fun `no codec is both compiled in and forbidden`() {
        val overlap = DeclaredFeatureSet.compiledIn intersect DeclaredFeatureSet.forbiddenFlags.keys
        assertEquals(emptySet(), overlap, "a codec cannot be both compiled in and declared absent")
    }

    @Test
    fun `every declared codec is one the audit can resolve`() {
        // `declared` and `compiledIn` are compared by name inside CodecAuditor, so a name in
        // one and not the other silently becomes a permanent RegistrationFailed — a build
        // defect reported at ERROR, for ever, about a codec that is fine.
        val declaredNames = DeclaredFeatureSet.declared.map { it.name }.toSet()
        assertEquals(
            declaredNames,
            DeclaredFeatureSet.compiledIn,
            "declared and compiledIn must name the same codecs, or the audit reports a " +
                "permanent build defect about a codec that is not defective",
        )
    }

    @Test
    fun `names are lowercase, because matching is by prefix and case has already cost one bug`() {
        // `LYRA` prefix-matches `lyra/16000/1` NEVER, and does so silently. That shipped.
        val offenders = (
            DeclaredFeatureSet.declared.map { it.name } +
                DeclaredFeatureSet.compiledIn +
                DeclaredFeatureSet.unnegotiableOnThisDeployment
            ).filter { it != it.lowercase() }

        assertEquals(emptyList(), offenders, "codec names must be lowercase")
    }

    @Test
    fun `a codec with no peer is one this build actually contains`() {
        // "No peer accepts it" is only meaningful about a codec that registers. Naming one
        // the build does not contain would produce an absence with two contradictory
        // reasons, and CodecAudit's own invariant could not catch it.
        val stranded = DeclaredFeatureSet.unnegotiableOnThisDeployment
        assertTrue(
            DeclaredFeatureSet.compiledIn.containsAll(stranded),
            "${stranded - DeclaredFeatureSet.compiledIn} are marked unnegotiable but are not " +
                "compiled in — a codec that is not there cannot be stranded by a peer",
        )
    }

    /** Walks up to `settings.gradle.kts`, the same way the architecture rules find the root. */
    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
