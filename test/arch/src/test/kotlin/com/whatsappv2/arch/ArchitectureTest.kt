package com.whatsappv2.arch

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The layer rules from §4.1, applied to the real project. Every one must find nothing.
 *
 * These run under `./gradlew check`, so a violation fails the build locally and in CI —
 * an architecture that is only documented is one that will be violated in week three.
 */
class ArchitectureTest {

    private val files = ArchitectureRules.projectFiles()

    private fun assertNoViolations(rule: String, violations: List<ArchitectureRules.Violation>) {
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("$rule violated ${violations.size} time(s):")
                violations.forEach { appendLine("  - $it") }
            },
        )
    }

    @Test
    fun `rule 1 - domain has no Android imports`() {
        assertNoViolations("Rule 1", ArchitectureRules.domainHasNoAndroidImports(files))
    }

    @Test
    fun `rule 2 - the SIP SDK stays inside data sip`() {
        assertNoViolations("Rule 2", ArchitectureRules.sipSdkStaysInDataSip(files))
    }

    @Test
    fun `rule 3 - features do not depend on data modules`() {
        assertNoViolations("Rule 3", ArchitectureRules.featuresDoNotDependOnData(files))
    }

    @Test
    fun `rule 4 - repository interfaces live in domain and implementations in data`() {
        assertNoViolations("Rule 4", ArchitectureRules.repositoriesAreDeclaredInDomain(files))
    }

    @Test
    fun `rule 5 - no LiveData, RxJava, AsyncTask or raw Thread`() {
        assertNoViolations("Rule 5", ArchitectureRules.forbiddenConcurrencyApis(files))
    }

    /**
     * Rule 5's raw-`Thread` clause has one exemption, and an exemption naming a file that
     * no longer exists is how a scope quietly becomes a licence. Renaming or deleting
     * `RealPjsipCoreGateway` should fail here and force the carve-out to be re-justified,
     * rather than leaving a dead path that matches nothing and forbids nothing.
     */
    @Test
    fun `rule 5 exempts one file, and it still exists`() {
        val present = files.map { it.relativePath }.toSet()
        ArchitectureRules.THREAD_EXEMPT.forEach { exempt ->
            assertTrue(
                exempt in present,
                "$exempt is exempt from rule 5 but is not in the source tree any more",
            )
        }
    }

    @Test
    fun `rule 6 - ViewModels expose immutable state`() {
        assertNoViolations("Rule 6", ArchitectureRules.viewModelsExposeImmutableState(files))
    }

    @Test
    fun `rule 7 - every design-system component is previewed in light and dark`() {
        assertNoViolations("Rule 7", ArchitectureRules.designSystemComponentsArePreviewed(files))
    }

    @Test
    fun `rule 8 - no hardcoded colour, dimension or text style outside the design system`() {
        assertNoViolations("Rule 8", ArchitectureRules.stylingStaysInTheDesignSystem(files))
    }

    @Test
    fun `the scope is not empty, so a passing rule means something`() {
        // Without this, a misconfigured root would make every rule above pass vacuously
        // - the failure mode that makes architecture tests worthless.
        assertTrue(files.size > MINIMUM_EXPECTED_FILES, "only ${files.size} files in scope")
    }

    @Test
    fun `the violation fixtures are excluded from the project scope`() {
        // They break every rule on purpose. If they were in scope, the suite above
        // could never pass, and "fixing" it would mean weakening the rules.
        assertTrue(
            files.none { it.relativePath.startsWith(ArchitectureRules.FIXTURES) },
            "fixture files leaked into the project scope",
        )
    }

    @Test
    fun `build output is excluded from the scope`() {
        assertTrue(files.none { "/build/" in it.relativePath }, "build output leaked into the scope")
    }

    private companion object {
        const val MINIMUM_EXPECTED_FILES = 40
    }

    @Test
    fun `rule 9 - contact data stays on the device`() {
        assertNoViolations("Rule 9", ArchitectureRules.contactDataStaysOnTheDevice(files))
    }

    @Test
    fun `rule 10 - call state is not restored from SavedStateHandle`() {
        assertNoViolations("Rule 10", ArchitectureRules.callStateIsNotRestoredFromSavedState(files))
    }
}
