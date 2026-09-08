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
 * `docs/pjsip-migration.md` P-2 says how. A checkout without it fails at configuration
 * time with the message below rather than at link time with an `UnsatisfiedLinkError`,
 * which is the difference between a five-second diagnosis and an hour of one.
 */
configurations.maybeCreate("default")

val aar = file("libs/pjsua2.aar")

if (aar.exists()) {
    artifacts.add("default", aar)
} else {
    // Deliberately not a hard failure at configuration time: `./gradlew :domain:test` and
    // every other module that does not touch SIP must still run on a fresh clone. The
    // build that actually needs the binary is the one that fails, and it says why.
    logger.warn(
        "\n:pjsip — libs/pjsua2.aar is missing, so anything depending on it will not compile." +
            "\n  Download it from the 'Build PJSIP for Android' workflow (artifact" +
            " 'pjsua2-aar-<version>') and put it at pjsip/libs/pjsua2.aar." +
            "\n  See docs/pjsip-migration.md P-2.\n",
    )
}
