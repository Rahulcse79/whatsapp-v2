package com.whatsappv2.arch

import com.lemonappdev.konsist.api.container.KoScope
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves each rule actually fires.
 *
 * Task 12's done-when asks for a deliberately-violating fixture per rule, and the
 * reason is worth stating: a rule with a typo in its predicate passes on every real
 * project forever. It reads like protection while providing none — the worst possible
 * outcome, because it also stops anyone looking.
 *
 * The fixtures live under `src/test/resources`, so they are never compiled and are free
 * to break every rule. The rules are pointed at them explicitly.
 *
 * These tests also validate that the Konsist API is being used correctly: if a query
 * were wrong, it would find nothing here and the test would fail immediately, rather
 * than silently passing against the real project.
 */
class RulesActuallyFireTest {

    private val fixtures: KoScope =
        ArchitectureRules.scopeFrom("test/arch/src/test/resources/violations")

    private fun assertFires(rule: String, violations: List<ArchitectureRules.Violation>) {
        assertTrue(
            violations.isNotEmpty(),
            "$rule found no violations in the fixtures - the rule does not work",
        )
    }

    @Test
    fun `the fixtures are actually being read`() {
        // If the path were wrong every test below would fail, but this says why.
        assertTrue(fixtures.files.count() >= EXPECTED_FIXTURES, "found ${fixtures.files.count()} fixture files")
    }

    @Test
    fun `rule 1 fires on an Android import inside domain`() {
        val violations = ArchitectureRules.domainHasNoAndroidImports(fixtures)
        assertFires("Rule 1", violations)
        assertTrue(violations.any { "android.content.Context" in it.detail })
        assertTrue(violations.any { "androidx.lifecycle.ViewModel" in it.detail })
    }

    @Test
    fun `rule 2 fires on both SIP SDKs outside data sip`() {
        val violations = ArchitectureRules.sipSdkStaysInDataSip(fixtures)
        assertFires("Rule 2", violations)
        assertTrue(violations.any { "org.linphone" in it.detail }, "liblinphone not detected")
        assertTrue(violations.any { "org.pjsip" in it.detail }, "PJSIP not detected")
    }

    @Test
    fun `rule 3 fires when a feature imports a data module`() {
        val violations = ArchitectureRules.featuresDoNotDependOnData(fixtures)
        assertFires("Rule 3", violations)
        assertTrue(violations.any { "com.whatsappv2.data.account" in it.detail })
    }

    @Test
    fun `rule 4 fires on a misplaced repository interface and implementation`() {
        val violations = ArchitectureRules.repositoriesAreDeclaredInDomain(fixtures)
        assertFires("Rule 4", violations)
        assertTrue(violations.any { "interface" in it.detail }, "misplaced interface not detected")
        assertTrue(violations.any { "implementation" in it.detail }, "misplaced implementation not detected")
    }

    @Test
    fun `rule 5 fires on LiveData, RxJava, AsyncTask and a raw Thread`() {
        val violations = ArchitectureRules.forbiddenConcurrencyApis(fixtures)
        assertFires("Rule 5", violations)
        assertTrue(violations.any { "MutableLiveData" in it.detail }, "LiveData not detected")
        assertTrue(violations.any { "io.reactivex" in it.detail }, "RxJava not detected")
        assertTrue(violations.any { "AsyncTask" in it.detail }, "AsyncTask not detected")
        assertTrue(violations.any { "raw Thread" in it.detail }, "raw Thread not detected")
    }

    @Test
    fun `rule 6 fires on an exposed MutableStateFlow and a public var`() {
        val violations = ArchitectureRules.viewModelsExposeImmutableState(fixtures)
        assertFires("Rule 6", violations)
        assertTrue(violations.any { "MutableStateFlow" in it.detail }, "exposed MutableStateFlow not detected")
        assertTrue(violations.any { "public var" in it.detail }, "public var not detected")
    }

    private companion object {
        const val EXPECTED_FIXTURES = 6
    }
}
