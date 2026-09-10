# Prompt: Design and build this VoIP client to a staff-engineer bar

> **How to use this file.** Paste it whole as the opening message to a coding agent working
> in `whatsapp-v2`. It is the *master* prompt: `android-sip-app-prompt.md` says what to
> build, `pjsip-native-build-prompt.md` covers the native stack in depth, and this one
> governs both. It specifies **how the thinking is done and written down** — HLD, LLD, the
> data structures and algorithms, the module structure, the system design — and, in §2, the
> one thing this project is not allowed to compromise on: **it owns its native stack, from
> source — no binary it did not compile ships inside the APK.** (The toolchain that does the
> compiling is exempt and pinned; §2.1.1 draws that line.)
>
> **DECIDE** = must be answered before code. **VERIFY** = must be checked against source
> before being relied on. **SHOW YOUR WORKING** = a number with the arithmetic beside it.
>
> **Revised 2026-09-09 — achievability pass.** Five requirements could not be met as first
> written, and are now stated so they can be: N-1 exempts the toolchain (§2.1.1), N-2's
> offline test is scoped to third-party native source (§2.1.2), **Lyra is a gated spike with
> a timebox and a written exit** rather than a mandate (§2.4), N-11 is measured over a
> *pinned* dependency set (§2.4.3), and DoD 10 no longer asks for a hardware measurement on
> an ABI that ships in no handset (§12). Three claims were also corrected against upstream:
> pjproject 2.17's CMake has no Android branch at all (§2.3), stage 1 needs `build.mak` and
> the declared feature set (§2.8), and Lyra's model files are ~3.6 MB, not a download problem
> (§9.8). Every engineering standard in §3–§10 is unchanged.

---

## 1. Role and operating principles

You are a staff Android engineer. You are handed an app that already registers, calls, and
holds a native SIP stack you own the build of. Your job is not to add features faster — it
is to make the system **explicable**: every structure justified, every failure mode named,
every number measured.

Five principles, in priority order. When two conflict, the lower number wins.

1. **Correctness under adversarial conditions.** Not "works on my Wi-Fi". NAT rebinding,
   50% loss, carrier handover mid-call, Doze, a registrar that restarts under 5,000 clients,
   a peer that offers a codec you compiled but never tested.
2. **The seam holds.** A new engineer adds a call feature without opening `:data:sip`, and
   swaps the SIP stack without opening `:domain`. This is already true here — ADR-006
   replaced the entire stack behind a 342-line contract. Do not be the change that breaks it.
3. **Testable without a device, a server, or a network.** Business logic runs on the JVM.
   If a rule can only be verified by placing a real call, it is in the wrong layer.
4. **Bounded everything.** Every queue, cache, retry, buffer and timer has a stated bound
   and a stated policy for what happens at that bound. Unbounded is a crash with a delay.
5. **Battery.** This app holds a registration for hours. Every wakeup, every timer, every
   keepalive is a withdrawal from a budget the user notices at 4pm.

**Anti-principles.** Do not produce: prototype code labelled production; `TODO` stubs
presented as finished; a design document that lists no alternative and no non-goal; a
benchmark without a method; an abstraction with exactly one implementation and no second
one in sight; a "scalability" layer inside an app that serves one user.

These five are unchanged and stay in force. §2 sits beside them: it is not a sixth
principle, it is a **constraint on the whole system** that the five are applied inside.

---

## 2. The native mandate — this project owns the stack, source and all

This section is the reason the prompt was revised. It **overrides every earlier instruction
about where binaries come from**, including ADR-006's sourcing paragraph
(`docs/architecture.md:218`) and the "take `pjsua2.aar` from the workflow artifact"
instruction in `pjsip/build.gradle.kts:46` and `docs/pjsip-migration.md` P-2. The
engineering standards, the layer rules, the seam and the review rubric stand exactly as
written. The definition of done stands too, with the five items the 2026-09-09 achievability
pass restated — 10, 16, 17, 20 and 22 — each still binary, and each now checkable.

**The rule, in one sentence:** *no binary that this repository did not compile, from source
that lives in this repository, may ship inside the APK.* The toolchain that does the
compiling is exempt, and that exemption is **written down rather than assumed** — §2.1.1.
Read it before reading the table, because without it N-1 forbids the NDK's own clang and no
implementation can satisfy it.

### 2.1 The fourteen requirements

Each is binary, and each has a place it is proved. A phase report that cannot point at the
proof column has not delivered the row.

| # | Requirement | Proved by |
|---|---|---|
| N-1 | **No prebuilt PJSIP artifact is consumed — binary or generated.** Not a third-party AAR, not a vendored `.so`, not an artifact downloaded from a previous CI run and committed, and **not pre-generated SWIG Java** (see N-13). Toolchains are exempt — §2.1.1 | An architecture rule that fails the build on any `.aar`/`.so` in the tree that is not a build output of `:pjsip` (§7), with its violating fixture |
| N-2 | **The complete pjproject source is vendored into this repository** | The scoped offline test in §2.1.2: with `third_party/` checked out and egress blocked, the **native stage** runs to completion — no `curl`, no `git clone`, no submodule init, no fetch of any kind |
| N-3 | **Conditional on the §2.4 gate.** *Gate passed:* the complete google/lyra source is vendored with its model files **and its dependency closure pinned to commit hashes** (§2.4.3). *Gate failed:* Lyra is out of the declared feature set, and ADR-008 records why | Gate passed: the §2.1.2 offline test with Lyra in it, plus the model manifest in §2.4.4. Gate failed: ADR-008, and no Lyra row in N-8's feature set — so N-9 has nothing to prove |
| N-4 | **PJSIP — and Lyra if the §2.4 gate passed — are built from source with the Android NDK, driven by CMake** as the single entry point | One `./gradlew` invocation from a clean tree produces every `.so`, **on a machine carrying the pinned toolchain — which is CI, not a laptop** (§13). See the DECIDE in §2.3 before writing a line of it |
| N-5 | **The native stack builds automatically in GitHub Actions. No manual native step exists** — no "download the artifact and drop it in `pjsip/libs/`", no local NDK, no hand-run `configure` | The green run, from a clean checkout, with no `workflow_dispatch` input a human has to remember |
| N-6 | **Every supported ABI is produced from source**: `arm64-v8a`, `armeabi-v7a`, `x86_64` (`.github/workflows/build-pjsip.yml:164-181`) | A packaging assertion that names the expected `.so` set per ABI and fails on a missing one |
| N-7 | **Any pjproject source file may be modified**, and every modification is a reviewable patch — never an untracked edit to a vendored tree | `pjsip/patches/` (§2.6), and a test that the vendored tree equals upstream-plus-patches, where *upstream* is the commit hash recorded in `docs/native-dependencies.md` — so the check needs the network only when a pin changes |
| N-8 | **Full control over SIP, RTP, audio, video, conferencing, codecs and media processing** is exercised, not merely claimed: the compile-time feature set is this project's decision, written down, and each flag is traced to a capability the app uses | `config_site.h` generated from a single declared feature set, with each flag annotated with the app behaviour that needs it |
| N-9 | **Every codec in the declared feature set (N-8) is proved registered and usable at runtime.** Lyra appears here only if the §2.4 gate passed; if it did not, it is not in the declared set and there is nothing to prove | The startup codec audit in §2.5, plus an on-device round trip for every declared codec |
| N-10 | **Every native dependency is documented**: name, upstream URL, exact version/commit, licence, why it is here, and every local patch | `docs/native-dependencies.md` (§3), and a CI check that the file lists exactly the directories under `third_party/` |
| N-11 | **The build is reproducible over a pinned set.** Two builds of the same commit produce the same `.so`, or the document states precisely why they cannot and what varies. **A dependency that floats — a `branch =` rather than a commit — is a defect to fix before N-11 is measurable at all** (§2.4.3) | Two runs, hashes compared, recorded in `docs/native-dependencies.md`, with the pinned input set listed beside them |
| N-12 | **The HLD, LLD, System Design, Module Structure and Architecture documents describe this build** — not the AAR-fetching one they describe today | §3–§8, and DoD item 14: no document describes a component that no longer exists |
| N-13 | **The Java bindings are generated by the build, never committed.** SWIG runs against `third_party/pjproject`; the 318 Java files under `:pjsip:api` leave git, and the hand-written `org.pjsip` camera/audio classes are copied from the vendored tree rather than kept as a second copy | `git ls-files pjsip/` lists no `.java`; the generated sources appear only under a build directory (§2.8) |
| N-14 | **There is exactly one way to consume PJSIP.** No conditional fallback, no "the AAR wins if it exists", no path that compiles but cannot run | The condition at `data/sip/build.gradle.kts:71` is deleted, and `:data:sip` takes `:pjsip` unconditionally (§2.8) |

**"Vendored" has a test, and a submodule fails it.** A git submodule is a pointer to
somebody else's repository resolved at fetch time; it re-introduces exactly the "the source
comes from elsewhere" property N-2 removes, and it breaks N-7 the first time you patch a
file. Vendored means the files are in this repository's own tree, in this repository's own
history, reviewable in a diff.

**SHOW YOUR WORKING on the cost — here is the arithmetic, done.** The repository today is
**30 MB** of `.git`. Upstream sizes, from the GitHub API on 2026-09-09:

| Tree | Size | In scope |
|---|---|---|
| `pjsip/pjproject` | 50.6 MB | Always (N-2) |
| `webmproject/libvpx` | 75.7 MB | Always |
| `xiph/opus` | 25.7 MB | Always |
| `openssl/openssl` | 351 MB | Always |
| `google/lyra` | 20.3 MB | Gate-dependent (§2.4) |
| `tensorflow/tensorflow` @ v2.11.0 | **1348 MB** | Only if Lyra must build **offline** (§2.4.3) |
| `protocolbuffers/protobuf` @ v3.15.4 | 234 MB | Same |
| `abseil/abseil-cpp` | 21.7 MB | Same |

So: **~500 MB** for the four unconditional trees, and **1.5–2.5 GB** once Lyra's offline
Bazel closure is included — paid on every clone and on each of the three per-ABI CI
checkouts. GitHub's own guidance is to stay under 1 GB.

**The threshold, stated in advance so it is a decision and not a discovery:** if the
vendored tree would exceed **1 GB**, that is an ADR *before* the commit lands. This number
is one of the four inputs to the §2.4 gate, and it is the reason Lyra is gated and the other
four trees are not. Record the final `du -sh` per tree, and the clone size before and after,
in `docs/native-dependencies.md`.

### 2.1.1 The toolchain exemption — what N-1 does not mean

N-1 governs **dependencies**, not **tools**. Read literally without this paragraph it
forbids the NDK's prebuilt `clang`, the `swig` binary, `gradle-wrapper.jar` and every
artifact Gradle resolves from Maven — which would make the mandate unsatisfiable by
construction rather than demanding.

**The test that separates the two: does the artifact ship inside the APK?** A `.so` does;
a compiler does not. Everything that ships is built here from vendored source. Everything
that *does the building* is exempt, pinned by version, and named in
`docs/native-dependencies.md` under a separate heading:

- The **Android NDK** (r27 or later) and everything in it — clang, lld, libc++, the sysroot.
- **CMake, Ninja, Make, autotools, Bazel, SWIG, Python, the JDK.**
- **`gradle-wrapper.jar`** and every JVM dependency Gradle resolves — AGP, Kotlin, Compose,
  Room, Hilt. These are pinned in `gradle/libs.versions.toml`, which is where that supply
  chain is already governed.

Two obligations survive the exemption. **Pin every exempt tool by exact version** — an
unpinned NDK is a silently different `.so` and defeats N-11. And **§7 rule 5 stays exactly
as written**: it fails on a committed `.aar`/`.so`, not on a compiler, so the rule already
draws this line correctly and needs no change.

### 2.1.2 The offline test, precisely

`./gradlew` cannot run with the network unplugged on a cold Gradle cache — AGP and Kotlin
resolve from Maven. That is the toolchain, and §2.1.1 exempts it. Stating the test as "a
fresh clone with no network builds the stack" makes N-2 and N-3 unprovable; here is the
provable form, which is what they actually mean:

> With `third_party/` checked out and **egress blocked after checkout**, the native stage
> runs to completion — configure, compile, link, SWIG, package — for every ABI.

- The proof is a **CI job that blocks egress with a firewall rule** and then runs the native
  stage. A promise in a document is not the proof; a job that fails when a process opens a
  socket is.
- Nothing in that stage may `curl`, `git clone`, initialise a submodule, or let Bazel fetch
  a repository rule. Under the Lyra path this is the hard part, not a formality — §2.4.3.
- Gradle's own dependency resolution is explicitly **out of scope**, and the CI job is
  scoped so this is a fact about the job rather than an excuse in prose.

### 2.2 What this changes — the verified baseline

Read this before proposing anything. Every row is a fact in the tree today, and every row
is something the mandate moves.

| Today | Where | What N-1…N-14 makes of it |
|---|---|---|
| Sources are downloaded as tarballs at build time — pjproject, OpenSSL, Opus, libvpx | `.github/workflows/build-pjsip.yml:85`, `:271`, `:281`, `:293`, `:328` | Deleted. The sources are in `third_party/`; the workflow builds what is checked out |
| Versions are `workflow_dispatch` inputs a human types: pjproject `2.17`, OpenSSL `3.5.0`, Opus `1.5.2`, libvpx `v1.17.0` | `.github/workflows/build-pjsip.yml:33-47` | Deleted. The vendored tree *is* the version. A bump is a commit, reviewable, with the patch set re-applied |
| `:pjsip` publishes a prebuilt `libs/pjsua2.aar` through its `default` configuration, and warns when it is absent | `pjsip/build.gradle.kts:36-55` | Replaced. `:pjsip` becomes a **source** module that compiles the vendored trees and produces the `.so` files itself |
| That AAR is gitignored and fetched from a workflow artifact by hand | `.gitignore:22`, `pjsip/build.gradle.kts:46-54` | Deleted, both halves — the manual fetch is exactly the "manual native build step" N-5 forbids |
| `:data:sip` depends on `project(":pjsip")` and **silently falls back** to `:pjsip:api` when the AAR is missing — a build that compiles and cannot run | `data/sip/build.gradle.kts:51-73` | **Deleted, condition and all** (N-14). One unconditional dependency on `:pjsip`. A build with no native output fails loudly instead of producing an APK that dies on the first call |
| **318 Java files are committed** under `:pjsip:api` — 313 SWIG-generated, plus 5 hand-copied from pjproject's Android device sources — pinned to pjproject 2.17 / SWIG 4.2.0 | `pjsip/api/build.gradle.kts:20-41`, `git ls-files pjsip/` | **Deleted from git** (N-13). They are regenerated per build from `third_party/pjproject`, by the same build that produces the `.so`. See §2.8 — this is the second of the two prebuilt paths, and the reason it existed disappears once the source is in the tree |
| The native workflow has never completed a run | `.github/workflows/build-pjsip.yml:12-14` | **VERIFY this first.** If it is still true, the first green build from vendored source is Phase 2 and nothing downstream starts |

### 2.3 CMake and the NDK — DECIDE before writing the build

N-4 requires the NDK plus CMake. Two facts have to be on the table before you choose a
shape, because getting this wrong costs weeks. Both were checked against upstream
**pjproject 2.17 on 2026-09-09** and both contradict what this project believed when
`pjsip-native-build-prompt.md` §2.1 was written:

**pjproject 2.17 does ship a CMake build — and it is marked experimental.** The root
`CMakeLists.txt` declares `project(pjproject … LANGUAGES C CXX)`, requires CMake
`3.28…4.0`, and prints a warning naming its **tested platforms: Linux x86_64 and macOS**.
Android is not among them — and "untested" understates it: see option (a) below, where the
concrete meaning of that absence is recorded. It carries the codec options this project cares about —
`PJMEDIA_WITH_OPUS_CODEC`, `PJMEDIA_WITH_VPX_CODEC`, **`PJMEDIA_WITH_LYRA_CODEC`** — each
backed by a find module in `cmake/` (`FindOPUS.cmake`, `FindVPX.cmake`, `FindLyra.cmake`,
`FindOpenH264.cmake`, `FindSRTP.cmake`). So "NDK + CMake" is achievable much more directly
than the autotools framing suggested; what it is not is *proven on Android by upstream*.

**The autotools path is the one Android is documented and tested on** —
`configure-android`, `make dep && make`, then SWIG in `pjsip-apps/src/swig` — and it is
what `.github/workflows/build-pjsip.yml` already encodes, including three failure modes
this repository has already paid for.

Three shapes honour N-4. Record the choice in ADR-007 and say which in the phase report.

**Recommended sequence: (b) first, then port to (a).** Not because (a) is worse — it is the
better destination — but because this repository has **never had a green native build**
(`.github/workflows/build-pjsip.yml:12-14`). Standing an Android-untested build system on
top of an unproven cross-compile means a failure tells you nothing: you will not know
whether it is CMake-on-Android or your own flags. Get (b) green from vendored source first,
then port to (a) with a working reference build to diff the output against.

- **(a) Upstream CMake + the NDK toolchain file — the destination.** `third_party/pjproject`
  configured with `-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake`,
  `-DANDROID_ABI=<abi>`, `-DPJMEDIA_WITH_LYRA_CODEC=ON`, reached through
  `externalNativeBuild` so one `./gradlew` builds everything. This is the mandate as
  literally stated, and it is the only option where CMake is the *real* build rather than a
  wrapper. **Two facts, verified against upstream 2.17 on 2026-09-09, say how big it is.**
  (i) The root `CMakeLists.txt` contains **no Android branch at all** — the only
  `CMAKE_SYSTEM_NAME` test in it is `rtems` (`CMakeLists.txt:54`). There is no Android
  awareness to fix; there is Android awareness to *write*, including the audio/video device
  backends and the `config_site.h` coupling the autotools path handles. (ii)
  `cmake/FindLyra.cmake` resolves through `pkg_check_modules` and a bare `find_library`,
  with no sysroot constraint — on a cross build it will find a **host** `liblyra` and
  link-check green against it unless `Lyra_ROOT` is pinned and
  `CMAKE_FIND_ROOT_PATH_MODE_{LIBRARY,INCLUDE}` are set to `ONLY`. A cross-compile silently
  using host libraries is this repository's recurring failure, and the same caution applies
  to `FindOPUS`/`FindVPX`. Treat (a) as an **upstream port with a real budget**, not a
  configuration exercise. **Timebox any attempt on this before (b) is green, and report what
  broke rather than grinding.**
- **(b) CMake as the driver over the autotools build — start here.** `pjsip/CMakeLists.txt`
  is still the single entry point and still reached through
  `externalNativeBuild`, but it drives each vendored tree with `ExternalProject_Add` /
  `add_custom_command` — `configure-android` for pjproject, and Bazel for Lyra on Exit A of
  the §2.4 gate — using the NDK
  toolchain CMake already resolves, and exposes the results as `IMPORTED` targets. One
  toolchain, one entry point, no manual step, upstream build systems left where upstream
  maintains them. It satisfies N-4 in full — one NDK toolchain, one CMake entry point, no
  manual step — and it is the path `.github/workflows/build-pjsip.yml` already encodes hard
  knowledge about, so a failure here is a failure you can read.
- **(c) A hand-authored CMake build of pjproject's sources.** Several hundred source files
  re-expressed by hand, re-diverging from upstream at every release, and re-doing the
  `.depend`/`config_site.h` coupling that has already produced one silent corruption in
  this repository. Now that (a) exists this has no remaining justification. If you believe
  it is required, **state the case in the phase report and stop for a decision — do not
  start.**

Whichever is chosen, these hold: the NDK is **r27 or later** (16 KB page sizes, mandatory
for Android 15+ and Play submissions — ADR-006); the toolchain comes from the NDK CMake
resolves, never the host's; and `AR`, `RANLIB`, `NM` and `STRIP` are named explicitly for
every autotools sub-build, because the NDK stopped shipping triple-prefixed binutils and
autotools silently falls back to the host's — a failure this repository has already paid
for once (`.github/workflows/build-pjsip.yml:19-20`).

**DECIDE — where the build runs, and how it stays bearable.** A full pjproject
cross-compile is roughly an hour per ABI, and Lyra on Exit A adds a TFLite build on top of
that. N-5 says no manual step; it does not say every
`./gradlew assembleDebug` must spend three hours. The required shape:

- The **canonical property** is that the app build *is* the native build, from vendored
  source, reachable with no human in the loop. Both (a) and (b) have it — (b) satisfies N-4
  in full — so this is not a vote for (a) over the recommended sequence above.
- A CI cache in front of it is permitted, **keyed by a content hash** of `third_party/` plus
  the patch set plus the NDK version plus the feature flags. A cache miss rebuilds from
  source. The cache may never be the only path, and it may never be populated by hand.
- **The cache has a ceiling, and it is smaller than this build.** GitHub Actions gives a
  repository **10 GB in total**, evicted LRU. Three per-ABI install prefixes plus a Bazel or
  TFLite cache go past that, and once they do the cache thrashes and every commit pays the
  full rebuild — precisely the condition that makes people route around the native build and
  a prebuilt binary come back. **DECIDE:** cache the per-ABI *install prefixes*, not the
  object trees; measure the cached size; and if it exceeds ~8 GB, split the key per ABI
  rather than pretending it fits.
- **SHOW YOUR WORKING** on the cache key: state exactly which inputs it covers, and name one
  change to the native build that the key would *not* invalidate. If you cannot name one,
  the key is not understood yet.

### 2.4 Lyra — a gated spike, not a mandate

Lyra was the headline of this mandate and it is the place a false claim is easiest to make.
It is now the one part of §2 that is allowed to end in "no", because **upstream stopped
maintaining it in 2022** and no amount of discipline in this repository changes that. The
gate below has a timebox, four binary criteria and two written exits, so that "we tried
Lyra" produces a decision rather than an open-ended stall in front of everything downstream.

#### 2.4.1 It is real, and it is worth having

pjmedia ships a Lyra codec and the checked-in bindings expose it: `CodecLyraConfig` with
`bitRate` and `modelPath`
(`pjsip/api/src/main/java/org/pjsip/pjsua2/CodecLyraConfig.java:51-64`), reachable through
`Endpoint.setCodecLyraConfig()`
(`pjsip/api/src/main/java/org/pjsip/pjsua2/Endpoint.java:297`). It carries intelligible
wideband speech at **3.2, 6 or 9.2 kbps** — an order of magnitude under Opus, which on a
congested mobile uplink is the difference between a call and no call. That prize is why the
spike is worth running at all.

#### 2.4.2 pjproject does not contain Lyra. It links against a Lyra you built.

Verified against upstream 2.17 on 2026-09-09, and this is the fact that shapes the phase:

- `third_party/` in pjproject 2.17 holds `BaseClasses`, `bdsound`, `g7221`, `gsm`, `ilbc`,
  `milenage`, `mp3`, `resample`, `speex`, `srtp`, `threademulation`, `webrtc`,
  `webrtc_aec3` and `yuv` — **no lyra**. `PJMEDIA_HAS_LYRA_CODEC` defaults to `0`
  (`pjmedia/include/pjmedia-codec/config.h`, and `docs/pjsip-migration.md:456`).
- `aconfigure.ac:2583` defines `--with-lyra=DIR`, and `DIR` is a **prefix you populate**:
  configure adds `-L$LYRA_PREFIX/lib`, links `-llyra`, and puts
  `$LYRA_PREFIX/include/com_google_absl`, `.../gulrak_filesystem` and
  `.../com_google_glog/src` on the include path (`aconfigure.ac:2607-2621`). The model
  directory is derived as `$LYRA_PREFIX/model_coeffs` (`:2641`).
- Its usability check compiles and **links** C++17 against `lyra_decoder.h`, constructing
  `chromemedia::codec::LyraDecoder` (`aconfigure.ac:2637-2638`). So `-llyra` must resolve
  *with its transitive closure* — absl, glog, gulrak-filesystem and TensorFlow Lite — built
  by **Bazel**, alongside the autotools/CMake used for everything else. Producing that
  prefix layout is work Lyra's own build does not do for you.
- **When cross-compiling, Lyra is off unless `--with-lyra` is given** — `aconfigure.ac:2589-2591`
  sets `enable_lyra=no` for any cross build that did not pass it. An Android build that
  forgets the flag produces a stack with no Lyra and no complaint.

#### 2.4.3 The upstream state — why this is a gate and not a task

Verified against `google/lyra` on 2026-09-09. Every row is a fact, and four of them are
blockers this repository would have to solve alone:

| Fact | Value | What it costs |
|---|---|---|
| Last commit | `47698dad`, **2022-12-20** (v1.3.2) | Abandoned ~4 years. No upstream fix is coming for anything below |
| `.bazelversion` | **5.3.2** | Bazel 5 is end-of-life |
| Android NDK in its README | **r21.4.7075529** | §2.3 mandates **r27+ for 16 KB pages** (ADR-006, Play). **VERIFY** the exact NDK ceiling of Bazel 5's `android_ndk_repository` — it is well below r27 |
| TFLite | `git_repository org_tensorflow` @ v2.11.0 | A 1348 MB tree fetched at build time, plus TF's own `workspace2()`/`workspace3()` fetching dozens more archives |
| `com_google_glog` | `branch = "master"` | **Unpinned — floats** |
| `com_github_gflags_gflags` | `mchinen/gflags`, `branch = "android_linking_fix"` | Unpinned, and an individual's fork |
| `fft2d` | `http://…kurims.kyoto-u.ac.jp/~ooura/fft2d.tgz` | Plain HTTP, one academic host, no mirror |
| `maven_install` | androidx artifacts, at build time | Another network fetch inside the native stage |

Four requirements collide here, and naming them is what makes the gate decidable:

1. **The NDK conflict is the first-order blocker.** Lyra at r21 and pjproject at r27 is a
   C++17 libc++ ABI mismatch across the `-llyra` link, and an r21-built `.so` is not 16 KB
   aligned. Either Lyra's build is ported to a modern NDK, or the two halves cannot ship in
   one APK. **This is criterion 1 and it is checked first, because everything else is
   wasted if it fails.**
2. **The offline test (§2.1.2) is not free here.** Bazel resolves `git_repository` over the
   network by definition. Offline means converting every repository rule to
   `local_repository`/`--override_repository` and vendoring TF v2.11 + protobuf 3.15.4 +
   absl + the TF workspace closure — the 1.5–2.5 GB in §2.1's cost table.
3. **N-11 cannot even be measured until the floats are pinned.** Two `git_repository` deps
   track a branch, so today the *inputs* are non-deterministic and the §2.3 content-hash
   cache key is unsound. **Pinning glog and the gflags fork to commit hashes, and mirroring
   `fft2d` into this repository, is the first act of the spike — before any build attempt.**
4. **The `.tflite` weights are prebuilt binaries this repository cannot compile.** They are
   data, not code, and N-3 deliberately requires vendoring them — but say so out loud in
   `docs/native-dependencies.md` rather than letting N-1's absolutism read as covering them.

#### 2.4.4 The gate — timebox, criteria, and both exits

**Timebox: 5 working days.** It runs during phase 2 (§11), in parallel with the vendoring of
the other four trees, and it is **decided before phase 3b starts**. It may not be extended
without an ADR; "nearly working" at day 5 is a fail, because the cost of the sixth day is
paid by every phase behind it.

**Pass requires all four, each binary:**

1. `liblyra` and its closure build for `arm64-v8a` with **NDK r27+**, and the resulting
   `.so` files are 16 KB aligned.
2. `./configure-android --with-lyra=<prefix>` prints `Checking lyra usability... yes` and
   the generated `config_site.h` carries `PJMEDIA_HAS_LYRA_CODEC 1` — asserted, per §2.4.5.
3. Every dependency is pinned to a commit hash, and the native stage passes the §2.1.2
   offline test with Lyra in it.
4. The vendored tree stays under the **1 GB** threshold in §2.1, or an ADR raises it
   deliberately.

**The other two ABIs are gated too — just later, and with the same two exits.** Criterion 1
proves `arm64-v8a` only, deliberately: it is the fast signal and it is where the NDK
conflict shows up first. `armeabi-v7a` and `x86_64` are proved in phase 3b, and **a failure
there is Exit B applied late** — 2 working days to fix it, then ADR-008 and Lyra leaves the
declared feature set. This is the rule that stops a passed gate from becoming an ungated
stall in phase 3b, which is the failure the gate exists to prevent.

**Default on a partial pass: drop Lyra everywhere, not per ABI.** A build where `arm64-v8a`
users negotiate Lyra and `armeabi-v7a` users silently do not is a support question nobody
can answer, even though the codec audit (§2.5) would report it correctly. Shipping a
per-ABI feature set requires its own ADR arguing for it; absent that, one failed ABI is
Exit B for all three.

**Exit A — gate passed.** Lyra is in the declared feature set; N-3, N-9, §2.4.5, §5.1's
model store and DoD 20 all apply as written — for **every** ABI in N-6, not just the gated
one.

**Exit B — gate failed.** This is a **legitimate, complete outcome, not a defeat**, and the
deliverable is:

- **ADR-008**, recording which criterion failed, with the evidence, and rejecting Lyra for
  this release with a stated re-evaluation trigger (a maintained fork, or upstream moving).
- Lyra removed from the declared feature set (N-8), so **N-9 has no Lyra row to prove** and
  DoD 20 is satisfied by the codecs that remain.
- `AudioCodec.LYRA` stays in the domain enum with its correct lowercase id
  (`domain/…/Codecs.kt:38`) and stays out of `CodecPreferences.DEFAULT` (`:78`) — the codec
  audit (§2.5) then reports it as **not compiled**, which is the honest state and is exactly
  what the audit's reason enum exists to say.
- `third_party/lyra` is **not** vendored, and the ~2 GB in §2.1's table is not spent.

Everything else in §2 — N-1, N-2, N-4…N-8, N-10…N-14 — is unaffected by either exit. That
independence is the point of gating it here rather than discovering the conflict in phase 3b.

#### 2.4.5 If the gate passes: make the silent failure loud

**This is the "silent no TLS, no Opus" failure again.** If the link check fails — wrong ABI,
missing absl, an STL mismatch — configure sets `ac_no_lyra_codec=1`, prints
`Checking lyra usability... no`, and **carries on** (`aconfigure.ac:2649`). The build
succeeds, the APK ships, and the codec is simply absent. The workflow already asserts on
exactly this class of defect for TLS and Opus
(`.github/workflows/build-pjsip.yml:21-23`); Lyra gets the same treatment — assert
`PJMEDIA_HAS_LYRA_CODEC 1` in the generated config and fail the build otherwise.

So the ordering is forced: **cross-compile Lyra first, into a per-ABI prefix, then build
pjproject against it.** Under the CMake shape (§2.3 option (a)) the same prefix is what
`cmake/FindLyra.cmake` resolves — with `Lyra_ROOT` pinned and the find-root modes set to
`ONLY`, or it resolves the host's copy instead (§2.3). Either way, Lyra is this project's
own build output from vendored source, which is what N-1 requires — a prefix you filled
yourself is not a prebuilt binary.

**Four model files must reach the device**: `lyra_config.binarypb`, `lyragan.tflite`,
`quantizer.tflite`, `soundstream_encoder.tflite`, with `CodecLyraConfig.modelPath` pointed
at an extracted, readable path. Without them the codec **registers and then fails when a
stream opens** — which is worse than not having it, because it advertises a capability it
cannot deliver. Ship a content-hash manifest of the four files and check it at extraction;
a truncated asset must be a loud startup failure, not a dead call. Their total size is
**~3.6 MB** (§9.8), so this is a correctness problem, not a download-size one.

**The payload id is lowercase `lyra`.** The stack matches preferences to codec ids by
prefix, so an uppercase `LYRA` matches `lyra/16000/1` **never**, and does so silently. The
domain enum already spells it correctly and a test pins it
(`domain/src/main/kotlin/com/whatsappv2/domain/model/Codecs.kt:38`). Do not unpin it — this
holds under **both** exits.

**And a second endpoint has to agree.** A codec is a contract between two ends, and the
deployed FreeSWITCH cannot negotiate Lyra. `AudioCodec.LYRA` is deliberately absent from
`CodecPreferences.DEFAULT` (`domain/src/main/kotlin/com/whatsappv2/domain/model/Codecs.kt:78`)
— selectable, never offered until the binary can honour it. **DECIDE:** is there a
server-side Lyra plan? If not, then even on Exit A the honest deliverable is *"Lyra compiled
from vendored source, registered, model files verified, and provably selectable, with no
deployed peer that accepts it"* — which is exactly what N-9 asks for, and it is a real
result. What it is not is "Lyra calling works". Do not report the former as the latter.

### 2.5 Runtime proof — every codec, every start

N-9 is not satisfied by a build log. Compiled, registered and negotiated are three different
claims (§10, Honesty).

Design a **codec audit that runs once per endpoint start**:

1. Enumerate what the library actually registered: `Endpoint.codecEnum2()` for audio
   (`pjsip/api/.../Endpoint.java:249`) and `videoCodecEnum2()` for video (`:265`), each
   yielding `CodecInfo{codecId, priority, desc}`.
2. Diff that against the **declared** feature set from §2.1 N-8 — the codecs this build was
   configured to contain. A codec in the declared set and absent from the registry is a
   build defect and must be reported as one, at ERROR, once, with the codec id.
3. **If the §2.4 gate passed**, go one step further than registration for Lyra: verify the
   model manifest, set `CodecLyraConfig.modelPath`, and prove an encode/decode round trip on
   device — a self-call through the endpoint is enough and needs no second party. A codec
   that registers and dies on stream open passes step 2 and fails the user. **If the gate
   failed**, Lyra is not in the declared set, and the audit reports it as *not compiled*
   rather than as a defect — which is step 2 working, not step 2 being skipped.
4. Feed the result into the existing priority application rather than beside it:
   `RealPjsipCoreGateway` already applies preferences only to codecs PJSIP registered
   (`data/sip/.../RealPjsipCoreGateway.kt:871-889`), which is why the H264-in-defaults
   mismatch is skipped rather than raised. That skip is now *reportable evidence*, not a
   silent no-op.

The audit result belongs in the domain as a value — an available-codec set with a reason
per absent codec — so the UI can say "Lyra: built, no peer accepts it" instead of showing a
toggle that does nothing.

### 2.6 Provenance — dependencies, licences, versions, patches

N-10 in detail. `docs/native-dependencies.md` carries one row per vendored tree:

| Column | Rule |
|---|---|
| Name and upstream URL | The canonical project, not a mirror |
| Exact version | Tag **and** commit hash. A tag can move; a hash cannot |
| Licence | The SPDX identifier, plus the full licence text under `third_party/<dep>/LICENSE` — copied, not linked |
| Why it is here | The app capability that would be lost without it. A dependency with no answer here is removed |
| Local patches | Every file in `pjsip/patches/`, each with what it changes and why, and whether it has been sent upstream |
| Size | `du -sh` of the vendored tree, and its contribution to the APK per ABI |

**The exempt toolchain is recorded too, under its own heading** — *Toolchain (not vendored,
not built here)*, per §2.1.1. One row each for the NDK, CMake, Ninja, autotools, Bazel,
SWIG, Python and the JDK, with the exact pinned version and where CI gets it. These are not
`third_party/` directories and the N-10 check must not expect them there; they are listed
because an unpinned tool is a silently different `.so` and defeats N-11.

**Patches are files, never edits.** N-7 allows any pjproject source file to be modified; it
does not allow a modified vendored tree with no record of what changed. Every local change
is a numbered patch in `pjsip/patches/`, applied by the build in order, with a test that the
vendored tree equals upstream-plus-patches. An unrecorded edit to vendored source is
invisible at the next version bump — it is how a fixed bug comes back.

### 2.7 The licence blocker got sharper, not softer

ADR-002 records the licence position as **GPLv2 working assumption, commercial licence
UNRESOLVED** (`docs/architecture.md:33`). Building from vendored source does not soften
that — it sharpens it. Statically linking GPLv2 code into a closed-source APK you distribute
is precisely the case the GPL addresses, and the source is now *in this repository*, which
makes the obligation visible to anyone who clones it. Lyra carries its own licence, and it
is a separate question with its own answer.

**DECIDE before any store submission**, and say so in every phase report until it is
decided. This is a release blocker, not a footnote.

---

### 2.8 The two doors into PJSIP — and why both are bricked up

**There are exactly two ways this repository consumes PJSIP today, and neither is built
from source by the build.** Both go. Say this in the phase report, because "we build PJSIP
from source in CI" is true of the *first* door and has been read as covering the second.

| # | Door | What it carries | Selected by | Failure it produces |
|---|---|---|---|---|
| 1 | `:pjsip` → `pjsip/libs/pjsua2.aar` | Java **and** `libpjsua2.so`, all ABIs | The file existing on disk — put there by a human who downloaded a CI artifact | None at build time. It is simply a binary nobody in a fresh clone can reproduce (N-1, N-5) |
| 2 | `:pjsip:api` — **318 committed `.java` files** | Java **only**. No native library | `data/sip/build.gradle.kts:71` — taken automatically *because the AAR is absent* | An APK that compiles, installs, and raises `UnsatisfiedLinkError` on the first SIP call (`pjsip/api/build.gradle.kts:51-56`) |

Door 2 is the subtler one and it is worth being precise about, because it was a good
decision under the old model. It exists so that CI can compile and check `:data:sip`
against the real pjsua2 API while the native build is still being brought up — without it,
a fresh clone yields 259 unresolved references and the entire gate stops before detekt,
lint and the architecture rules ever run (`pjsip/api/build.gradle.kts:6-16`). That is a
real problem and it was solved correctly *for a repository that did not contain pjproject's
source*.

**This repository will contain pjproject's source, so the problem dissolves.** SWIG needs
no NDK, no OpenSSL, no cross-compile — it parses the pjsua2 headers and emits Java in about
a minute. Once `third_party/pjproject` is in the tree, the bindings can be *generated* on
every build as cheaply as they can be *read from git*, and the reason to commit them is
gone.

**Two details make stage 1 real work rather than a one-line `swig` call. Both verified
against upstream 2.17 on 2026-09-09.** First, upstream's make path is not usable on its own:
`pjsip-apps/src/swig/java/Makefile:1` opens with `include ../../../../build.mak`, a file
`configure` writes — so stage 1 either runs a **host** `./configure` (seconds; no NDK, no
cross-compile, no OpenSSL) or invokes `swig` directly with the include set that Makefile
lists. Second, **SWIG parses `config_site.h`**, so the generated Java depends on the
declared feature set (N-8) — the same header stage 2 compiles against. Stage 1 therefore
takes the declared feature set as an *input*, and generating it from a different one than
stage 2 uses re-creates precisely the drift N-13 exists to remove. It is still a minute of
wall-clock. It is not zero design, and budgeting it as zero is how stage 3a slips.

**And generating them removes a whole class of crash.** The `.so` exports
`Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java SWIG generated beside it.
A committed copy can drift from the binary — between SWIG 4.2.0 and 4.4.1 the typed vector
classes rename `add`/`reserve` to `doAdd`/`doReserve`, and 45 of these files differ
(`pjsip/api/build.gradle.kts:25-33`). Today that drift is prevented by a comment asking
people to be careful. Generate both halves in one build from one source tree and it becomes
*structurally impossible*, which is the whole argument for this change in one sentence.

**The required shape — two stages, one source of truth:**

```
third_party/pjproject ──► stage 1: SWIG          ──► generated org.pjsip.pjsua2 Java
   (vendored, patched)     no NDK, ~1 minute          + org.pjsip camera/audio classes
                                                        copied from pjmedia/src/…/android
            │
            └───────────► stage 2: CMake + NDK   ──► libpjsua2.so × 3 ABIs
                          slow, content-hash cached
                                     │
                                     └──► :pjsip  ──► :data:sip   (one dependency, no condition)
```

- **Stage 1 restores everything door 2 was protecting**, and costs a minute rather than an
  hour. The CI gate still compiles `:data:sip` against the real API on a fresh clone with
  no native build; it just does it from the vendored headers instead of from git.
- **Stage 2 is what N-14 makes non-optional.** No `if (aar.exists())`. If stage 2 has not
  produced a `.so` for the target ABI, the build **fails** — it does not quietly assemble
  an APK that dies on the first call.
- **The five hand-written classes come from the vendored tree too.** `PjCamera`,
  `PjCamera2`, `PjCameraInfo`, `PjCameraInfo2` and `PjAudioDevInfo` are not SWIG output —
  pjproject ships them beside the C they are called from, in
  `pjmedia/src/pjmedia-{video,audio}dev/android` (`pjsip/api/build.gradle.kts:37-41`). Copy
  them in stage 1. A second hand-maintained copy in git is the same defect as door 2,
  smaller.
- **`:pjsip:api` as a module may survive; its committed sources may not.** Whether the
  generated code lands in a `:pjsip:api` module fed by a generator task, or directly in
  `:pjsip`'s source sets, is a structure decision for §7 — **DECIDE** it there. What N-13
  fixes is that `git ls-files pjsip/` returns no `.java`, and that `settings.gradle.kts:57-59`
  no longer describes a checked-in fallback.

**The honest cost, stated up front.** Deleting door 2 means that until stage 1 works, the
tree does not compile at all. That is not a regression — it is N-14 doing its job, and it
is why §11 phase 3a exists and gates everything after it. Do not restore the fallback to
get a green build; a green build that cannot place a call is the exact failure this section
removes.

---

### 2.9 The ownership ladder — where control actually comes from

Placed last in §2 because it is the section to re-read when someone proposes spending a
month on the build system. **Ownership comes from the source and the flags. The build
system is where the most effort is spent for the least control.** Ranked by
control-per-hour:

1. **Own the patch lineage, not just the source files.** N-7's `pjsip/patches/*.patch` is
   right for a handful of changes. It stops scaling the moment you carry a larger series,
   or need a fix that is on upstream `master` but in no release: you cannot `git log` a
   tarball, and rebasing onto 2.18 becomes manual archaeology. **DECIDE at that point:**
   fork `pjsip/pjproject` into an organisation remote and vendor into `third_party/` *from
   the fork*. Upstream's history comes with it, local changes become commits, and taking a
   security fix is a cherry-pick. The offline test in N-2 still passes — the vendored copy
   is still a copy in this tree; only its provenance improves. **Threshold: start with
   patch files; fork when the series exceeds what you would rebase by hand.**
2. **Own the compile-time feature set.** `config_site.h` plus the configure/CMake options
   are where SIP, RTP, audio, video, conferencing and codec behaviour are actually decided.
   That is N-8, and it is worth more than any build-system choice. One declared list, each
   flag traced to the app capability that needs it, beats flags scattered through a
   workflow heredoc.
3. **Own the proof.** Control you cannot verify is not control — §2.5.
4. **Do not own pjproject's build system.** Option (c) in §2.3 feels like maximum ownership
   and delivers the opposite: permanent divergence, and the loss of every upstream fix you
   did not write yourself. **Divergence is not ownership.**

**And the part that is not engineering.** Owning GPLv2 code means owning its obligations.
If "full control" is meant to include the right to ship this closed-source, that is a Teluu
commercial licence — a *purchase*, not a task — and no amount of build ownership
substitutes for it. Vendoring the source makes the obligation more visible, not less.
See §2.7 and ADR-002.

---

## 3. The artifacts you must produce

Design is a deliverable here, not a preamble to one. Each artifact below has a required
shape. Write them into `docs/`, alongside the ones that exist.

| Artifact | File | What makes it acceptable |
|---|---|---|
| **HLD** | `docs/architecture.md` (extend) | Module graph, layer rules, threading model, sequence diagrams per flow, and an ADR for every decision with alternatives and a rejection reason |
| **LLD** | `docs/lld.md` (extend) | Per-class responsibility, the contracts, state machines with their transition tables, schemas with their indices, error taxonomy |
| **DSA dossier** | `docs/data-structures.md` (new) | §6 — every structure and algorithm, with a complexity bound and the failure it prevents |
| **Structure** | `docs/module-structure.md` (new) | §7 — the module graph, package conventions, visibility rules, and the tests that enforce them |
| **System design** | `docs/system-design.md` (new) | §8 — topology beyond the APK, capacity arithmetic, failure domains, observability, rollout |
| **Native provenance** | `docs/native-dependencies.md` (new) | §2.6 — every vendored dependency, its version, licence, reason and patches, plus the *Toolchain* heading of §2.1.1 with each exempt tool pinned |

All six must describe **the vendored, built-from-source stack** (N-12). A document that
still tells a reader to fetch `pjsua2.aar` from a workflow artifact is a document that
describes a component that no longer exists, and DoD item 14 fails on it.

**The rule that makes these worth writing:** every claim carries either a `path:line`
citation into this repository, or a measurement with its method, or an explicit
`ASSUMPTION:` label. A design document with unlabelled guesses is worse than none — it
launders a guess into a fact that a later engineer builds on.

---

## 4. HLD — what a high-level design must answer here

Extend `docs/architecture.md`. It already carries ADR-001…ADR-006, a module graph (§4.1),
layer rules (§4.2), a threading model (§4.3) and five flow diagrams (§4.4–4.8). Read all of
it before adding a line; most of what a fresh HLD would say is already decided, and
re-deciding it silently is the failure mode.

An HLD section here is complete when it answers all seven:

1. **What are the components, and what may each know?** The layer rule in this repo is
   strict and machine-checked: `:domain` is pure Kotlin with zero Android imports;
   `:feature:*` may not reach into `:data:*`; no `org.pjsip.*` import exists above
   `:data:sip`. See `test/arch/src/test/kotlin/com/whatsappv2/arch/ArchitectureRules.kt`.
2. **What crosses each boundary?** Only `:domain` types cross `SipEngine`. That is what
   makes `FakeSipEngine` a drop-in rather than an approximation, and it is why the whole
   app runs with no server.
3. **What thread is this on, and what happens if it blocks?** `docs/architecture.md` §4.3
   has the table. The load-bearing entry: a blocked pjsua2 callback **stops SIP processing
   entirely** — not just this call, the stack. Every design that touches the native seam
   must state its answer to this.
4. **What is the lifecycle, and who owns the destruction?** pjsua2 objects are native
   handles with an owner. A `Call` that outlives its native peer is a use-after-free that
   reaches the user as a random crash days later.
5. **What happens when it fails?** For each flow: the failure, how it is detected, what the
   client does, and what the user sees. "It retries" is not an answer; §6.3 is.
6. **What was rejected, and why?** An ADR without a rejected alternative is a decision
   nobody actually made. ADR-006's three sourcing options are the model.
7. **What is explicitly a non-goal?** Non-goals are the highest-value paragraph in any
   design doc, because they are the only part that constrains future scope.

**Diagram every flow as a sequence diagram** in mermaid, as `docs/lld.md` already does.
Minimum set: register, outgoing call, incoming call woken by push, hold/resume, blind
transfer, attended transfer, conference join, network handover mid-call, registrar restart
recovery. A flow without a diagram is a flow nobody has traced end to end.

### 4.1 The native build is part of the HLD now

§2 changes the component graph, so it changes this document. Add:

- **A new ADR** — the next free number, ADR-007 if it is still free — recording the move from "build in
  CI, package an AAR, fetch it by hand" to "vendored source, built by the app build". State
  the alternatives that were rejected *this time round*: the third-party AAR (ADR-006
  already rejects it), the CI-artifact AAR that exists today (rejected by N-1 and N-5:
  a manual fetch step and a binary nobody in a fresh clone can reproduce), and the git
  submodule (rejected by N-2's offline test and by N-7's patch requirement).
- **The Lyra gate outcome** (§2.4.4), whichever way it went, as the ADR after that one —
  ADR-008 throughout this document, and renumber both together if 007 is taken. On Exit A it records
  the pinned closure and the NDK it was built with; on Exit B it records which criterion
  failed, with the evidence, and the trigger that would reopen the question. An ADR is owed
  in both directions — a decision not to ship something is still a decision, and it is the
  one a later engineer is most likely to re-litigate without it.
- **ADR-006 is amended, not deleted.** It chose PJSIP and it chose "build from source" —
  both stand. What changes is *where the source lives and who runs the build*. Amend it in
  place with a dated note pointing at ADR-007; do not rewrite history.
- **A build-pipeline diagram**, at the same level of care as the call flows: vendored source
  → patches → CMake/NDK per ABI → `.so` + SWIG bindings → `:pjsip` → `:data:sip` → APK.
  Mark what CI does, what the developer's machine does (nothing native, §13), and where the
  content-hash cache sits.
- **The declared feature set** (N-8) as an HLD-level table: each `config_site.h` flag, the
  app capability that needs it, and the module that exercises it. `PJMEDIA_HAS_VIDEO`,
  `PJMEDIA_HAS_VPX_CODEC`, `PJMEDIA_HAS_OPUS_CODEC`, `PJSIP_HAS_TLS_TRANSPORT`,
  `PJMEDIA_HAS_LYRA_CODEC` and `PJMEDIA_HAS_OPENH264_CODEC` are the ones with a decision
  attached; **VERIFY** the current values against the generated file rather than this list.

---

## 5. LLD — what a low-level design must answer here

Extend `docs/lld.md`. Its existing §1 (call state machine) is the quality bar: it does not
merely draw the machine, it names **the four decisions inside it** and the bug each one
prevents — `HoldParty` instead of a boolean, because both ends can hold at once and a
boolean ships "resume did nothing".

Match that. For every non-trivial type, state:

- **Responsibility**, in one sentence. Two sentences means two classes.
- **The invariant it maintains**, and what breaks if it does not.
- **Its concurrency contract** — which dispatcher, whether it is main-safe, whether it is
  thread-confined, and what it does when cancelled. Note the existing rule: *cancelling
  abandons the result, not the SIP transaction.* A cancelled `placeCall` may still have put
  an INVITE on the wire.
- **Its error model.** This repo uses `Outcome<T, SipError>` — errors are values, not
  exceptions, because a dropped registration is a normal outcome of a mobile network and a
  caller that can forget a `catch` will.
- **Idempotence**, where it applies. Unregistering an unregistered account succeeds quietly.

**State machines are tables, not `if` chains.** The existing `CallStateMachine` enumerates
every `(state, event)` pair and asserts that every pair *absent* from its table is
rejected — so adding an undocumented transition fails the build. Any new machine you write
(registration, media negotiation, conference membership) meets the same bar: a total
function returning an explicit `Rejected` value, never a null and never a silent no-op.

**Schemas carry their indices and their access pattern.** For every Room table, state the
queries that run against it and the index that serves each. An unindexed `ORDER BY` on the
call log is a table scan that grows with the user's history.

### 5.1 The types §2 adds

Three of them, and each needs the full treatment above:

- **The codec audit result** (§2.5). A value in `:domain` — the set of codecs the running
  library registered, and for each declared-but-absent codec, the reason: *not compiled*,
  *compiled but registration failed*, *registered but model files missing*, *usable but no
  peer accepts it*. That last reason is the honest Lyra state, and having it as a value is
  what lets the UI say it.
- **The Lyra model store** — built only on Exit A of the §2.4 gate. Responsibility: extract four asset files to a readable path,
  verify them against a content-hash manifest, and hand back the path
  `CodecLyraConfig.modelPath` needs — or a typed failure. Invariant: `modelPath` is never
  handed out unverified. Idempotent: a second call on an already-extracted, still-valid set
  does no I/O.
- **The native library inventory.** The expected `.so` set per ABI, asserted at packaging
  time (N-6) and re-checked at startup, so "the ABI split dropped a library" is a build
  failure rather than an `UnsatisfiedLinkError` on somebody's phone.

---

## 6. DSA — the structures, with bounds

Write `docs/data-structures.md`. This is the section most prompts omit and most incidents
come from. For **each** entry: the structure, the operation complexities, the bound, the
policy at that bound, and the specific failure it prevents.

### 6.1 The hot path — native events into Kotlin

The single most important design in the app. pjsua2 delivers callbacks on **its own worker
threads**, and blocking one stops the stack. The existing design publishes to a buffered
`SharedFlow` immediately and handles it elsewhere (§4.3 of `docs/architecture.md`).

Specify precisely:

- **Enqueue is O(1), allocation-light, and never blocks.** No lock held across a JNI
  boundary. No suspending call inside the callback.
- **Every buffer has a capacity and an overflow policy, chosen per stream.** These are not
  the same choice: dropping a level-meter sample is invisible; dropping an `incomingCalls`
  event loses the missed call, *which is precisely the call that matters*. The existing
  contract already reasons this way — `incomingCalls`, `endedCalls` and `transferEvents`
  are non-replaying `Flow` with a buffer of 64, because replay would re-ring a call answered
  minutes ago and write a duplicate call-log row on every re-collection.
- **DECIDE and document the policy per stream** in a table: stream, capacity, overflow
  policy, and the consequence of a drop.
- **Thread confinement is an enforced invariant, not a convention.** Every thread that calls
  into pjsua2 must be registered with the library first. Add a debug-build assertion that
  fires on an unregistered thread; this is the top source of native crashes in pjsua2 apps
  and it is silent until it is a `SIGSEGV` in a stack trace with no Kotlin frames. Owning
  the build makes this *more* important, not less: a stack you patched is a stack whose
  crashes are yours to explain.

### 6.2 Structures, with required complexity

| Concern | Structure | Required bound | The failure it prevents |
|---|---|---|---|
| Call registry | Concurrent map keyed by call id | O(1) lookup, O(1) insert | Linear scans on every event; a lost call handle |
| Call state | Transition table `(State, Event) → State` | O(1); table size O(\|S\|×\|E\|) | Undocumented transitions; "resume did nothing" |
| Registration retry | Exponential backoff, **full jitter**, capped, honouring `Retry-After` | O(1) per attempt | 5,000 clients stampeding a restarted registrar |
| Connectivity churn | Coalesce + debounce on a derived network-identity key | O(1) per callback | `ConnectivityManager` fires in bursts during handover; naive handling re-registers 6× per handover |
| Codec preference match | Normalize once, match on the codec-name segment, stable sort by priority | O(n log n) at account setup, **never in the call path** | The real bug in P-9: `LYRA` prefix-matches `lyra/16000/1` **never**, silently |
| **Codec audit** | Set difference: *declared feature set* − *`codecEnum2()` registry*, computed once per endpoint start | O(n) over ≤ ~30 codecs, once | A codec that compiled and did not register — the silent half of "Lyra works" (§2.5) |
| **Lyra model manifest** *(Exit A only)* | Four-entry content-hash map (~3.6 MB total), checked at extraction | O(size) once per version, never per call | The codec that registers and then fails when the stream opens |
| **Native library inventory** | Expected `.so` set per ABI, asserted at packaging and at startup | O(1) per ABI | An ABI silently short a library; an APK that installs and dies on the first call (N-6) |
| **Vendored-tree integrity** | Hash of upstream-plus-patches vs the tree on disk | O(size) once in CI | An unrecorded edit to vendored source; a fixed bug returning at the next bump (N-7) |
| Contact resolution | E.164-normalized key → LRU cache, bounded | O(1) amortized, bounded memory | A `ContactsContract` query per list row |
| Call log paging | Keyset pagination on `(timestamp DESC, id DESC)` with an index | O(log n) per page | `OFFSET` pagination degrades linearly as history grows |
| Audio route | Pure function: available devices + user override → route, over an explicit priority order | O(1), deterministic | A boolean chain that resolves differently depending on callback arrival order |
| Conference roster | Ordered set keyed by participant SIP URI, diffed by stable key | O(n) diff | An MCU reorders participants; index-keyed lists tear the UI |
| Timers | **One** scheduler for refresh, keepalive, session timer, ring timeout | O(log n) insert | N independent delays = N wakeups = battery |
| Log redaction | Single-pass, linear, no backtracking | O(n) strictly | A catastrophically-backtracking regex on a large SIP message is an ANR |
| Level metering | Fixed ring buffer, reused | O(1), zero allocation per tick | Per-frame allocation in a path that runs 50×/second |

**Allocation budget.** State explicitly which paths must not allocate: the native callback,
the per-frame video surface path, and the metering tick. Everywhere else, allocate freely
and readably — premature pooling is its own defect.

**DECIDE** the backoff parameters (base, cap, jitter form) and **SHOW YOUR WORKING** for
why they are safe against the deployed server's concurrent-session ceiling.

### 6.3 Failure algorithms

For each, specify: **detect → decide → act → recover → what the user sees.**

1. Registration lost (network gone, registrar 503, `Retry-After`, TLS handshake failure).
2. Mid-call handover, Wi-Fi ↔ cellular, including the IP change (`pjsua` has an IP-change
   path — **VERIFY** how it is invoked from the bindings before designing around it).
3. Push gateway down. This one is **silent**: everything looks healthy and no incoming call
   ever arrives. Design the detection, not just the recovery.
4. Native stack failure — a missing or mismatched `libpjsua2.so`. Today this is caught at
   configuration time by `pjsip/build.gradle.kts:46`; under §2 the *build* must fail instead,
   and the runtime check becomes the second line of defence rather than the first.
5. Media flowing but silent — SDP negotiated, no RTP arriving. Detect and surface.
6. **Codec advertised but unusable.** Three distinct cases, three different user-visible
   outcomes: not compiled into this build; compiled but not registered; registered but its
   model files are missing or corrupt (Lyra). §2.5 produces the evidence; this is where you
   say what the client *does* with it.
7. **A vendored dependency stops building after a bump.** Detect (CI, on the bump commit),
   decide (revert the bump, or add a patch), act, and record the patch in
   `pjsip/patches/` with its reason. The bump is a commit like any other and reverts like
   one — which is the point of vendoring.

---

## 7. Structure — the file and module layout

Write `docs/module-structure.md`. Start from what exists; justify every change.

**The current graph** (`settings.gradle.kts`):

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

**The shape §2 requires.** `:pjsip` stops being a wrapper around a binary and becomes the
native build itself:

```
:pjsip                   the native build: CMake entry point, NDK toolchain, produced .so per ABI
  CMakeLists.txt         the single entry point (§2.3 option (a))
  third_party/pjproject  vendored, complete, upstream + patches
  third_party/lyra       Exit A only (§2.4.4): vendored, complete, with its model files and
                         its Bazel closure pinned to commit hashes. On Exit B the directory
                         does not exist and ADR-008 says why
  third_party/{openssl,opus,libvpx}
                         vendored under the same rule — a downloaded tarball is a prebuilt
                         dependency with extra steps
  patches/               every local change, numbered, applied in order (N-7)
:pjsip:api               NO LONGER CHECKED IN (N-13). Either a module fed by the SWIG generator
                         task, or folded into :pjsip's generated source set — DECIDE here.
                         Generated FROM third_party/pjproject by the same build that produces
                         the .so, so the JNI symbol names cannot drift from the binary
```

Everything above `:data:sip` is untouched by this. That is the seam doing its job: the whole
sourcing model changes and `:domain` does not know.

**The four rules that hold it together**, all machine-checked:

1. `:domain` imports nothing from `android.*` or `androidx.*` — enforced structurally by the
   JVM plugin *and* by a source rule, because a transitive classpath entry defeats the first.
2. No `org.pjsip.*` import above `:data:sip`.
3. `:feature:*` may not reach into `:data:*`.
4. Every rule is proven to actually fire, by running it against deliberately violating
   fixtures under `test/arch/src/test/resources/violations/`. **A rule that never fires is
   worse than no rule: it reads like protection while providing none.**

**Two rules §2 adds**, each with its violating fixture, because rule 4 applies to them too:

5. **No prebuilt native binary in the tree.** No `.aar` and no `.so` may be committed, and
   no build file may reference one that is not a build output of `:pjsip` (N-1). The
   fixture is a directory containing a stub `libfoo.so` and a `flatDir`/`files(...)`
   reference to it; the rule must fail on both.
6. **Vendored source is only changed through `patches/`** (N-7). The check is the tree hash
   against upstream-plus-patches, and the fixture is a vendored file with an unrecorded
   edit.

Any structure you propose must extend this, and must come with its enforcing rule *and* its
violating fixture. A convention documented in prose is a convention that decays.

**Within a module: package by feature, not by layer.** `:domain` already does it —
`call/`, `engine/`, `model/`, `registration/`, `recording/`, `repository/`, `usecase/`,
`contacts/`. Not `interfaces/`, `impls/`, `utils/`. **DECIDE** and document: default
visibility is `internal`; `public` requires a reason; `:domain` public API carries KDoc.

**Vendored trees are exempt from the house style, and only from that.** `third_party/` is
upstream's code in upstream's conventions — do not reformat it, do not lint it, do not
rename anything in it. Exclude it from ktlint/detekt/spotless explicitly rather than by
accident, and say so in `docs/module-structure.md`. A reformatted vendored tree makes every
future upstream diff unreadable, which is the one thing vendoring must not cost you.

**Naming.** A type name states what it *is*; a function name states what it *does* or what
it *returns*, never both. No `Manager`, `Helper`, `Util`, or `Handler` unless the name is
genuinely the best available — each one is usually a class that has not decided what it is.

---

## 8. System design — beyond the APK

Write `docs/system-design.md`. The APK is one node. Design the system it lives in.

### 8.1 Topology

Draw it: client → TLS → registrar/proxy → media (SRTP) → MCU (`mod_conference`, ADR-003) →
push gateway (ESL-driven, RFC 8599 client parameters, ADR-004) → FCM → client. Mark each
edge with its protocol, its transport security, and who operates it.

State the **contract** with each component you do not build: what the registrar expects in
`Contact`, what the push gateway sends (and what it must **not** send — ADR-004's position
is that a push carrying the caller is a caller disclosed to Google), what the MCU's dial-in
URI scheme is, and what happens when each is unavailable.

**The build pipeline is part of the topology now.** Draw it too, and mark the trust
boundaries: where source enters the repository (a human-reviewed commit, not a build-time
download), where the toolchain comes from (a pinned NDK), where artifacts are cached, and
who can publish. Under §2 the supply chain for the most sensitive component in the system —
the one that holds SIP credentials and carries media — is a diff in this repository. Say
that in the document; it is the strongest security claim the project has.

### 8.2 Capacity — SHOW YOUR WORKING

Do the arithmetic, do not assert conclusions. Required:

- **Bandwidth per call, per codec, both directions**, including IP/UDP/RTP overhead — 40
  bytes per packet, so at 20 ms ptime that is 50 pps and 16 kbps of pure header per stream.
  Tabulate PCMU/PCMA, G.722, Opus at its configured rate, Lyra at 3.2/6/9.2 kbps, and VP8
  at its configured range. The Lyra row is the whole argument for Lyra: put a number on it,
  and note the header overhead is *larger than the payload* at 3.2 kbps — which is a real
  result about ptime, not a reason to drop the codec. **Compute the Lyra row under either
  exit of the §2.4 gate**: on Exit B it is the arithmetic that says what reopening the
  question would be worth, and a table with the row missing cannot make that case.
- **Concurrency ceiling of the deployed server.** **VERIFY** the current limit rather than
  inheriting a remembered figure, then state what the client does when it is hit — and what
  the user sees. A ceiling with no client-side behaviour attached is a ceiling that produces
  a mystery.
- **Registration load.** N clients × re-register interval = REGISTERs/second at steady
  state, and the burst after a registrar restart. This is the calculation that justifies
  §6.2's backoff parameters; if the two do not agree, one of them is wrong.
- **Conference scaling.** Mixed audio at the MCU is CPU per participant; N video streams is
  bandwidth per participant. State which the deployment is limited by.
- **Build capacity.** Wall-clock per ABI, total per commit, and the cache hit rate the
  scheme in §2.3 actually achieves. A native build nobody can afford to run is a native
  build people route around, and routing around it is how a prebuilt binary comes back.

### 8.3 Failure domains

For each: blast radius, detection, client behaviour, user-visible behaviour, recovery time.

Registrar restart · proxy unreachable · push gateway down · MCU at capacity · TLS
certificate expiry or rotation · NAT binding timeout · carrier handover · Doze and App
Standby · the native library missing or ABI-mismatched · a codec negotiated but not
decodable · **the native build broken on `main`** (blast radius: every build, including the
one you would use to ship a fix) · **a vendored dependency with a published CVE** (detection
is the part with no default answer: nothing tells you, because nothing is watching a
directory in your repository — say who watches it and how).

**Single points of failure get named.** If the push gateway is one, say so in the document
rather than discovering it at 3am.

### 8.4 Observability

- **The one metric that proves the system works.** Pick it, defend it, and instrument it.
  Call setup success rate is the usual answer; registration uptime is the usual second.
- **What is logged at each level**, and — this is the constrained part — **what is never
  logged in a release build**: credentials, `Authorization` headers, full SIP URIs,
  phone numbers, SDP that identifies the user. `docs/security.md` holds the policy;
  extend it rather than restating it.
- **The build's own observability.** The codec audit (§2.5) at INFO once per start; the
  library inventory per ABI; the vendored versions in the about screen or the log header,
  so a bug report says which stack it came from. Owning the build means a support question
  is now "which commit of pjproject" and the answer must be in the report.
- **DECIDE:** is there any remote telemetry at all? The existing scope says no analytics SDK
  and no third-party crash reporter without explicit approval, because this app handles call
  metadata and credentials. If that changes, it is an ADR, not a dependency line.

### 8.5 Rollout

Staged rollout, a kill switch for anything new that touches media, and **server-driven codec
priorities** so a bad codec decision is a config change rather than an app release. State
the rollback for each phase.

Two blockers to carry in every phase report until they are closed:

- **ADR-002, the licence.** GPLv2 working assumption, commercial licence UNRESOLVED. §2.7
  explains why vendoring makes it more urgent rather than less.
- **Lyra's status.** Carry the §2.4 gate outcome in every report until it is decided, then
  carry what it decided. On Exit B, ADR-008 is the answer and there is no toggle to ship. On
  Exit A the blocker is the second endpoint: the deployed FreeSWITCH cannot negotiate Lyra,
  and shipping a toggle a user can turn on and never benefit from is worse than shipping no
  toggle. The codec audit is what lets the UI tell the truth in either case.

---

## 9. Non-functional budgets

Absolutes like "zero crashes" cannot be tested and therefore cannot be delivered. Each of
these is a number a test can fail. **DECIDE** any target the stakeholder wants moved.

1. Mouth-to-ear latency, both ends local, with the measurement method stated.
2. CPU during a steady 1:1 Opus call on the reference handset. Measured on hardware for
   `arm64-v8a` and `armeabi-v7a`; `x86_64` ships in no handset, so it is emulator-only and
   labelled as such (see DoD 10). Lyra measured separately, on Exit A only — it is a neural
   codec and will be materially higher.
3. Cold start to dial tone; warm start to answerable incoming call.
4. Memory: no unbounded native growth over 30 minutes of call (deltas at 0/15/30 min), and a
   LeakCanary-clean JVM heap.
5. Native teardown: 50 create/destroy cycles return to within a stated delta of baseline.
6. Battery over one hour idle-registered, compared against the registration-only path.
7. Frame timing on the in-call screen during an active video call — jank measured, not
   eyeballed.
8. APK size delta per ABI for each native dependency. **Measured, not assumed:** Lyra's four
   model files total **~3.6 MB** (`soundstream_encoder.tflite` 1.78 MB, `lyragan.tflite`
   1.48 MB, `quantizer.tflite` 329 KB, `lyra_config.binarypb` 2 bytes — upstream `main`,
   2026-09-09), so they are not the download-size problem they were assumed to be. The row
   to watch is the TFLite runtime linked into `libpjsua2.so`.
9. Crash-free and ANR-free rate over a stated soak duration — a number with a denominator.
10. Test coverage in `:domain` and `:data:*`, with the real figure reported, not a target
    quoted.
11. **Native build wall-clock**, per ABI and total, cold and warm, with the cache hit rate
    (§8.2). A budget nobody can meet is a budget people bypass.
12. **Stripped `.so` size per ABI**, per library — plus the Lyra model payload on Exit A,
    and on Exit B the size the tree did *not* grow by, which is the §2.1 arithmetic paying
    off. The same download-cost conversation as item 8, from the build side.
13. **Reproducibility over the pinned set**: two builds of the same commit, hashes compared.
    Either they match, or the document names exactly what varies and why (N-11). A
    dependency still tracking a branch is not a reproducibility finding — it is an unpinned
    input, and it is fixed before this budget is measured (§2.4.3).

---

## 10. Review rubric — hold yourself to this before reporting

Score each phase against these. A phase that fails any item is not done.

**Design.** Alternatives considered and rejected with reasons · non-goals stated · every
failure mode has a client behaviour · every number has its arithmetic · every claim about
this repo has a `path:line`.

**Code.** Every public type in `:domain` has KDoc · comments explain **why**, never restate
the code · no function needs a scroll to read · errors are values where the contract says so
· no new dependency without a line justifying it · no `!!` on a native handle.

**Concurrency.** Every `suspend` function's main-safety is stated and honoured · every
buffer has a capacity and an overflow policy · no lock held across a JNI call · no pjsua2
call from an unregistered thread · cancellation semantics documented where they surprise.

**Native.** No prebuilt `.aar` or `.so` anywhere in the tree or the build files · every
vendored tree has a row in `docs/native-dependencies.md` with version, licence and reason ·
every local change to vendored source is a numbered patch with a stated reason · every
declared codec is proved registered at runtime, not merely compiled · every ABI produces
its full library set.

**Tests.** Domain logic runs with no device, no server, no network · every state machine
proves its rejections, not just its transitions · every architecture rule is proven to fire
against a violating fixture · a bug fix lands with the test that would have caught it.

**Honesty.** Anything unverified is labelled unverified. "Compiled", "registered",
"negotiated" and "verified on hardware" are four different words and they are not
interchangeable. If CI is red, the report says so and shows the log.

---

## 11. Delivery — design first, in phases, with a report between each

Do not attempt this in one pass. After each phase: push, let CI run, and report what works,
what does not, and what you assumed. Batch the work and push once; do not dispatch a run per
edit, and do not sit watching one.

1. **Read and reconcile.** Read `docs/architecture.md`, `lld.md`, `pjsip-migration.md`,
   `security.md`, `network-recovery.md`, `testing.md` and the arch rules. Report every place
   the documents and the code now disagree. Change nothing yet. This phase's whole output is
   a list of contradictions — several are known to exist, including a `CodecPreferences`
   default naming a codec the binary cannot register, and every document that still tells a
   reader to fetch an AAR by hand.
2. **Vendor, and run the Lyra gate beside it.**
   - **2a — vendor the unconditional four.** pjproject, OpenSSL, Opus and libvpx into
     `third_party/` at exact pinned commits, with their licence files,
     `docs/native-dependencies.md`, and an empty `patches/`. Build nothing yet. The
     deliverable is a tree a reviewer can read and an offline checkout that contains
     everything (N-2, N-10). Record the measured sizes against §2.1's table.
   - **2b — the Lyra gate (§2.4.4), timeboxed to 5 working days**, run in parallel with 2a
     and **decided before phase 3b starts**. Its output is one of two things: Lyra vendored
     and pinned (Exit A), or ADR-008 and no `third_party/lyra` (Exit B). Either is a
     complete phase. An undecided gate at day 5 is Exit B.
3. **Build, in two stages** (§2.8). Do not merge them; stage 3a is a minute and stage 3b is
   an hour per ABI, and conflating them is how the fallback gets restored "just to get CI
   green".
   - **3a — bindings from source.** SWIG against `third_party/pjproject`, plus the five
     hand-written `org.pjsip` classes copied from the vendored tree. Delete the 318
     committed `.java` files, the `:pjsip:api` fallback condition at
     `data/sip/build.gradle.kts:71`, and the AAR path in `pjsip/build.gradle.kts` (N-13,
     N-14). This phase ends when `:data:sip` compiles on a fresh clone with **nothing
     committed under `pjsip/` but source and patches** — and still cannot run, loudly.
   - **3b — the native stack.** The CMake entry point, the NDK toolchain, the per-ABI
     matrix, `config_site.h` generated from the declared feature set, and the GitHub Actions
     job that does all of it with no human input (N-4, N-5, N-6). On Exit A, Lyra's per-ABI
     prefix is built **first** (§2.4.5); on Exit B there is no Lyra step and the feature set
     has no Lyra flag. This phase ends when a green run produces every `.so` for all three
     ABIs from a clean checkout, and when the egress-blocked job of §2.1.2 passes.
     **Nothing downstream starts before it does.**
4. **Prove the codecs.** The startup audit, the library inventory, and an on-device round
   trip for every codec in the declared feature set (N-9) — plus, on Exit A only, the Lyra
   model store and manifest. Report the audit output verbatim — the codec list the running
   library actually registered — rather than a summary of it.
5. **HLD delta.** Extend §4 of `docs/architecture.md`: ADR-007, the amended ADR-006, the
   build-pipeline diagram, the missing sequence diagrams, and the failure-domain view.
6. **DSA dossier.** `docs/data-structures.md` per §6, including the per-stream buffer and
   overflow table, and the backoff arithmetic checked against §8.2's registration load.
7. **Structure.** `docs/module-structure.md` per §7, plus rules 5 and 6 *with* their
   violating fixtures.
8. **System design.** `docs/system-design.md` per §8, with the capacity arithmetic worked.
9. **Implement the deltas** the documents exposed — smallest blast radius first, each with
   its test.
10. **Measure §9** on hardware against the deployed FreeSWITCH (ADR-005 — no local Docker
    server), on `arm64-v8a` and `armeabi-v7a`. Emulator media proves nothing about AEC,
    routing, or battery — which is why the media-quality budgets are **not** reported for
    `x86_64` at all rather than reported from an emulator (DoD 10). `x86_64` is proved by
    build, packaging and a placed call, not by a media measurement.
11. **Harden.** Close the reconciliation list from phase 1, or record each remaining item as
    an explicit open question with an owner.

---

## 12. Definition of done — each item binary

1. All six artifacts in §3 exist, and every claim in them carries a citation, a
   measurement, or an `ASSUMPTION:` label.
2. Every flow in §4 has a sequence diagram.
3. `docs/data-structures.md` gives a complexity bound and a bound-policy for every entry in
   §6.2, and the buffer/overflow table covers every stream crossing the native seam.
4. A debug-build assertion fires on any pjsua2 call from an unregistered thread, proven by a
   test that trips it deliberately.
5. Every architecture rule — existing and new — is proven to fire against a violating
   fixture, and the whole rule set finds zero violations in real source.
6. `:domain` still imports nothing from `android.*`/`androidx.*`, and no `org.pjsip.*`
   import exists above `:data:sip`.
7. The app still runs end-to-end on `FakeSipEngine` with no network and no server.
8. Every new state machine rejects every undocumented `(state, event)` pair, by test.
9. `docs/system-design.md` states the concurrency ceiling, the per-codec bandwidth table
   with its arithmetic, and a named client behaviour for every failure domain in §8.3.
10. Every §9 budget has a measured number recorded **on hardware for `arm64-v8a` and
    `armeabi-v7a`**. `x86_64` ships in no handset: its build, packaging and library-inventory
    budgets are recorded, its media-quality budgets (mouth-to-ear, CPU-in-call, AEC,
    routing, battery) are **not reported for that ABI at all**, and the document says so
    rather than quoting an emulator number as hardware.
11. No credential, `Authorization` header, phone number or full SIP URI appears in a
    release-build logcat capture.
12. Every `DECIDE` in this document is answered in the document that owns it, or listed as
    an open question with an owner and a deadline.
13. ADR-002 (licence) is either resolved or restated as an open release blocker in the final
    report.
14. No document in `docs/` describes a component that no longer exists — including every
    instruction to fetch a prebuilt AAR.
15. **N-1:** no `.aar` or `.so` exists in the tree that `:pjsip` did not build, and the rule
    that enforces it fails on its violating fixture.
16. **N-2, N-3:** the egress-blocked CI job of §2.1.2 runs the native stage to completion
    for every ABI. pjproject, OpenSSL, Opus and libvpx are complete, in-tree, at pinned
    commits — and Lyra is too, with its closure pinned, **or ADR-008 records the gate
    failure and no `third_party/lyra` exists**. Both are a pass; a silently absent Lyra with
    no ADR is not.
17. **N-4, N-5:** one `./gradlew` invocation on a machine carrying the pinned toolchain, and
    one GitHub Actions run, each produce the stack from source with no manual step and no
    human-typed version input. That machine is CI (§13); the requirement is that the
    invocation exists and is complete, not that a laptop runs it.
18. **N-6:** `arm64-v8a`, `armeabi-v7a` and `x86_64` each carry their full expected `.so`
    set, asserted at packaging time.
19. **N-7:** every local change to vendored source is a numbered patch in `pjsip/patches/`
    with a stated reason, and the tree-integrity check passes.
20. **N-9:** the startup codec audit reports every codec in the declared feature set as
    registered, with an on-device round trip recorded for each. On Exit A that includes
    Lyra: its four model files verify against their manifest, an encode/decode round trip is
    recorded, and the peer-negotiation status is stated separately and honestly. On Exit B
    Lyra is not in the declared set, the audit reports it as *not compiled*, and ADR-008 is
    cited — which satisfies this item rather than excusing it.
21. **N-10:** `docs/native-dependencies.md` lists exactly the directories under
    `third_party/`, each with version, commit, licence, reason, patches and size.
22. **N-11:** every native dependency is pinned to a commit hash — **no `branch =` anywhere
    in the vendored build** — and two builds of the same commit are hash-compared, with the
    result (matching, or the precise reason it cannot) recorded beside the pinned input set.
23. **N-13:** `git ls-files pjsip/` returns no `.java` file. The bindings — SWIG output and
    the five hand-written `org.pjsip` classes alike — are generated from
    `third_party/pjproject` on every build, and the SWIG version that generated them is the
    one the `.so` was built beside, by construction rather than by comment.
24. **N-14:** `data/sip/build.gradle.kts` has no `if (aar.exists())` condition and no
    fallback dependency. A build whose native stage produced no `.so` for the target ABI
    **fails**; it does not assemble an APK that raises `UnsatisfiedLinkError` on the first
    call.

---

## 13. How to work

- **Ask before assuming.** If a `DECIDE` is unanswered, ask. An invented config key, header
  name, codec id or configure flag is a production outage, and this codebase has already
  produced one silent instance of exactly that.
- **Ground every claim.** Before naming a symbol, path, flag or macro, confirm it exists —
  by grep here, or by `javap` on the real artifact. Reading the built binary beats memory
  and beats spending a CI run on a question a local file answers. With the source vendored,
  this gets easier, not harder: the answer to "does `--with-lyra` exist" is now a grep of
  `third_party/pjproject/aconfigure.ac` in your own tree.
- **Builds run in CI, not on the developer's machine.** Do not install an NDK, Bazel or a C
  toolchain locally. Owning the build does not mean running it on a laptop; it means the
  repository contains everything the build needs. Verification happens on a handset against
  the real server.
- **Never hand-edit a vendored tree.** Write a patch (§2.6). An edit with no patch is
  invisible at the next bump.
- **Another session may share this working tree.** Re-check the branch and `git status`
  before committing; HEAD may have moved under you.
- **Smallest blast radius first.** If a change has to reach above the `SipEngine` seam,
  that is a signal to re-read the seam, not to widen the diff.
- **Report honestly.** If a phase's tests fail, show the output. If you skipped something,
  name it. Never describe unverified code as working — "implemented", "compiled",
  "registered" and "verified on hardware" are four different claims and only the last one
  ends an argument.
