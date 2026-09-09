# Module structure

**Master prompt §7.** The module graph, package conventions, visibility rules, and the
tests that enforce them. Start from what exists; every change is justified.

A convention documented in prose is a convention that decays. Every rule here is
machine-checked, and every rule is proven to **fire** against a deliberately violating
fixture — because a rule that never fires reads like protection while providing none.

---

## 1. The graph today

From `settings.gradle.kts`:

```
:app                     composition root, Telecom, notifications, navigation
:domain                  pure Kotlin/JVM — entities, contracts, use cases, policies
:core:common             cross-cutting, Android-aware
:core:designsystem       Compose theme + previewed components
:data:account            Room + Keystore credential storage
:data:settings           DataStore
:data:sip                the PJSIP adapter — the ONLY module that may import org.pjsip
:data:calllog            Room + Paging
:data:contacts           ContactsContract
:feature:{dialer,calls,accounts,history,settings}
:pjsip                   publishes libs/pjsua2.aar through its `default` configuration
:pjsip:api               SWIG-generated Java, checked in, so the tree compiles without the AAR
:test:arch               the layer rules, applied to the project AND to violating fixtures
build-logic              convention plugins
```

**`:domain` is a JVM module, not an Android one** (`settings.gradle.kts:44-45`). That is
structural enforcement of rule 1: an Android type is not merely discouraged there, it is
not on the classpath.

---

## 2. The shape the native mandate requires

`:pjsip` stops being a wrapper around a binary and becomes the native build itself.

```
:pjsip                   the native build: CMake entry point, NDK toolchain, .so per ABI
  CMakeLists.txt         the single entry point
  third_party/pjproject  vendored, complete, upstream + patches, PRUNED (ADR-007)
  third_party/{openssl,opus,libvpx}
                         vendored under the same rule and the same pruning
  patches/               every local change, numbered, applied in order (N-7)
:pjsip:api               NOT CHECKED IN (N-13). Generated FROM third_party/pjproject by the
                         same build that produces the .so, so the JNI symbol names cannot
                         drift from the binary
```

`third_party/lyra` is **absent pending the §2.4 gate**. See `docs/native-dependencies.md`
for the gate's status and both exits.

### 2.0 DECIDE — `third_party/` sits at the repository root, not under `pjsip/`

The master prompt is inconsistent about this and the difference is load-bearing, so it is
settled here.

**§7's layout diagram** nests the vendored trees under the module: `:pjsip` →
`third_party/pjproject`. **§2.1.2, N-2 and N-10 all name a bare `third_party/`** — *"with
`third_party/` checked out"*, *"a CI check that the file lists exactly the directories under
`third_party/`"*.

**Root wins, and DoD 23 is what decides it.** That item requires `git ls-files pjsip/` to
return **no `.java` file**. pjproject's own tree contains **13** — five of them the
`org.pjsip` camera and audio helpers that stage 1 copies out
(`pjmedia/src/pjmedia-{video,audio}dev/android/`), the rest sample-app sources. Vendoring
into `pjsip/third_party/` would put all 13 under `pjsip/` and **fail DoD 23 permanently**,
for a reason that has nothing to do with the defect DoD 23 exists to catch.

Three references to one diagram, and a Definition-of-Done item that cannot otherwise be
met. `third_party/` is at the root.

### 2.0.1 Two sample apps are pruned, and the reason is not size

`pjproject/pjsip-apps/src/pjsua/android/` and
`pjproject/pjsip-apps/src/swig/java/android/app/` are demo applications, and each ships a
committed **`gradle-wrapper.jar`**. Neither is a build input: stage 1 needs
`pjsip-apps/src/swig/java/Makefile` and `pjsua2.i`, not the sample that consumes them.

They are pruned because **this repository should not carry a second project's Gradle wrapper
binary**. §2.1.1 exempts *this* repository's `gradle-wrapper.jar` as a tool; it does not
make every wrapper jar in every vendored tree exempt by association. Rule 11 covers `.aar`
and `.so` and would not fire on these — which is precisely why they are removed by hand and
the reason is written down.

Everything above `:data:sip` is untouched by this. That is the seam doing its job: the
whole sourcing model changes and `:domain` does not know.

### 2.1 DECIDE — where the generated bindings land

Master prompt §2.8 leaves this open: *"Whether the generated code lands in a `:pjsip:api`
module fed by a generator task, or directly in `:pjsip`'s source sets, is a structure
decision for §7 — DECIDE it there."*

**Decision: keep `:pjsip:api` as a module; delete its committed sources.**

**Why the module survives.** It is an Android library, not a JVM one, and it has to be: the
five hand-written `org.pjsip` classes use `android.hardware.camera2`
(`pjsip/api/build.gradle.kts:37-41`). `:pjsip` itself will carry an `externalNativeBuild`
and a per-ABI `.so` output. Those are different build shapes with different inputs and
different cache keys, and merging them means the ~15-second SWIG stage cannot run without
configuring the multi-minute native stage.

**What that buys, concretely.** The measured bindings job is **15 s** and the measured
per-ABI native build is **2m46s-3m23s** (`docs/reconciliation.md` B-7). Keeping them as
separate modules keeps them as separate Gradle tasks with separate up-to-date checks, so a
change that affects only the declared feature set re-runs SWIG and not the cross-compile.

**What it does not buy, and this is the part that must not be lost.** Under the old model
`:pjsip:api` was a *fallback* — `:data:sip` took it **instead of** the native library
(`data/sip/build.gradle.kts:71-73`). Under N-14 it is a *component*: `:data:sip` takes
`:pjsip` unconditionally, and `:pjsip` depends on `:pjsip:api`. The module keeps its name
and loses its conditional, which is the whole of N-14 in one sentence.

**The rejected alternative:** folding the generated sources into `:pjsip`'s source set.
Simpler graph, one fewer module — and it couples a 15-second task to a 3-minute one for no
gain. Rejected on the measurement, not on taste.

### 2.2 Vendored trees are exempt from the house style, and only from that

`third_party/` is upstream's code in upstream's conventions.

- **Do not reformat it, do not lint it, do not rename anything in it.** A reformatted
  vendored tree makes every future upstream diff unreadable, which is the one thing
  vendoring must not cost.
- Excluded from ktlint, detekt and lint **explicitly**, in the build files, rather than by
  accident of path. `pjsip/api/build.gradle.kts:66-76` already does this for the generated
  Java and states the reason: findings there belong to pjproject, are not actionable here,
  and cannot be parked in a baseline because CI fails the build if one exists.
- The exemption is from **style**, not from **provenance**. Rule 12 applies to every file
  under `third_party/`.

---

## 3. The rules

### 3.1 The ten that exist

All in `test/arch/src/test/kotlin/com/whatsappv2/arch/ArchitectureRules.kt`, each a pure
function from a set of files to the violations it finds — so the same code runs against the
real project (must find none) and against violating fixtures (must find some).

| # | Rule | Where | Fixture |
|---|---|---|---|
| 1 | `:domain` imports nothing from `android.*`/`androidx.*` | `:114` | `violations/domain/src/AndroidInDomain.kt` |
| 2 | No `org.pjsip.*` outside `:data:sip`; no `org.linphone.*` anywhere | `:144` | `violations/app/src/SdkLeak.kt` |
| 3 | `:feature:*` may not import `com.whatsappv2.data.*` | `:165` | `violations/feature/dialer/FeatureReachesIntoData.kt` |
| 4 | Repository interfaces in `:domain`, implementations in `:data:*` | `:180` | `violations/badrepo/src/MisplacedRepository.kt` |
| 5 | No LiveData, RxJava, AsyncTask or raw `Thread` | `:204` | `violations/app/src/ForbiddenConcurrency.kt` |
| 6 | ViewModels expose immutable state | `:223` | `violations/app/src/LeakyViewModel.kt` |
| 7 | Every design-system component is previewed light and dark | `:247` | `violations/core/designsystem/component/UnpreviewedComponent.kt` |
| 8 | No hardcoded colour, dimension or text style outside the design system | `:265` | `violations/ui/HardcodedStyling.kt` |
| 9 | Contact data does not leave the device | `:345` | `violations/data/contacts/ContactUpload.kt` |
| 10 | Call state is never restored from `SavedStateHandle` | `:388` | `violations/app/callstate/RestoredCall.kt` |

**Rule 5 has exactly one exemption, and it is a scope rather than a suppression.**
`RealPjsipCoreGateway.kt` may construct a `Thread`, because pjsua2 requires every calling
thread to be registered and `libRegisterThread` allocates a descriptor freed only at
library destruction — posting to `Dispatchers.IO`'s 64-thread pool would leak one per
thread for the life of the process (`ArchitectureRules.kt:298-316`). The exemption is a
**path**, deliberately, so it stops applying the moment the file moves, and a test asserts
the exempt file still exists.

### 3.2 The two the native mandate adds

Master prompt §7 calls these "rules 5 and 6". **Both numbers are taken** — see
`docs/reconciliation.md` B-5. Adding them at those numbers would renumber four existing
rules and invalidate every citation to them, so they land as **11** and **12**.

#### Rule 11 — no prebuilt native binary in the tree (N-1)

**What it forbids.** No `.aar` and no `.so` may be committed anywhere in the repository,
and no build file may reference one that is not a build output of `:pjsip`.

**Two clauses, because there are two ways to bring a binary in:**

1. **The file.** Any tracked `*.aar` or `*.so` outside `:pjsip`'s build output directory.
2. **The reference.** Any build script containing `flatDir`, or a `files(...)` /
   `artifacts.add(...)` naming an `.aar` or `.so` path.

**Why both.** Deleting the file and leaving the reference produces a build that resolves
nothing and fails obscurely; deleting the reference and leaving the file leaves a 19 MB
binary in git history that a future edit can re-wire in one line.

**The toolchain exemption is not this rule's problem.** Master prompt §2.1.1 is explicit
that the rule as written already draws the line correctly: it fails on a committed
`.aar`/`.so`, not on a compiler. The NDK's `clang`, `swig` and `gradle-wrapper.jar` are
never tracked files under this rule's scope. **No exemption list is needed, and adding one
would be the first crack in it.**

**Fixture** (`violations/nativeblob/`): a directory containing a stub `libfoo.so` **and** a
build file with a `flatDir` reference to it. The rule must fire on both, separately — a
fixture that only proves one clause leaves the other unproven, which is rule 4's lesson
applied to itself.

**Note on what this rule makes true today.** `pjsip/libs/*.aar` is gitignored
(`.gitignore:22`), so the property currently holds — but it is held by a convention that
`git add -f` defeats silently. Rule 11 is what turns it into a check.

#### Rule 12 — vendored source is only changed through `patches/` (N-7)

**What it forbids.** Any difference between `third_party/<dep>` and *upstream at the
recorded commit, plus the patches in `pjsip/patches/` applied in order*.

**How it is checked.** Hash the tree on disk; compare against the hash of
upstream-plus-patches. The upstream commit is the one recorded in
`docs/native-dependencies.md`, so the check needs the network **only when a pin changes** —
which is the property that lets it run on every build rather than nightly.

**Why an unrecorded edit is worse than it sounds.** It is invisible at the next version
bump. Someone bumps pjproject, re-applies the patch series, and the undocumented fix is
silently gone — a fixed bug comes back with no commit that removed it.

**Fixture** (`violations/vendored/`): a miniature vendored tree with a recorded hash, one
file edited, and no patch recording the edit. The rule must fire.

**Scope note.** Rule 12 is the *only* rule that applies inside `third_party/`. §2.2 exempts
those trees from every style rule; it does not exempt them from provenance.

### 3.3 The rule about the rules

**Rule 4 of the master prompt's four: every rule is proven to actually fire.**
`RulesActuallyFireTest.kt` runs each rule against
`test/arch/src/test/resources/violations/` and requires a non-empty result;
`ArchitectureTest.kt` runs each against real source and requires an empty one.

The fixtures live **outside** the real module directories so they are never compiled, yet
their paths *look* like they are inside them so path-based rules fire on them. That is why
`SourceFile.isUnder` matches a segment anywhere in the path rather than only at the start —
rules 1 and 3 silently failed to fire until this was fixed (`ArchitectureRules.kt:56-63`).

**Rules 11 and 12 are subject to this too, and they are the harder case**, because both
operate on files that are not Kotlin source. Their fixtures are directories, not `.kt`
files, and the existing `SourceFile` scan does not see them. **Adding rule 11 therefore
means extending the fixture harness, not just adding a function** — budget it as such.

---

## 4. Within a module

**Package by feature, not by layer.** `:domain` already does it: `call/`, `engine/`,
`model/`, `registration/`, `recording/`, `repository/`, `usecase/`, `contacts/`. Not
`interfaces/`, `impls/`, `utils/` — those are packages that describe the code's shape
rather than its subject, and they grow without bound because nothing ever obviously belongs
somewhere else.

**Visibility. DECIDE, answered:**

- **Default is `internal`.** A type is `public` only when something outside its module
  needs it, and the reason is the fact that something does.
- **`:domain`'s public API carries KDoc.** It is the contract every other module is written
  against; an undocumented public type there is a contract nobody can read.
- **Rule 5's exemption is the model for every carve-out**: a scope stated in one place a
  reviewer reads, never a baseline file that grows on its own.

**Naming.** A type name states what it *is*; a function name states what it *does* or what
it *returns*, never both. No `Manager`, `Helper`, `Util` or `Handler` unless the name is
genuinely the best available — each one is usually a class that has not decided what it is.

`RealPjsipCoreGateway`, `CallStateMachine`, `RegistrationBackoff`, `LookupCache` are the
house standard: each names a thing, and each is one sentence to describe.

---

## 5. What changes, and in what order

| Change | Rule / requirement | Phase | Status |
|---|---|---|---|
| Rules 11 and 12, with their fixtures | Master prompt §7 | 7 | **Done.** `NativeMandateRulesTest` |
| The fixture harness reads non-Kotlin fixtures | §3.3 | 7 | **Done.** `trackedFiles()` asks git; rule 12 hashes a directory |
| Vendor the four trees, pruned | N-2, ADR-007 | 2a | **Done.** 141 MB, 8,133 files, hashes verified against a fresh checkout |
| Delete the 318 committed `.java` | N-13 | 3a | **Done.** `git ls-files pjsip/` returns three files, none `.java` |
| Delete the `:pjsip:api` fallback condition | N-14 | 3a | **Done.** `data/sip/build.gradle.kts` has no `if` |
| Delete the AAR path in `pjsip/build.gradle.kts` | N-1, N-5 | 3b | **Done.** The module is the native build |
| `:pjsip` becomes an `externalNativeBuild` module | N-4 | 3b | **Written, NOT YET GREEN.** See below |

**The honest status of the last row.** `pjsip/CMakeLists.txt` and `pjsip/build-native.sh`
are ported flag-for-flag from a build that IS green — run `34317978694`
(`docs/reconciliation.md` B-7) — but the port itself has not had a CI run. Until it does,
the accurate claim is *"written from a proven build, not yet proven"*. The three words in
master prompt §10 are not interchangeable, and this is *implemented*, not *verified*.

**The honest cost of phases 3a-3b, stated up front.** Deleting the fallback means that
until stage 1 works, the tree does not compile at all. That is not a regression — it is
N-14 doing its job. **Do not restore the fallback to get a green build**; a green build that
cannot place a call is the exact failure the mandate removes.
