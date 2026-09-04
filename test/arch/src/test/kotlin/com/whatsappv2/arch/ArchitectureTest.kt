package com.whatsappv2.arch

import com.lemonappdev.konsist.api.container.KoScope
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The layer rules from §4.1, applied to the real project. Every one must find nothing.
 *
 * These run under `./gradlew check`, so a violation fails the build locally and in CI —
 * an architecture that is only documented is one that will be violated in week three.
 */
class ArchitectureTest {

    private val scope: KoScope = ArchitectureRules.projectScope()

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
        assertNoViolations("Rule 1", ArchitectureRules.domainHasNoAndroidImports(scope))
    }

    @Test
    fun `rule 2 - the SIP SDK stays inside data sip`() {
        assertNoViolations("Rule 2", ArchitectureRules.sipSdkStaysInDataSip(scope))
    }

    @Test
    fun `rule 3 - features do not depend on data modules`() {
        assertNoViolations("Rule 3", ArchitectureRules.featuresDoNotDependOnData(scope))
    }

    @Test
    fun `rule 4 - repository interfaces live in domain and implementations in data`() {
        assertNoViolations("Rule 4", ArchitectureRules.repositoriesAreDeclaredInDomain(scope))
    }

    @Test
    fun `rule 5 - no LiveData, RxJava, AsyncTask or raw Thread`() {
        assertNoViolations("Rule 5", ArchitectureRules.forbiddenConcurrencyApis(scope))
    }

    @Test
    fun `rule 6 - ViewModels expose immutable state`() {
        assertNoViolations("Rule 6", ArchitectureRules.viewModelsExposeImmutableState(scope))
    }

    @Test
    fun `the scope is not empty, so a passing rule means something`() {
        // Without this, a misconfigured root path would make every rule above pass
        // vacuously - the failure mode that makes architecture tests worthless.
        assertTrue(scope.files.count() > MINIMUM_EXPECTED_FILES, "only ${scope.files.count()} files in scope")
    }

    private companion object {
        const val MINIMUM_EXPECTED_FILES = 40
    }
}
