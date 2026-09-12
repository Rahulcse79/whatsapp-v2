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

    /**
     * Directories that are build output, tooling state, vendored source, or deliberate
     * violations.
     *
     * **`third_party` is the one that had to be added, and it was found by rule 2 firing.**
     * pjproject ships a Kotlin sample app — `pjsip-apps/src/swig/java/android/app-kotlin` —
     * whose `MainActivity.kt` imports `org.pjsip.pjsua2`, so vendoring the tree put a rule-2
     * violation in the repository on day one. The file is not ours, the import is correct
     * where it is, and the directory cannot be pruned because `configure-android` reads
     * `android/jni/Application.mk` beside it.
     *
     * `docs/module-structure.md` §2.2 already said vendored trees are exempt from the house
     * style. This is where that stopped being prose. The exemption is from **style** only —
     * rule 12 still hashes every one of these files, so provenance is not exempt.
     *
     * **`bin` is the second one that had to be added, and it made every rule fail at once.**
     * Eclipse and the IDE's Kotlin plugin copy sources into `<module>/bin/main`, including
     * this module's own `fixtures/` — the files that violate every rule *on purpose*. The
     * scan then found each deliberate violation at a second path that no exclusion covered,
     * and reported it as real. Gitignoring `bin/` fixed the commit and not the scan: this
     * walks the filesystem, so it has to be told separately. Nothing is lost by skipping it,
     * because everything under it is a copy of a file already scanned at its real path.
     */
    private val EXCLUDED =
        listOf("build", "bin", ".git", ".gradle", ".idea", ".kotlin", "resources", "third_party")

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

    /**
     * Every file git is tracking, as repository-relative paths.
     *
     * Rules 11 and 12 ask what has been **committed**, not what happens to be on disk, and
     * those are different questions: the AAR under `pjsip/libs` was gitignored, so
     * a developer who fetched one to run the app has a `.aar` in their tree that the
     * repository does not carry. A filesystem scan would fail their build for doing
     * exactly what `docs/pjsip-migration.md` P-2 tells them to do.
     *
     * Asking git also means no exclusion list: it already knows about `build/`, `.gradle/`
     * and everything else in `.gitignore`, and it cannot drift from them the way a second
     * copy of that list here would.
     *
     * A failure to run git is a hard error rather than an empty list. An empty list would
     * make every rule below pass vacuously, which is the one outcome an architecture rule
     * must never produce.
     */
    fun trackedFiles(): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z")
            .directory(projectRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ls-files failed in $projectRoot (exit $exit): ${output.take(500)}" }
        return output.split('\u0000').filter { it.isNotBlank() }
    }

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
     * **Rule 2 — no SIP SDK type outside `:data:sip`, and no liblinphone anywhere (DoD 3).**
     *
     * Two clauses, because the migration in ADR-006 made them different questions.
     *
     * **PJSIP outside `:data:sip`** is the original rule and the reason the seam survived
     * a stack swap at all: replacing liblinphone with PJSIP was a rewrite of one file
     * behind four interfaces, not of the application. Letting `org.pjsip` leak upward
     * would spend that.
     *
     * **liblinphone anywhere at all** is the migration's guard rail. It is gone — the
     * dependency, the repository and the adapter were all deleted — and the way a removed
     * stack comes back is one import at a time, in a hurry, because something was easier
     * to reach for. Listing it here is not a reference to liblinphone; it is what stops
     * one being added.
     */
    /** The SIP SDK in use (ADR-006). Confined to `:data:sip`. */
    private const val SIP_SDK = "org.pjsip"

    /** The stack ADR-006 removed. Permitted nowhere. */
    private const val REMOVED_SDK = "org.linphone"

    fun sipSdkStaysInDataSip(files: List<SourceFile>): List<Violation> =
        files.flatMap { file ->
            file.imports.mapNotNull { imported ->
                when {
                    imported.startsWith(REMOVED_SDK) ->
                        Violation(file.relativePath, "imports $imported; liblinphone was removed in ADR-006")

                    imported.startsWith(SIP_SDK) && !file.isUnder("data/sip") ->
                        Violation(file.relativePath, "imports $imported outside :data:sip")

                    else -> null
                }
            }
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
     *
     * **The forbidden imports have no exceptions.** The raw-`Thread` clause has exactly
     * one, and it is [THREAD_EXEMPT] rather than a suppression: the rule is scoped, the
     * way rule 2 scopes `org.pjsip` to `:data:sip`, instead of the finding being hidden.
     * The difference matters — a scope is one line a reviewer reads in the rule, and a
     * baseline is a file that grows on its own.
     */
    fun forbiddenConcurrencyApis(files: List<SourceFile>): List<Violation> = buildList {
        for (file in files) {
            file.imports
                .filter { import -> FORBIDDEN_IMPORTS.any { import.startsWith(it) } }
                .forEach { add(Violation(file.relativePath, "uses $it, which is forbidden (§3)")) }

            if (RAW_THREAD.containsMatchIn(file.code) && file.relativePath !in THREAD_EXEMPT) {
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

    /**
     * The one file allowed to construct a thread, and why.
     *
     * pjsua2 requires every thread that calls into it to be registered first, and
     * `Endpoint::libRegisterThread` allocates a descriptor **freed only when the library
     * is destroyed**. Posting to `Dispatchers.IO` - a pool of up to 64 threads that come
     * and go - would therefore leak one descriptor per thread for the life of the process,
     * and calling in unregistered is undefined behaviour that surfaces as a native crash.
     * One dedicated, named, daemon thread is what the native library's threading contract
     * demands; it is not hand-rolled concurrency, which is what this rule is actually for.
     *
     * Deliberately a path and not a package: the exemption should stop applying the moment
     * this file is renamed or moved, so a stale carve-out cannot quietly widen the rule.
     * `rule 5 exempts one file, and it still exists` is what enforces that.
     */
    val THREAD_EXEMPT = setOf(
        "data/sip/src/main/kotlin/com/whatsappv2/data/sip/registration/stack/RealPjsipCoreGateway.kt",

        // DoD 4's test, and it is exempt for the same reason the gateway is — it is ABOUT
        // the threading contract. It asserts that the executor's ThreadFactory names its
        // thread exactly what the confinement assertion checks for, and a ThreadFactory
        // cannot be tested without constructing a Thread. The two halves live in different
        // files, so without this test a rename in either one leaves a check that can never
        // fire again.
        "data/sip/src/test/kotlin/com/whatsappv2/data/sip/PjsipThreadConfinementTest.kt",
    )

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

    /**
     * **Rule 9 — contact data does not leave the device (Task 49, §7, §11).**
     *
     * The address book is somebody else's personal data, held on loan to draw a name on a
     * ringing screen. §11 forbids collecting it in bulk and §7 forbids sending it anywhere,
     * and neither is the kind of promise a code review keeps on its own — the mistake is
     * one import in one file, months later, in a class that already had a good reason to
     * talk to the network.
     *
     * So: nothing under `:data:contacts`, and no file that touches a [Contact], may also
     * reach for a way off the device. That is coarse on purpose. A file that genuinely
     * needs both is a file worth arguing about.
     */
    fun contactDataStaysOnTheDevice(files: List<SourceFile>): List<Violation> =
        files.filter { file ->
            file.isUnder("data/contacts") ||
                file.imports.any { it.startsWith("com.whatsappv2.domain.contacts") }
        }.flatMap { file ->
            file.imports
                .filter { import -> EGRESS.any { import.startsWith(it) } }
                .map { Violation(file.relativePath, "reaches off-device with $it while holding contact data") }
        }

    /**
     * Ways off the device, as imports.
     *
     * Not exhaustive and cannot be: the point is to catch the ordinary ones, so that
     * getting contact data out takes a deliberate act nobody can call an accident.
     */
    private val EGRESS = listOf(
        "java.net.",
        "javax.net.",
        "okhttp3.",
        "retrofit2.",
        "io.ktor.",
        "android.webkit.",
        "com.google.firebase.",
    )

    /**
     * **Rule 10 — call state is never restored from `SavedStateHandle` (Task 45, §6).**
     *
     * A `SavedStateHandle` survives process death, which is exactly what makes it the
     * wrong place for this: it remembers what was true when the process was killed, and a
     * call is precisely the thing that may not be true any more. Restoring a call screen
     * from it produces a screen for a call that has ended — with a running timer, and
     * buttons that reach a stack which no longer has that dialog.
     *
     * The sources that can answer are the platform's Telecom connections and the engine's
     * own list, both rebuilt from what is still running. So a file that touches a call id
     * or a call snapshot may not also hold a `SavedStateHandle`.
     *
     * Navigation arguments are the ordinary use of one, which is why this looks for the
     * combination rather than for the type: reading an account id out of one is fine, and
     * common.
     */
    fun callStateIsNotRestoredFromSavedState(files: List<SourceFile>): List<Violation> =
        files.filter { it.imports.any { import -> import.endsWith(".SavedStateHandle") } }
            .filter { file -> CALL_STATE.containsMatchIn(file.code) }
            .map {
                Violation(it.relativePath, "restores call state from a SavedStateHandle")
            }

    /**
     * What "call state" looks like in a file.
     *
     * The domain's own call types. A screen that names one of these and holds a
     * `SavedStateHandle` is reconstructing a call from memory, whatever it calls the
     * variable.
     */
    private val CALL_STATE = Regex("""\b(CallSnapshot|CallState|CallUiState|CallDisplay)\b""")

    // ================================================ rules 11 and 12 (the native mandate)

    /**
     * **Rule 11 — no prebuilt native binary in the tree (N-1).**
     *
     * The native mandate's first requirement: *no binary this repository did not compile,
     * from source that lives in this repository, may ship inside the APK.* This is the
     * machine check for it.
     *
     * **Two clauses, because there are two ways to bring a binary in.** Deleting the file
     * and leaving the reference produces a build that resolves nothing and fails
     * obscurely; deleting the reference and leaving the file leaves a 19 MB binary in git
     * history that one line can re-wire. Neither half is sufficient on its own, so both
     * are checked and [nativeBinaryReferences] is the second.
     *
     * **The toolchain needs no exemption here, and adding one would be the first crack in
     * this rule.** N-1 governs what ships inside the APK, not what does the building: a
     * `.so` ships and a compiler does not. The NDK's clang, the `swig` binary and
     * `gradle-wrapper.jar` are not tracked files under this rule's scope, so the rule as
     * written already draws that line correctly.
     *
     * Build **output** is out of scope for free: [trackedFiles] asks git, and every
     * `build/` and `.cxx/` directory is already ignored. So "a `.so` that `:pjsip` built"
     * cannot fire this rule, and a `.so` somebody committed always does.
     */
    fun noPrebuiltNativeBinaries(tracked: List<String>): List<Violation> =
        tracked.filterNot { it.startsWith("$FIXTURES/") }
            .filter { path -> NATIVE_BINARY_SUFFIXES.any { path.endsWith(it) } }
            .map { Violation(it, "is a committed native binary; :pjsip must build it from source (N-1)") }

    /**
     * **Rule 11, second clause — no build file points at a prebuilt binary.**
     *
     * Scoped to build scripts rather than to every file, because a `.so` path in a comment
     * or a document is prose, and prose is what `docs/` is for. A `flatDir`, or an
     * `artifacts.add`/`files(...)` naming an `.aar` or `.so`, is a build instruction.
     *
     * `pjsip/build.gradle.kts:38-41` is the live example this rule exists to delete
     * (N-14), and it will fire on it until phase 3b lands — which is the rule working,
     * not the rule being wrong.
     */
    fun nativeBinaryReferences(files: List<SourceFile>): List<Violation> =
        files.filter { it.relativePath.endsWith(".gradle.kts") || it.relativePath.endsWith(".gradle") }
            .filterNot { it.isUnder(FIXTURES) }
            .flatMap { file ->
                buildList {
                    if (FLAT_DIR.containsMatchIn(file.code)) {
                        add(
                            Violation(
                                file.relativePath,
                                "declares a flatDir repository; a local .aar is not a repository (N-1)",
                            ),
                        )
                    }
                    BINARY_ARTIFACT.findAll(file.code).forEach {
                        add(
                            Violation(
                                file.relativePath,
                                "references the prebuilt binary ${it.groupValues[1]}; :pjsip must build it (N-1)",
                            ),
                        )
                    }
                }
            }

    /**
     * **Rule 12 — vendored source is only changed through `patches/` (N-7).**
     *
     * N-7 allows any pjproject source file to be modified. It does not allow a modified
     * vendored tree with **no record of what changed** — an unrecorded edit is invisible at
     * the next version bump, so somebody bumps pjproject, re-applies the patch series, and
     * a fixed bug comes back with no commit that removed it.
     *
     * **How the comparison is done without the network.** The honest statement of N-7 is
     * "the tree equals upstream-at-the-recorded-commit plus the patches, in order", and
     * resolving *upstream* means a fetch. So the hash of that known-good state is recorded
     * once — at vendoring time, when upstream and the patches were both in hand — in
     * [TREE_HASH_MANIFEST], and every build re-hashes the tree and compares. A pin change
     * updates the manifest in the same commit that changes it, which is the only moment
     * the network is needed.
     *
     * **Vacuously passing is the failure mode here**, because `third_party/` does not exist
     * until phase 2a: an empty tree hashes consistently with an empty manifest for ever.
     * So an absent tree AND an absent manifest is a pass, an absent tree with a manifest
     * that names trees is a violation — which is what
     * `rule 12 is not vacuous - an absent third_party with a populated manifest fails`
     * holds in place.
     */
    fun vendoredTreesMatchTheirManifest(root: File = projectRoot): List<Violation> {
        val manifest = File(root, TREE_HASH_MANIFEST)
        val vendored = File(root, VENDORED_ROOT)

        val recorded: Map<String, String> =
            if (!manifest.isFile) {
                emptyMap()
            } else {
                manifest.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        // A malformed line must be a reported violation, not an exception:
                        // this file is edited by hand at every pin change.
                        val parts = line.split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2) parts[1].trim() to parts[0].trim() else null
                    }
                    .toMap()
            }

        val present: Set<String> = if (!vendored.isDirectory) {
            emptySet()
        } else {
            vendored.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        }

        return buildList {
            (recorded.keys - present).forEach {
                add(Violation(TREE_HASH_MANIFEST, "records $it, which is not a directory under $VENDORED_ROOT/"))
            }
            (present - recorded.keys).forEach {
                add(Violation("$VENDORED_ROOT/$it", "is vendored but has no recorded tree hash (N-7)"))
            }
            (recorded.keys intersect present).forEach { name ->
                val actual = hashTree(File(vendored, name))
                if (actual != recorded[name]) {
                    add(
                        Violation(
                            "$VENDORED_ROOT/$name",
                            "does not match its recorded hash — an edit with no patch " +
                                "in pjsip/patches/ (N-7). recorded " +
                                "${recorded[name]?.take(HASH_PREFIX)}…, " +
                                "found ${actual.take(HASH_PREFIX)}…",
                        ),
                    )
                }
            }
        }
    }

    /**
     * A stable content hash of one vendored tree.
     *
     * Paths are sorted and hashed alongside the bytes, so a **renamed** file changes the
     * hash even when every byte in the tree is unchanged — a rename is an edit, and one
     * that a bytes-only hash would miss entirely.
     *
     * `/` is forced as the separator so the manifest a Linux runner writes matches the one
     * a macOS or Windows checkout computes.
     */
    fun hashTree(root: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') to it }
            .sortedBy { it.first }
            .forEach { (relative, file) ->
                digest.update(relative.toByteArray())
                digest.update(0.toByte())
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Where the vendored trees live — the repository root, not under `pjsip/`. */
    const val VENDORED_ROOT = "third_party"

    /** One `<sha256>  <tree name>` line per vendored tree. `#` starts a comment. */
    const val TREE_HASH_MANIFEST = "pjsip/patches/vendored-tree.sha256"

    /** What "a native binary" means for rule 11. */
    private val NATIVE_BINARY_SUFFIXES = listOf(".aar", ".so")

    private val FLAT_DIR = Regex("""\bflatDir\s*[({]""")

    /** `files("....aar")`, `artifacts.add("default", file("....so"))`, and the like. */
    private val BINARY_ARTIFACT = Regex("""["']([^"']*\.(?:aar|so))["']""")

    /** Enough of a hash to identify it in a failure message without printing 64 chars twice. */
    private const val HASH_PREFIX = 12
}
