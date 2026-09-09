pluginManagement {
    // Convention plugins (Task 3). An included build, so they are compiled and
    // applied without publishing anything to a repository.
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // No module may declare its own repositories (§4.1 — one dependency source of truth).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()

        // No third repository. The previous SIP stack needed one of its own because it
        // shipped from Belledonne rather than Maven Central; PJSIP ships from nowhere at
        // all and is built by `.github/workflows/build-pjsip.yml` into `:pjsip`. The
        // supply chain is narrower for it (ADR-006).
    }
}

rootProject.name = "whatsapp-v2"

include(":app")

// Pure Kotlin/JVM. Applies no Android plugin, by design — see §4.1 and DoD 2.
include(":domain")

include(":core:common")
include(":core:designsystem")

include(":data:account")
include(":data:settings")
include(":data:sip")

// The PJSIP binaries, wrapped (ADR-006). Not a source module — see pjsip/build.gradle.kts.
include(":pjsip")

// The Java half of that AAR, as source, so the tree still compiles when the binary is
// absent. Used only then; the AAR wins whenever it exists. See pjsip/api/build.gradle.kts.
include(":pjsip:api")
include(":data:calllog")
include(":data:contacts")

include(":feature:dialer")
include(":feature:calls")
include(":feature:accounts")
include(":feature:history")
include(":feature:settings")

// Architecture rules (Task 12). A module of its own so the rules are not buried in
// :app, and so they run for every module rather than only where they happen to live.
include(":test:arch")
