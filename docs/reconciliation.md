# Reconciliation — where the documents and the code disagree

**Phase 1 of `docs/master-engineering-prompt.md` §11.** Its whole output is this list.
Nothing in the tree was changed to produce it.

Every row below was read out of the working tree on **2026-09-09** at commit `735dc98`.
Each carries a `path:line` citation, a statement of which side is currently true, and the
phase that closes it. Rows marked **ASSUMPTION** are ones I could not settle from source.

Three kinds of disagreement are separated, because they close differently:

- **A. Code contradicts code.** Two places in the build describe incompatible worlds. These
  are defects today, independent of the native mandate.
- **B. Documents contradict code.** A document describes a component or a procedure that
  the tree does not have, or has differently.
- **C. The mandate contradicts both.** §2 of the master prompt makes a currently-true fact
  false on purpose. These are not defects — they are the work — but they are listed so that
  no later phase mistakes one for a bug it should "fix" back.

---

## A. Code contradicts code

### A-1 · `CodecPreferences.DEFAULT` offers a codec the binary is configured not to contain

`CodecPreferences.DEFAULT` lists `VideoCodec.H264`
(`domain/src/main/kotlin/com/whatsappv2/domain/model/Codecs.kt:78-82`). Every
`config_site.h` the native build writes sets `PJMEDIA_HAS_OPENH264_CODEC 0` — both the
bindings job (`.github/workflows/build-pjsip.yml:99`) and the per-ABI build job
(`.github/workflows/build-pjsip.yml:392`).

**Which is true:** the workflow. H264 is not compiled, so it is never registered, so
`applyPriorities` never sees it — the preference is silently dropped, because the loop
iterates the *registered* codecs and asks which preference matches, not the other way
round (`data/sip/src/main/kotlin/com/whatsappv2/data/sip/registration/stack/RealPjsipCoreGateway.kt:889-901`).

`docs/pjsip-migration.md:422-440` (P-9) already records this honestly and calls it
"outstanding — decisions, not defects". It is still open, and the master prompt's §2.5
changes what closing it looks like: the mismatch becomes a **reported** value rather than a
log line, via the codec audit.

**Closed by:** phase 4 (the codec audit makes it visible as a value), then a product
decision — either OpenH264 enters the declared feature set (N-8) or H264 leaves
`DEFAULT`. **DECIDE, unanswered.**

### A-1b · Every wideband codec the app prefers is one the deployed server cannot offer

**Measured against the running FreeSWITCH on 2026-09-09**, not recalled:
`fs_cli -x "show codec"` returns 13 entries. The audio codecs among them are **G.711 alaw,
G.711 ulaw, G.729, G.723.1, AMR (two packings), Speex** and raw L16; the video codecs are
**VP8 and VP9**.

`CodecPreferences.DEFAULT` is
`audio = [OPUS, G722, PCMU, PCMA]`, `video = [VP8, H264]`
(`domain/src/main/kotlin/com/whatsappv2/domain/model/Codecs.kt:78-82`).

Intersect the two:

| Preference | Compiled into the APK? | Offered by the server? | Can negotiate? |
|---|---|---|---|
| `OPUS` | **Yes** — `PJMEDIA_HAS_OPUS_CODEC 1`, Opus 1.5.2 cross-compiled (B-7) | **No** — `mod_opus` is configured in `modules.conf.xml` but its `.so` is not installed | **No** |
| `G722` | Yes — pjmedia builds G.722 unconditionally | **No** | **No** |
| `PCMU` | Yes | Yes (`CORE_PCM_MODULE`) | **Yes** |
| `PCMA` | Yes | Yes (`CORE_PCM_MODULE`) | **Yes** |
| `VP8` | Yes — libvpx 1.17.0 cross-compiled, `PJMEDIA_HAS_VPX_CODEC 1` | Yes (`CORE_VPX_MODULE`) | **Yes** |
| `H264` | **No** — `PJMEDIA_HAS_OPENH264_CODEC 0` (A-1) | **No** | **No** |
| `LYRA` | **No** — `-DPJMEDIA_HAS_LYRA_CODEC=0` in the build (B-7) | **No** | **No** |

**So every call to this server is narrowband G.711.** The app compiles, registers and
prioritises a wideband codec that has no peer, and the one thing that would tell anyone is
a log line.

**Which is true:** both sides, separately. Nothing is broken; the two ends simply do not
overlap above G.711. This is not a bug report — it is the exact failure mode master prompt
§2.5 invents a vocabulary for, and the reason the codec audit's reason enum needs
***usable but no peer accepts it*** as a first-class value rather than a footnote about
Lyra.

**Consequence for the Lyra argument, and it cuts both ways.** §2.4.1 justifies Lyra as
"an order of magnitude under Opus". On this deployment the comparison is not Lyra-vs-Opus,
it is Lyra-vs-**G.711 at 64 kbps** — which makes the bandwidth case *stronger*, and the
"no peer accepts it" objection *identical to the one Opus already has and ships with
anyway*. Phase 8's bandwidth table must be computed against G.711, not Opus.

**ASSUMPTION:** the FreeSWITCH I queried is the ADR-005 deployment
(`docs/architecture.md:181-186`), not a second instance. `fs_cli` answered on this
machine; whether that is the same host the instrumented tests point at is a
`sip.test.host` question (`data/sip/build.gradle.kts:102-105`) I cannot settle from here.
**Confirm before phase 8 quotes these numbers.**

**Closed by:** phase 4 (the audit reports it), phase 8 (the bandwidth arithmetic uses it),
and a server-side decision — installing `mod_opus` is a one-module change and would make
the app's existing, already-compiled Opus support live. **DECIDE, unanswered.**

### A-2 · The one architecture rule the tree cannot currently satisfy is the one that is not written

`:pjsip` publishes a `.aar` through its `default` configuration
(`pjsip/build.gradle.kts:36-41`) and the file is gitignored (`.gitignore:22`). No
architecture rule forbids a committed `.aar`/`.so` — the rule set has ten rules
(`test/arch/src/test/kotlin/com/whatsappv2/arch/ArchitectureRules.kt`) and none of them is
about native binaries. So the property "no prebuilt binary in the tree" is held today by
`.gitignore` alone, which is a convention, not a check: `git add -f` defeats it silently.

**Which is true:** neither is wrong; the guard is simply absent.

**Closed by:** phase 7 — new rules and their violating fixtures (master prompt §7 rules 5
and 6; they land as rules 11 and 12 here, because 5 and 6 are taken — see B-5).

### A-3 · The fallback that keeps CI green is the same fallback that ships a dead APK

`data/sip/build.gradle.kts:71-73` adds `:pjsip:api` when `pjsip/libs/pjsua2.aar` is
absent. On a fresh clone the AAR is always absent, so **every** build anyone has ever run
from a clean checkout took the fallback. That build compiles, assembles, installs, and
raises `UnsatisfiedLinkError` on the first SIP call — which `pjsip/api/build.gradle.kts:51-56`
and `pjsip/build.gradle.kts:46-56` both say in as many words.

**Which is true:** both, and that is the problem. The condition is load-bearing for CI and
fatal for the artifact, and nothing distinguishes the two uses at build time.

**Closed by:** phase 3a/3b — N-14 deletes the condition; N-13 replaces the fallback's
purpose with generated bindings. This is the single highest-risk change in the programme:
until stage 1 works, the tree does not compile at all (master prompt §2.8).

### A-4 · `settings.gradle.kts` describes the resolution rule that `data/sip` actually implements — in the wrong module

`settings.gradle.kts:57-59` says "the AAR wins whenever it exists". The condition that
makes that true is not in `settings.gradle.kts`; it is in
`data/sip/build.gradle.kts:71`. A reader who changes the comment has changed nothing, and
a reader who changes the condition leaves the comment lying.

**Closed by:** phase 3a — both go together (N-14).

---

### A-5 · Three of the four streams that cross the seam drop silently, and the LLD says none of them do

`docs/lld.md:111-118` states one policy for the three non-replaying streams: "Dropping
would lose the missed call nobody was collecting for — which is precisely the call that
matters — so the buffer is 64."

The code has **two** policies, and neither module states which it is using.

| Stream | Declared | Emitted by | Actual policy on a full buffer |
|---|---|---|---|
| `incomingCalls` | `PjsipSipEngine.kt:334-338` — `replay=0`, `extraBufferCapacity=64`, **no `onBufferOverflow`** | `incoming.emit(call)` — `:772` | **Suspends** the emitting coroutine. Nothing is lost |
| `endedCalls` | `:347-350`, same shape | `ending?.let(ended::tryEmit)` — `:322` | **Drops, silently.** `tryEmit` returns `false` and the result is discarded |
| `transferEvents` | `:282-285`, same shape | `transfers.tryEmit(mapped)` — `:471`, `:484` | **Drops, silently.** Same |
| `videoRequests` | `:276-279`, same shape | `videoOffers.tryEmit(request)` — `:719` | **Drops, silently.** Same |

`MutableSharedFlow` defaults `onBufferOverflow` to `SUSPEND`, and on a `SUSPEND` flow with
a live subscriber and a full buffer, `tryEmit` returns `false` without emitting. Three call
sites discard that boolean.

**By contrast, the four gateway streams below the seam are explicit and correct.**
`events`, `callEventFlow`, `transferEventFlow` and `conferenceEventFlow` all declare
`onBufferOverflow = BufferOverflow.DROP_OLDEST`
(`RealPjsipCoreGateway.kt:114-148`) with `extraBufferCapacity = EVENT_BUFFER` = **64**
(`:1535`). That is the right choice *there* — the emitter is a pjsua2 worker thread and
suspending it stops the whole stack — and it is stated rather than inherited.

**Which is true:** the code. The document describes an intent the seam's upper half does
not implement.

**Why it matters, concretely.** `endedCalls` writes one call-log row per emission
(`docs/lld.md:114-116`). A dropped emission is a **call that happened and is not in the
history** — and the drop is invisible, because nothing checks the return. 64 is a large
buffer and a phone will rarely fill it, which is exactly what makes this the kind of defect
that surfaces once, in the field, with no way to reproduce it.

**This is master prompt §6.1 in one example:** *"Every buffer has a capacity and an
overflow policy, chosen per stream… DECIDE and document the policy per stream in a table."*
Today the capacity is chosen deliberately and the policy is inherited by accident.

**Closed by:** phase 6 (`docs/data-structures.md` carries the table and names the intended
policy per stream), then phase 9 (make each site match its stated policy — a `tryEmit`
whose `false` is a logged, counted event, or an explicit `DROP_OLDEST`, or a suspending
`emit`; the choice differs per stream and that is the point).

---

## B. Documents contradict code

### B-1 · `docs/pjsip-migration.md` P-2 still instructs a human to fetch and place a binary

`docs/pjsip-migration.md:251-274` (P-2, "Assemble a consumable AAR") and
`pjsip/build.gradle.kts:52-54` both tell the reader to take `pjsua2-aar-<version>` from the
workflow artifact and drop it at `pjsip/libs/pjsua2.aar`.

**Which is true:** the code — that genuinely is the only way to get a runnable APK today.

**Which the mandate makes false:** all of it. N-5 forbids exactly this step by name, and
DoD 14 fails on any document that still describes it.

**Closed by:** phase 3b for the code, phase 5 for the document.

### B-2 · `docs/architecture.md` ADR-006 records "vendored blobs are not reproducible" as a *rejection*

ADR-006's decision paragraph (`docs/architecture.md:224-228`) rejects option 3 —
"vendor prebuilt `.so` files into the repository" — on reproducibility grounds, and chooses
"build pjproject in CI from source".

**This is not the contradiction it looks like.** ADR-006 rejects vendored **binaries**; the
master prompt mandates vendored **source**. Those are opposite decisions about different
things, and ADR-006's reasoning supports the mandate rather than conflicting with it. What
*is* stale is ADR-006's implicit answer to "where does the source live" — a tarball fetched
at build time — which N-2 replaces.

**Note for phase 5:** the master prompt cites this paragraph as `docs/architecture.md:218`.
Line 218 is the ADR-006 heading; the sourcing paragraph is 224-228. Amend the real lines.

**Closed by:** phase 5 — ADR-006 amended in place with a dated note pointing at ADR-007,
not rewritten (master prompt §4.1).

### B-3 · Six documents describe a `:pjsip:api` module whose contents the mandate deletes

`pjsip/api/build.gradle.kts:1-45`, `docs/pjsip-migration.md:355-372`,
`settings.gradle.kts:57-59` and `pjsip/build.gradle.kts:24-33` all explain, correctly and
at length, why 318 Java files are checked in. `git ls-files pjsip/` returns 320 files, 318
of them `.java` — the count is exact.

**Which is true:** the documents, precisely, today.

**Which the mandate makes false:** N-13. The reasoning in those documents is not wrong; its
*premise* is removed ("this repository does not contain pjproject's source"). Phase 5 must
record why the reasoning stopped applying, not delete it as though it had been a mistake.

**Closed by:** phase 3a (the files), phase 5 and 7 (the documents).

### B-4 · `docs/lld.md` calls the seam a 342-line contract; it is 368 lines

`docs/architecture.md:275` says "≈985 lines behind a 342-line contract".
`domain/src/main/kotlin/com/whatsappv2/domain/engine/SipEngine.kt` is **368** lines today.
The master prompt inherits the 342 figure (§1 principle 2).

**Which is true:** the file. Low stakes, but it is a number in a design document with no
method beside it, which is the class of claim §3 of the master prompt exists to stop.

**Closed by:** phase 5. Restate as a measurement with its date, or drop the number.

### B-5 · The master prompt's §7 says "four rules"; there are ten

`test/arch/src/test/kotlin/com/whatsappv2/arch/ArchitectureRules.kt` declares ten rules:
domain-has-no-Android (`:114`), SIP-SDK-stays-in-`:data:sip` (`:144`),
features-do-not-depend-on-data (`:165`), repositories-declared-in-domain (`:180`),
forbidden-concurrency-APIs (`:204`), ViewModels-expose-immutable-state (`:223`),
design-system-components-are-previewed (`:247`), styling-stays-in-the-design-system
(`:265`), contact-data-stays-on-the-device (`:345`), call-state-not-restored-from-SavedState
(`:388`).

**Consequence, and it is not cosmetic:** the master prompt's "two rules §2 adds" are
numbered **5** and **6**, and both numbers are already taken — rule 5 is the
forbidden-concurrency rule, rule 6 is the ViewModel rule. Adding them at those numbers
would renumber four existing rules and silently invalidate every citation to them.

**Resolution taken:** the new rules land as **11** (no prebuilt native binary) and **12**
(vendored source changes only through `patches/`). Documented in
`docs/module-structure.md` when phase 7 writes it.

### B-6 · `docs/network-recovery.md` publishes a backoff trace from the test's parameters, not production's

`docs/network-recovery.md:44-58` shows a 60s → 960s doubling sequence, and `:62-64` says
so explicitly: "The 60-second base is the test's… production starts at two seconds."

**Which is true:** both, and the document is honest about it. The disagreement is that the
*visible* artifact — the trace a reader skims — is not the shipped behaviour.
`RegistrationBackoff.DEFAULT_BASE_DELAY` is `2.seconds`, `DEFAULT_CEILING` is
`1_800.seconds`, `DEFAULT_SERVER_JITTER` is `10.seconds`, `MINIMUM_DELAY` is `1.seconds`
(`domain/src/main/kotlin/com/whatsappv2/domain/registration/RegistrationBackoff.kt:100-110`).

**Which the mandate makes insufficient:** master prompt §6.2 requires the backoff
parameters to be *justified against the deployed server's concurrent-session ceiling*, with
the arithmetic shown. No document does that arithmetic today.

**Closed by:** phase 6 (`docs/data-structures.md`) and phase 8 (`docs/system-design.md`
§8.2 registration load). The two must agree or one of them is wrong.

### B-7 · The native workflow HAS completed — four green runs, and three documents still say it never has

**This is the largest finding in phase 1, and it inverts the premise several later sections
are built on.**

`.github/workflows/build-pjsip.yml:12-14` says "STATUS: UNVERIFIED. This has still never
completed a run". `docs/architecture.md:295-299` (ADR-006 sequencing step 1) says the same.
`docs/pjsip-migration.md:240` marks P-1 "🔴 blocks the build — the only thing left".

**All three are false.** `gh run list --workflow=build-pjsip.yml` on 2026-09-09 returns
four `completed success` runs on branch `fix/media-controls-and-codecs`, the most recent
being run **34317978694** (7m18s, 2026-09-09T06:11:28Z), with every job green:

| Job | Duration |
|---|---|
| Generate the pjsua2 Java API | 15s |
| pjproject 2.17 · arm64-v8a | 2m51s |
| pjproject 2.17 · x86_64 | 3m23s |
| pjproject 2.17 · armeabi-v7a | 2m46s |
| Assemble pjsua2.aar | 1m20s |
| Build the app APK | 2m21s |

It produced six artifacts: `pjsua2-aar-2.17` (19 MB), `app-debug-apk-pjsip-2.17` (38 MB),
and a per-ABI `pjsip-2.17-<abi>` (9-10 MB each). The build's own assertions passed — the
arm64 log shows `checking VPX usability... yes`, the SSL/Opus configure summary group, and
the 16 KB LOAD-segment assertion running to completion rather than being skipped. The NDK
resolved to `/opt/hostedtoolcache/ndk/r27c/x64` — **r27c**, which satisfies the 16 KB
requirement ADR-006 records.

**Which is true:** the CI history. The three documents are stale by roughly one day.

**Three consequences, and the third is the important one.**

1. **P-1 is not the blocker it is marked as.** `docs/pjsip-migration.md:240` needs
   re-marking, and ADR-006's sequencing step 1 needs a dated amendment.

2. **The build is confirmed to have no Lyra.** The compile line in the arm64 log carries
   `-DPJMEDIA_HAS_LYRA_CODEC=0` explicitly. This is the verified baseline for C-7, from
   the binary's own build rather than from upstream's default.

3. **The master prompt's build-time budget is wrong by a factor of about twenty, and the
   cache design that follows from it may be unnecessary.** Master prompt §2.3 says "a full
   pjproject cross-compile is roughly an hour per ABI", and builds a whole content-hash
   cache scheme on top of that number — including a 10 GB GitHub-cache ceiling analysis and
   a "split the key per ABI" contingency. **Measured: 2m46s to 3m23s per ABI, three ABIs in
   parallel, 7m18s end to end including the AAR and the APK.** A seven-minute build does
   not need a cache at all.

   **SHOW YOUR WORKING — what the mandate adds to that 7m18s**, and this is the honest part,
   because the measured build is *not* the mandated one:
   - It fetches four tarballs (C-1) rather than reading a vendored tree — vendoring
     *removes* that time, it does not add it.
   - It does not run SWIG-per-build into `:pjsip` (the bindings job is separate and takes
     **15s**), so N-13 adds ≈15s.
   - It does not build Lyra. On Exit A of the §2.4 gate, a Bazel + TFLite build is added
     per ABI, and *that* is the step with an hour-scale budget — it is the only one.

   **So: on Exit B the entire cache section of §2.3 is solving a problem that does not
   exist, and the honest deliverable is to say so rather than build it.** On Exit A it
   becomes necessary for the Lyra prefix alone. **This is a DECIDE for phase 3b, and it
   should be decided from this measurement rather than from the prompt's estimate.**

**Verified, not assumed, that this proves `main`.** The run's head SHA is `051fe490`, which
`git merge-base --is-ancestor 051fe490 HEAD` confirms is an ancestor of `main`'s current
HEAD (`735dc98`). The workflow file has changed once since — commit `71771e1`, a one-word
comment edit replacing a stack name in line 3. No step, flag or assertion differs. `main`
is green.

### B-8 · `docs/dod-sweep.md` reports item 1 as PASS on a build that cannot place a call

`docs/dod-sweep.md:24` records "`clean build` from a fresh clone — **PASS**", and it is
literally true (`docs/dod-sweep.md:42-48`). But the build it passes is the fallback build
from A-3: it compiles `:data:sip` against `:pjsip:api` and ships no `.so`.

**Which is true:** the sweep, under its own definition. The master prompt narrows the
definition — DoD 24 says a build whose native stage produced no `.so` must **fail**. Under
that definition today's PASS becomes a FAIL, and that is the intended outcome.

**Closed by:** phase 3b, then a re-sweep. Do not "fix" this row by loosening DoD 24.

### B-9 · No document names the exempt toolchain, and the NDK pin lives only in a workflow

The NDK is pinned to `r27c` at `.github/workflows/build-pjsip.yml:207`. Nothing in `docs/`
records it, and nothing pins CMake, Ninja, autotools, SWIG, Python or the JDK for the
native build. `pjsip/api/build.gradle.kts:25-27` is explicit that SWIG's version is
"whatever `apt-get install swig` gives the runner" — an unpinned tool by design.

**Which the mandate makes false:** master prompt §2.1.1 requires every exempt tool pinned
by exact version and listed under its own heading in `docs/native-dependencies.md`, because
an unpinned SWIG or NDK defeats N-11 before it can be measured.

**Closed by:** phase 2a (`docs/native-dependencies.md`) and phase 3a (pin SWIG in CI).

---

## C. The mandate contradicts both, on purpose

Listed so no later phase reverts one thinking it found a bug.

| # | Currently true | Made false by | Where |
|---|---|---|---|
| C-1 | Sources are fetched as tarballs at build time — pjproject, OpenSSL, Opus, libvpx | N-2 | `.github/workflows/build-pjsip.yml:85, 271, 281, 293, 328` |
| C-2 | Versions are `workflow_dispatch` inputs a human types | N-5 | `.github/workflows/build-pjsip.yml:33-47` |
| C-3 | `:pjsip` publishes a prebuilt AAR through `default` | N-1, N-4 | `pjsip/build.gradle.kts:36-41` |
| C-4 | The AAR is gitignored and fetched by hand | N-5 | `.gitignore:22`, `pjsip/build.gradle.kts:46-56` |
| C-5 | `:data:sip` falls back to `:pjsip:api` when the AAR is missing | N-14 | `data/sip/build.gradle.kts:71-73` |
| C-6 | 318 `.java` files are committed under `:pjsip:api` | N-13 | `git ls-files pjsip/` |
| C-7 | `PJMEDIA_HAS_LYRA_CODEC` is not set at all; Lyra is absent from every `config_site.h` | N-3 / N-8, **conditional on the §2.4 gate** | `.github/workflows/build-pjsip.yml:97-102, 391-396` |

---

## What phase 1 did not settle

One question the tree answered after all, and three that need the stakeholder.

**Settled during phase 1:** the native workflow is green on `main` (B-7). That was the
first thing master prompt §2.2 asks to VERIFY, and it is the fact the rest of the
programme's sequencing hangs on. The three below are not answerable from any source I can
read.

1. **The vendoring decision itself.** ~500 MB of third-party source enters git history
   permanently on the unconditional four; that is an irreversible act on a 30 MB
   repository. Master prompt §2.1 sets a 1 GB threshold and calls for an ADR before the
   commit lands — so the ADR is owed *first*, not after.
2. **The Lyra gate (§2.4).** It is a 5-working-day timeboxed spike whose criterion 1 —
   Bazel 5.3.2's `android_ndk_repository` accepting NDK r27+ — is very likely to fail.
   Running it costs a week; Exit B costs an ADR. That trade is the stakeholder's.
3. **The licence, ADR-002.** GPLv2 working assumption, commercial licence UNRESOLVED
   (`docs/architecture.md:33`). Vendoring the source makes the obligation visible to
   anyone who clones the repository, which sharpens it. Carried in every phase report from
   here until it is decided.
