package com.whatsappv2.arch

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rules 11 and 12 — the two the native mandate adds — applied to the real project and to
 * their violating fixtures.
 *
 * ## Why these live in their own file
 *
 * Every other rule reads Kotlin source. These two do not: rule 11 asks git what is
 * **committed**, and rule 12 hashes a directory of C. Neither fits `ArchitectureRules
 * .projectFiles()`, and threading a second kind of input through the existing suites would
 * make both harder to read than one file that says so.
 *
 * ## The fixture problem, and how it is solved here
 *
 * `noPrebuiltNativeBinaries` excludes anything under [ArchitectureRules.FIXTURES] — it has
 * to, because the fixture is a committed `libfoo.so` and without the exclusion the rule
 * would fire on the real project for ever.
 *
 * That exclusion would also stop the fixture proving anything, so the two halves are
 * tested separately: `rule 11 fires…` feeds the rule the fixture paths **rewritten as
 * though they were real** — which is what git would report if somebody committed that
 * directory anywhere else — and `rule 11 does not fire on its own fixture` proves the
 * exclusion works against the paths git actually reports.
 */
class NativeMandateRulesTest {

    private val fixtureRoot = File(ArchitectureRules.projectRoot, ArchitectureRules.FIXTURES)
    private val tracked = ArchitectureRules.trackedFiles()

    // ============================================================ rule 11, clause 1

    @Test
    fun `rule 11 - no committed aar or so in the real tree`() {
        val violations = ArchitectureRules.noPrebuiltNativeBinaries(tracked)
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Rule 11 found ${violations.size} committed native binary/binaries:")
                violations.forEach { appendLine("  - $it") }
                appendLine("N-1: no binary this repository did not compile may ship inside the APK.")
            },
        )
    }

    @Test
    fun `rule 11 fires on a committed native binary`() {
        // The fixture paths as git would report them if this directory lived anywhere but
        // under the fixtures root. See the class KDoc.
        val asIfReal = fixturePathsAsIfReal()
        val violations = ArchitectureRules.noPrebuiltNativeBinaries(asIfReal)

        assertTrue(violations.isNotEmpty(), "Rule 11 found no violations in the fixtures - the rule does not work")
        assertTrue(
            violations.any { it.file.endsWith("libfoo.so") },
            "the committed .so was not detected; found $violations",
        )
    }

    @Test
    fun `rule 11 does not fire on its own fixture`() {
        // The fixture really is committed, so this is not hypothetical: without the
        // exclusion the rule above would fail on every run for ever.
        val fixtureSo = "${ArchitectureRules.FIXTURES}/nativeblob/libs/libfoo.so"
        assertTrue(fixtureSo in tracked, "the rule 11 fixture is not committed, so it proves nothing")
        assertTrue(
            ArchitectureRules.noPrebuiltNativeBinaries(tracked).none { it.file == fixtureSo },
            "the fixture exclusion does not work, and the rule would fail on every run",
        )
    }

    // ============================================================ rule 11, clause 2

    @Test
    fun `rule 11 - no build file references a prebuilt binary`() {
        val violations = ArchitectureRules.nativeBinaryReferences(ArchitectureRules.projectFiles())
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Rule 11 found ${violations.size} build-file reference(s) to a prebuilt binary:")
                violations.forEach { appendLine("  - $it") }
                appendLine("N-14: :data:sip takes :pjsip unconditionally; there is no AAR to point at.")
            },
        )
    }

    @Test
    fun `rule 11 fires on both a flatDir and a binary artifact reference`() {
        // Read as a build script rather than left as one: the file is `.gradle.kts.fixture`
        // on disk so Gradle never configures it, and the rule is scoped to `.gradle.kts`,
        // so the test supplies the name the rule looks for.
        val script = File(fixtureRoot, "nativeblob/build.gradle.kts.fixture")
        assertTrue(script.isFile, "the rule 11 build-script fixture is missing")

        val violations = ArchitectureRules.nativeBinaryReferences(
            listOf(ArchitectureRules.SourceFile("nativeblob/build.gradle.kts", script.readText())),
        )

        assertTrue(violations.isNotEmpty(), "Rule 11 clause 2 found nothing in the fixture - the rule does not work")
        assertTrue(violations.any { "flatDir" in it.detail }, "flatDir not detected; found $violations")
        assertTrue(violations.any { "libfoo.so" in it.detail }, "the .so artifact was not detected; found $violations")
        assertTrue(
            violations.any { "pjsua2.aar" in it.detail },
            "the .aar reference was not detected; found $violations",
        )
    }

    // ============================================================ rule 12

    @Test
    fun `rule 12 - every vendored tree matches its recorded hash`() {
        val violations = ArchitectureRules.vendoredTreesMatchTheirManifest()
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Rule 12 found ${violations.size} vendored-tree problem(s):")
                violations.forEach { appendLine("  - $it") }
                appendLine("N-7: every change to vendored source is a numbered patch in pjsip/patches/.")
            },
        )
    }

    @Test
    fun `rule 12 fires on an unrecorded edit to a vendored file`() {
        val violations = ArchitectureRules.vendoredTreesMatchTheirManifest(File(fixtureRoot, "vendored"))

        assertTrue(violations.isNotEmpty(), "Rule 12 found nothing in the fixture - the rule does not work")
        assertTrue(
            violations.any { "faketree" in it.file && "does not match its recorded hash" in it.detail },
            "the unrecorded edit was not detected; found $violations",
        )
    }

    @Test
    fun `rule 12 fires on a vendored tree with no recorded hash at all`() {
        // The other direction, and the more likely mistake: somebody vendors a fifth
        // dependency and forgets the manifest. An empty manifest must not mean "fine".
        val scratch = createScratchVendorTree(withManifest = false)
        try {
            val violations = ArchitectureRules.vendoredTreesMatchTheirManifest(scratch)
            assertTrue(
                violations.any { "no recorded tree hash" in it.detail },
                "an unrecorded vendored tree was accepted; found $violations",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `rule 12 passes when the recorded hash is the real one`() {
        // Proves the hash is computable and stable, not merely that mismatches are caught.
        // A rule that fires on everything is as useless as one that fires on nothing.
        val scratch = createScratchVendorTree(withManifest = true)
        try {
            assertEquals(
                emptyList<ArchitectureRules.Violation>(),
                ArchitectureRules.vendoredTreesMatchTheirManifest(scratch),
                "a correctly recorded tree was reported as modified",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `rule 12 is not vacuous - an absent third_party with a populated manifest fails`() {
        // The failure this rule is most exposed to: `third_party/` does not exist until
        // phase 2a, and an empty tree matches an empty manifest for ever. If somebody
        // records hashes and the trees are not there, that must be loud.
        val scratch = Files.createTempDirectory("arch-vacuous").toFile()
        try {
            File(scratch, ArchitectureRules.TREE_HASH_MANIFEST).apply {
                parentFile.mkdirs()
                writeText("0000000000000000000000000000000000000000000000000000000000000000  pjproject\n")
            }
            val violations = ArchitectureRules.vendoredTreesMatchTheirManifest(scratch)
            assertTrue(
                violations.any { "which is not a directory under" in it.detail },
                "a manifest naming a tree that does not exist was accepted; found $violations",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    // ============================================================ helpers

    /**
     * The fixture's own file paths, with the fixtures prefix stripped.
     *
     * This is what `git ls-files` would report if the same directory were committed
     * anywhere else in the repository — which is exactly the thing rule 11 forbids.
     */
    private fun fixturePathsAsIfReal(): List<String> =
        File(fixtureRoot, "nativeblob").walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(fixtureRoot).path.replace('\\', '/') }
            .toList()

    /** A throwaway `third_party/faketree` plus, optionally, a manifest with its true hash. */
    private fun createScratchVendorTree(withManifest: Boolean): File {
        val root = Files.createTempDirectory("arch-vendored").toFile()
        val tree = File(root, "${ArchitectureRules.VENDORED_ROOT}/faketree")
        tree.mkdirs()
        File(tree, "source.c").writeText("int f(void) { return 1; }\n")

        if (withManifest) {
            File(root, ArchitectureRules.TREE_HASH_MANIFEST).apply {
                parentFile.mkdirs()
                writeText("${ArchitectureRules.hashTree(tree)}  faketree\n")
            }
        }
        return root
    }
}
