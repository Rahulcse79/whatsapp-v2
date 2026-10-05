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

        // No third repository, and under ADR-007 PJSIP needs none at all: its source is
        // in this repository under third_party/ and `:pjsip` compiles it. The supply chain
        // for the component that holds SIP credentials and carries media is a diff here,
        // which is the strongest security claim the project has (docs/system-design.md §1.2).
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

// The native build itself (ADR-007). Compiles the vendored trees under third_party/ with
// the NDK, through one CMake entry point, and produces libpjsua2.so per ABI. There is no
// prebuilt AAR and no fallback: a build that produces no .so fails (N-14).
include(":pjsip")

// Stage 1 — the pjsua2 Java API, GENERATED from third_party/pjproject on every build by
// the same build that produces the .so, so the JNI symbol names cannot drift from the
// binary (N-13). Nothing under pjsip/ is a committed .java. See pjsip/api/build.gradle.kts.
include(":pjsip:api")
include(":data:calllog")
include(":data:contacts")

// The voice profile (ADR-013): enrolment, the speaker embedding, and the profile store.
// Apart from :data:sip so that ONNX Runtime and the 24 MB model are a dependency of the
// feature that needs them rather than of the SIP stack.
include(":data:voice")

// The chat SDK (com.chatserver.sdk), vendored as source rather than consumed as an AAR:
// architecture rule 11 forbids a committed .aar, and source is what lets this build prove
// what it ships. Its Java is upstream's and is not modified — only its build file was
// converted to the Kotlin DSL and the version catalog, which CI requires. Only :data:chat
// may name a type from it; architecture rule 13 is what keeps that true.
include(":chatsdk")
include(":data:chat")

include(":feature:dialer")
include(":feature:calls")
include(":feature:accounts")
include(":feature:history")
include(":feature:settings")
include(":feature:recordings")
include(":feature:chat")

// Architecture rules (Task 12). A module of its own so the rules are not buried in
// :app, and so they run for every module rather than only where they happen to live.
include(":test:arch")
