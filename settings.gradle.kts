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

// Modules are added in Task 4. The list is intentionally empty: Task 2 delivers a
// buildable skeleton only, which is also why this build needs no Android SDK.
