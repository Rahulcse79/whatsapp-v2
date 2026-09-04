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
    }
}

rootProject.name = "whatsapp-v2"

include(":app")

// Pure Kotlin/JVM. Applies no Android plugin, by design — see §4.1 and DoD 2.
include(":domain")

include(":core:common")
include(":core:designsystem")

include(":data:account")
include(":data:sip")
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
