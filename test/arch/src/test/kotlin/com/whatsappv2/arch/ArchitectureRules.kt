package com.whatsappv2.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File

/**
 * The layer rules from §4.1, expressed once and applied twice.
 *
 * Each rule is a pure function from a [KoScope] to the violations it finds, so the same
 * code can be pointed at the real project (must find none) and at a deliberately
 * violating fixture (must find some). A rule that never fires is worse than no rule,
 * because it reads like protection while providing none — which is exactly what the
 * done-when for Task 12 is guarding against.
 *
 * Konsist parses Kotlin rather than matching text. That matters: the shell greps this
 * repository already uses produced two false positives — the Gradle cache *directory*
 * named `.gradle`, and a KDoc line that merely mentioned `org.linphone`.
 */
object ArchitectureRules {

    /** One violation, phrased so a failure message is actionable on its own. */
    data class Violation(val file: String, val detail: String) {
        override fun toString(): String = "$file: $detail"
    }

    private val projectRoot: File
        get() = File(
            requireNotNull(System.getProperty("whatsappv2.rootDir")) {
                "whatsappv2.rootDir is not set; see test/arch/build.gradle.kts"
            },
        )

    /** Every Kotlin source file in the repository, excluding this rules module itself. */
    fun projectScope(): KoScope = Konsist.scopeFromDirectory(projectRoot.absolutePath)

    fun scopeFrom(relativePath: String): KoScope =
        Konsist.scopeFromDirectory(File(projectRoot, relativePath).absolutePath)

    // ================================================================ rules

    /**
     * **Rule 1 — `:domain` has no Android dependency (DoD 2).**
     *
     * The Gradle side of this is already enforced by the JVM-library plugin and by a CI
     * step that adds an Android dependency and requires the build to fail. This catches
     * the subtler case: a source file that imports an Android type which happens to be
     * on the classpath transitively.
     */
    fun domainHasNoAndroidImports(scope: KoScope): List<Violation> =
        scope.files
            .filter { it.path.isUnder("domain/src") }
            .flatMap { file ->
                file.imports
                    .filter { it.name.startsWith("android.") || it.name.startsWith("androidx.") }
                    .map { Violation(file.path.relativeName(), "imports ${it.name} in :domain") }
            }

    /**
     * **Rule 2 — no SIP SDK type outside `:data:sip` (DoD 3).**
     *
     * This is what makes ADR-001 reversible: swapping liblinphone for PJSIP must be a
     * rewrite of one module, not of the application.
     */
    fun sipSdkStaysInDataSip(scope: KoScope): List<Violation> =
        scope.files
            .filterNot { it.path.isUnder("data/sip/src") }
            .flatMap { file ->
                file.imports
                    .filter { it.name.startsWith("org.linphone") || it.name.startsWith("org.pjsip") }
                    .map { Violation(file.path.relativeName(), "imports ${it.name} outside :data:sip") }
            }

    /**
     * **Rule 3 — `:feature:*` depends on `:domain`, never on `:data:*`.**
     *
     * A feature that reaches into a data module bypasses the repository interface, and
     * with it every test seam the domain layer exists to provide.
     */
    fun featuresDoNotDependOnData(scope: KoScope): List<Violation> =
        scope.files
            .filter { it.path.isUnder("feature/") }
            .flatMap { file ->
                file.imports
                    .filter { it.name.startsWith("com.whatsappv2.data.") }
                    .map { Violation(file.path.relativeName(), "imports ${it.name}; features may only use :domain") }
            }

    /**
     * **Rule 4 — repository interfaces in `:domain`, implementations in `:data:*`.**
     *
     * Declared in the layer that owns the contract, implemented in the layer that owns
     * the storage. An interface that drifts into `:data` is one no other module can
     * depend on without depending on the storage too.
     */
    fun repositoriesAreDeclaredInDomain(scope: KoScope): List<Violation> = buildList {
        for (file in scope.files) {
            val path = file.path
            val name = path.relativeName()

            val declaresRepositoryInterface = INTERFACE_REPOSITORY.containsMatchIn(file.text)
            val declaresRepositoryClass = CLASS_REPOSITORY.containsMatchIn(file.text)

            if (declaresRepositoryInterface && !path.isUnder("domain/src")) {
                add(Violation(name, "declares a repository interface outside :domain"))
            }
            if (declaresRepositoryClass && !path.isUnder("data/")) {
                add(Violation(name, "declares a repository implementation outside :data:*"))
            }
        }
    }

    /**
     * **Rule 5 — no LiveData, RxJava, AsyncTask or raw Thread (§3).**
     *
     * Not stylistic. Mixing LiveData and Flow means two lifecycle models in one screen;
     * `AsyncTask` has been removed from the platform; and a raw `Thread` in an app that
     * holds a long-lived registration is how leaks and wake-lock bugs start.
     */
    fun forbiddenConcurrencyApis(scope: KoScope): List<Violation> = buildList {
        for (file in scope.files) {
            val name = file.path.relativeName()
            file.imports
                .filter { import -> FORBIDDEN_IMPORTS.any { import.name.startsWith(it) } }
                .forEach { add(Violation(name, "uses ${it.name}, which is forbidden (§3)")) }

            if (RAW_THREAD.containsMatchIn(file.text)) {
                add(Violation(name, "constructs a raw Thread; use coroutines (§3)"))
            }
        }
    }

    /**
     * **Rule 6 — ViewModels expose immutable state (§4.2).**
     *
     * A ViewModel that exposes `MutableStateFlow` lets any collector write to it, so the
     * single source of truth stops being single. The mutable holder must stay private
     * and be published through `asStateFlow()`.
     */
    fun viewModelsExposeImmutableState(scope: KoScope): List<Violation> = buildList {
        for (file in scope.files.filter { it.text.contains(": ViewModel(") }) {
            val name = file.path.relativeName()
            PUBLIC_MUTABLE_STATE.findAll(file.text).forEach {
                add(Violation(name, "exposes ${it.value.trim()}; keep it private and publish asStateFlow()"))
            }
            PUBLIC_VAR.findAll(file.text).forEach {
                add(Violation(name, "exposes a public var (${it.value.trim()}); state must be immutable"))
            }
        }
    }

    // ================================================================ helpers

    private val FORBIDDEN_IMPORTS = listOf(
        "androidx.lifecycle.LiveData",
        "androidx.lifecycle.MutableLiveData",
        "androidx.lifecycle.liveData",
        "io.reactivex",
        "rx.",
        "android.os.AsyncTask",
    )

    private val INTERFACE_REPOSITORY = Regex("""^\s*(?:public\s+)?interface\s+\w*Repository\b""", RegexOption.MULTILINE)
    private val CLASS_REPOSITORY = Regex(
        """^\s*(?:\w+\s+)*class\s+\w+Repository(?:Impl|Implementation)\b""",
        RegexOption.MULTILINE,
    )
    private val RAW_THREAD = Regex("""\bThread\s*\(""")
    private val PUBLIC_MUTABLE_STATE = Regex(
        """^\s*(?!private|internal|protected)(?:val|var)\s+\w+\s*:\s*Mutable(?:State|Shared)Flow<""",
        RegexOption.MULTILINE,
    )
    private val PUBLIC_VAR =
        Regex("""^\s{4}(?!private|internal|protected)var\s+\w+\s*[:=]""", RegexOption.MULTILINE)

    private fun String.isUnder(segment: String): Boolean = replace('\\', '/').contains("/$segment")

    private fun String.relativeName(): String {
        val root = projectRoot.absolutePath.replace('\\', '/')
        return replace('\\', '/').removePrefix(root).removePrefix("/")
    }
}
