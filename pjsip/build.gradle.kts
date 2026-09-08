/**
 * The PJSIP binaries, wrapped as a module (ADR-006).
 *
 * ## Why a module rather than a dependency
 *
 * PJSIP publishes no Android artifact. `pjsua2.aar` is built by
 * `.github/workflows/build-pjsip.yml` from pjproject source — OpenSSL and Opus
 * cross-compiled, SWIG bindings generated, three ABIs packaged — and lands here as a
 * file rather than arriving from a repository.
 *
 * `settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so a module-level
 * `flatDir` is not available; it would also be the wrong shape, because a local `.aar` is
 * not a repository and pretending otherwise makes dependency resolution lie about where
 * the binary came from. Publishing it through this module's `default` configuration is
 * the honest form: `:data:sip` takes a project dependency, and no repository is involved.
 *
 * ## The file is not committed
 *
 * `libs/pjsua2.aar` is a ~30 MB native binary that changes with every pjproject or NDK
 * bump. It is `.gitignore`d and fetched from the workflow's artifact instead —
 * `docs/pjsip-migration.md` P-2 says how.
 *
 * ## Without it, the tree still compiles — and still cannot run
 *
 * The Java classes in that AAR are SWIG output, and SWIG needs none of the native
 * toolchain to produce them, so they are checked in as `:pjsip:api` and `:data:sip`
 * falls back to them. That is what keeps the CI gate meaningful while P-1 is still
 * being brought up: the adapter is compiled and checked against the real PJSIP API
 * rather than not compiled at all.
 *
 * What the fallback cannot supply is `libpjsua2.so`. An APK built without this AAR
 * installs and then raises an `UnsatisfiedLinkError` on the first SIP call, so the
 * warning below says that in as many words — a five-second diagnosis instead of an
 * hour of one.
 */
configurations.maybeCreate("default")

val aar = file("libs/pjsua2.aar")

if (aar.exists()) {
    artifacts.add("default", aar)
} else {
    // Deliberately not a hard failure at configuration time: `./gradlew :domain:test` and
    // every other module that does not touch SIP must still run on a fresh clone, and
    // `./gradlew build` has to stay green so the rest of the gate is worth something.
    logger.warn(
        "\n:pjsip — libs/pjsua2.aar is missing." +
            "\n  :data:sip is compiling against :pjsip:api instead, which is the same Java" +
            " API without the native library." +
            "\n  The build will succeed and the app will NOT run: the first SIP call raises" +
            " UnsatisfiedLinkError on libpjsua2.so." +
            "\n  For an APK that runs, take 'pjsua2-aar-<version>' from the 'Build PJSIP for" +
            " Android' workflow and put it at pjsip/libs/pjsua2.aar." +
            "\n  See docs/pjsip-migration.md P-1 and P-2.\n",
    )
}
