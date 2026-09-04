package com.whatsappv2.arch

import java.io.File

/**
 * The layer rules from §4.1, expressed once and applied twice.
 *
 * Each rule is a pure function from a set of files to the violations it finds, so the
 * same code runs against the real project (must find none) and against deliberately
 * violating fixtures (must find some). A rule that never fires is worse than no rule:
 * it reads like protection while providing none, and stops anyone from looking.
 *
 * ## Why not Konsist
 *
 * Konsist was tried first and reverted. Scoping it correctly meant excluding `build/`
 * output and this module's own violation fixtures — otherwise the fixtures fail every
 * rule — and that is scope-filtering API this project cannot verify without another
 * round trip. The rules needed here are "does this file import X" and "does this file
 * contain construct Y", both of which are answered exactly by reading the file.
 *
 * The false positives that motivated a parser are avoided directly instead:
 *  - imports are matched only at **column 0**, which is where every real Kotlin import
 *    is, so a KDoc line mentioning `org.linphone` cannot fire (it did, under grep);
 *  - comments are stripped before any text rule runs, so `Thread(` in prose is inert.
 */
object ArchitectureRules {

    /** One violation, phrased so a failure message is actionable on its own. */
    data class Violation(val file: String, val detail: String) {
        override fun toString(): String = "$file: $detail"
    }

    /** A Kotlin source file, with its path relative to the repository root. */
    data class SourceFile(val relativePath: String, val text: String) {

        /** Only column-0 `import` lines. A mention in a comment is not an import. */
        val imports: List<String> by lazy {
            IMPORT.findAll(text).map { it.groupValues[1] }.toList()
        }

        /** True when this file is Compose UI, and therefore subject to the style rules. */
        val usesCompose: Boolean by lazy {
            imports.any { it.startsWith("androidx.compose") }
        }

        /** [text] with comments removed, so text rules cannot fire on prose. */
        val code: String by lazy {
            text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
        }

        /**
         * True when [relativePath] passes through any of [segments] as a path segment.
         *
         * Matches anywhere in the path, not just at the start, and that is deliberate:
         * the violation fixtures must live OUTSIDE the real module directories so they
         * are never compiled, yet must LOOK like they are inside them so path-based
         * rules fire on them. A prefix match satisfies only the first requirement, and
         * rules 1 and 3 silently failed to fire until this was fixed.
         */
        fun isUnder(vararg segments: String): Boolean = segments.any {
            relativePath == it || relativePath.startsWith("$it/") || "/$it/" in relativePath
        }
    }

    private val IMPORT = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    /** Directories that are build output, tooling state, or deliberate violations. */
    private val EXCLUDED = listOf("build", ".git", ".gradle", ".idea", ".kotlin", "resources")

    /**
     * The repository root.
     *
     * Found by walking up from the test's working directory until `settings.gradle.kts`
     * appears, rather than read from a system property: a property that fails to reach
     * the test JVM produces a confusing `IllegalArgumentException` at class-init time,
     * which is exactly how this rule set failed on its first run.
     */
    val projectRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find the repository root from ${System.getProperty("user.dir")}")
    }

    /** Every Kotlin source file in the project, excluding build output and fixtures. */
    fun projectFiles(): List<SourceFile> = filesUnder(projectRoot)

    /** The deliberately violating fixtures, which are never compiled. */
    fun fixtureFiles(): List<SourceFile> =
        filesUnder(File(projectRoot, FIXTURES), excludeResources = false)

    private fun filesUnder(root: File, excludeResources: Boolean = true): List<SourceFile> {
        require(root.isDirectory) { "Not a directory: $root" }
        val excluded = if (excludeResources) EXCLUDED else EXCLUDED - "resources"

        return root.walkTopDown()
            .onEnter { it.name !in excluded }
            .filter { it.isFile && it.extension == "kt" }
            .map { SourceFile(it.relativeTo(projectRoot).path.replace('\\', '/'), it.readText()) }
            .toList()
    }

    // ================================================================ rules

    /**
     * **Rule 1 — `:domain` has no Android dependency (DoD 2).**
     *
     * Gradle already enforces this structurally: `:domain` uses the JVM library plugin,
     * and a CI step adds an Android dependency and requires the build to fail. This
     * catches the subtler case — a source file importing an Android type that is on the
     * classpath transitively.
     */
    fun domainHasNoAndroidImports(files: List<SourceFile>): List<Violation> =
        files.filter { it.isUnder("domain") }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith("android.") || it.startsWith("androidx.") }
                    .map { Violation(file.relativePath, "imports $it in :domain") }
            }

    /**
     * **Rule 2 — no SIP SDK type outside `:data:sip` (DoD 3).**
     *
     * This is what keeps ADR-001 reversible: swapping liblinphone for PJSIP must be a
     * rewrite of one module, not of the application.
     */
    fun sipSdkStaysInDataSip(files: List<SourceFile>): List<Violation> =
        files.filterNot { it.isUnder("data/sip") }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith("org.linphone") || it.startsWith("org.pjsip") }
                    .map { Violation(file.relativePath, "imports $it outside :data:sip") }
            }

    /**
     * **Rule 3 — `:feature:*` depends on `:domain`, never on `:data:*`.**
     *
     * A feature that reaches into a data module bypasses the repository interface, and
     * with it every test seam the domain layer exists to provide.
     */
    fun featuresDoNotDependOnData(files: List<SourceFile>): List<Violation> =
        files.filter { it.isUnder("feature") }
            .flatMap { file ->
                file.imports
                    .filter { it.startsWith("com.whatsappv2.data.") }
                    .map { Violation(file.relativePath, "imports $it; features may only use :domain") }
            }

    /**
     * **Rule 4 — repository interfaces in `:domain`, implementations in `:data:*`.**
     *
     * Declared in the layer that owns the contract, implemented in the layer that owns
     * the storage. An interface that drifts into `:data` is one no other module can
     * depend on without depending on the storage too.
     */
    fun repositoriesAreDeclaredInDomain(files: List<SourceFile>): List<Violation> = buildList {
        for (file in files) {
            if (REPOSITORY_INTERFACE.containsMatchIn(file.code) && !file.isUnder("domain")) {
                add(Violation(file.relativePath, "declares a repository interface outside :domain"))
            }
            if (REPOSITORY_IMPL.containsMatchIn(file.code) && !file.isUnder("data")) {
                add(Violation(file.relativePath, "declares a repository implementation outside :data"))
            }
        }
    }

    /**
     * **Rule 5 — no LiveData, RxJava, AsyncTask or raw Thread (§3).**
     *
     * Not stylistic. Mixing LiveData and Flow puts two lifecycle models in one screen;
     * `AsyncTask` has been removed from the platform; and a raw `Thread` in an app that
     * holds a long-lived registration is how leaks and wake-lock bugs start.
     */
    fun forbiddenConcurrencyApis(files: List<SourceFile>): List<Violation> = buildList {
        for (file in files) {
            file.imports
                .filter { import -> FORBIDDEN_IMPORTS.any { import.startsWith(it) } }
                .forEach { add(Violation(file.relativePath, "uses $it, which is forbidden (§3)")) }

            if (RAW_THREAD.containsMatchIn(file.code)) {
                add(Violation(file.relativePath, "constructs a raw Thread; use coroutines (§3)"))
            }
        }
    }

    /**
     * **Rule 6 — ViewModels expose immutable state (§4.2).**
     *
     * A ViewModel exposing `MutableStateFlow` lets any collector write to it, so the
     * single source of truth stops being single. The mutable holder stays private and is
     * published through `asStateFlow()`.
     */
    fun viewModelsExposeImmutableState(files: List<SourceFile>): List<Violation> = buildList {
        for (file in files.filter { VIEW_MODEL.containsMatchIn(it.code) }) {
            PUBLIC_MUTABLE_FLOW.findAll(file.code).forEach {
                add(
                    Violation(
                        file.relativePath,
                        "exposes ${it.value.trim()}; keep it private and publish asStateFlow()",
                    ),
                )
            }
            PUBLIC_VAR.findAll(file.code).forEach {
                add(Violation(file.relativePath, "exposes a public var (${it.value.trim()}); state must be immutable"))
            }
        }
    }

    /**
     * **Rule 7 — every design-system component is previewed in light and dark (Task 14).**
     *
     * A component with no preview is one nobody has seen in dark mode, and dark-mode
     * contrast bugs are invisible until someone with dark mode on reports them. The
     * `@ThemePreviews` annotation renders both at once, so a single annotation cannot be
     * half-applied the way two separate `@Preview`s can.
     */
    fun designSystemComponentsArePreviewed(files: List<SourceFile>): List<Violation> =
        files.filter { it.isUnder("designsystem") && "/component/" in it.relativePath }
            .filter { PUBLIC_COMPOSABLE.containsMatchIn(it.code) }
            .filterNot { PREVIEW_ANNOTATION.containsMatchIn(it.code) }
            .map { Violation(it.relativePath, "declares a public @Composable with no @ThemePreviews") }

    /**
     * **Rule 8 — no hardcoded colour, dimension or text style outside the design
     * system (Task 14).**
     *
     * Strict on purpose. Once one screen uses `12.dp` and its neighbour uses `14.dp`,
     * nobody can tell whether the difference was intended, and every later change
     * becomes a judgement call. `AppTheme.spacing` and `MaterialTheme.colorScheme` cost
     * nothing to use and keep the app coherent.
     *
     * Applied to UI source only — `:domain` has no styling to hardcode, and its
     * constants are not dimensions.
     */
    fun stylingStaysInTheDesignSystem(files: List<SourceFile>): List<Violation> =
        files.filterNot { it.isUnder("designsystem") }
            .filter { it.usesCompose }
            .flatMap { file ->
                COLOR_LITERAL.findAll(file.code).map {
                    Violation(file.relativePath, "hardcodes a colour (${it.value}); use MaterialTheme.colorScheme")
                } + TEXT_STYLE.findAll(file.code).map {
                    Violation(file.relativePath, "constructs a TextStyle; use MaterialTheme.typography")
                } + DIMENSION_LITERAL.findAll(file.code).map {
                    Violation(
                        file.relativePath,
                        "hardcodes a dimension (${it.value.trim()}); use AppTheme.spacing",
                    )
                }
            }

    // ================================================================ patterns

    const val FIXTURES = "test/arch/src/test/resources/violations"

    private val FORBIDDEN_IMPORTS = listOf(
        "androidx.lifecycle.LiveData",
        "androidx.lifecycle.MutableLiveData",
        "androidx.lifecycle.liveData",
        "io.reactivex",
        "rx.",
        "android.os.AsyncTask",
    )

    private val REPOSITORY_INTERFACE =
        Regex("""^\s*(?:public\s+)?interface\s+\w*Repository\b""", RegexOption.MULTILINE)
    private val REPOSITORY_IMPL =
        Regex("""^\s*(?:\w+\s+)*class\s+\w+Repository(?:Impl|Implementation)\b""", RegexOption.MULTILINE)
    private val RAW_THREAD = Regex("""\bThread\s*\(""")
    private val VIEW_MODEL = Regex(""":\s*ViewModel\s*\(""")
    private val PUBLIC_MUTABLE_FLOW = Regex(
        """^\s*(?!private|internal|protected)(?:val|var)\s+\w+\s*:\s*Mutable(?:State|Shared)Flow<""",
        RegexOption.MULTILINE,
    )
    private val PUBLIC_VAR = Regex("""^\s{4}(?!private|internal|protected)var\s+\w+\s*[:=]""", RegexOption.MULTILINE)

    private val PUBLIC_COMPOSABLE = Regex("""@Composable\s*\n\s*fun\s+\w+""")
    private val PREVIEW_ANNOTATION = Regex("""@Theme(?:AndFont)?Previews""")
    private val COLOR_LITERAL = Regex("""Color\(\s*0x[0-9A-Fa-f]{6,8}""")
    private val TEXT_STYLE = Regex("""\bTextStyle\s*\(""")
    private val DIMENSION_LITERAL = Regex("""(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b""")
}
