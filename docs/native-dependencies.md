# Native dependencies — provenance, licences, versions, patches

**Master prompt §2.6 (N-10).** One row per vendored tree: name, upstream URL, exact
version *and commit*, licence, why it is here, every local patch, and size. Plus the
**exempt toolchain** under its own heading (§2.1.1) — not vendored, not built here, and
pinned anyway, because an unpinned tool is a silently different `.so` and defeats N-11.

> **STATUS — `third_party/` exists.** Phase 2a landed on 2026-09-09. Every version, commit
> and licence below is verified against upstream, and every size is **measured** with `du`
> on the vendored tree, not estimated. The tree hashes in
> `pjsip/patches/vendored-tree.sha256` were verified to reproduce **byte-identically from a
> fresh `git worktree` checkout**, which is what makes architecture rule 12 meaningful in
> CI rather than only on the machine that vendored.

---

## 1. The four unconditional trees

| Name | Upstream | Version | Commit | Licence | Why it is here |
|---|---|---|---|---|---|
| **pjproject** | https://github.com/pjsip/pjproject | `2.17` | `5a457451fa2712ba18e12b01738e8ff3af2b26fd` | **GPL-2.0** *or* a Teluu commercial licence — see §4 | The SIP stack itself. Signalling, media, the pjsua2 API the whole of `:data:sip` is written against |
| **OpenSSL** | https://github.com/openssl/openssl | `openssl-3.5.0` | `636dfadc70ce26f2473870570bfd9ec352806b1d` | Apache-2.0 | **TLS transport.** Without it `configure-android` builds a stack with no TLS and does not complain — DoD 13 and `docs/security.md` §Transport both fail silently |
| **Opus** | https://github.com/xiph/opus | `v1.5.2` | `ddbe48383984d56acd9e1ab6a090c54ca6b735a6` | BSD-3-Clause (GitHub reports `NOASSERTION`; the tree's `COPYING` is the 3-clause BSD) | The only wideband audio codec in the build. `CodecPreferences.DEFAULT` lists it first (`domain/…/model/Codecs.kt:78-82`) |
| **libvpx** | https://github.com/webmproject/libvpx | `v1.17.0` | `6df3ec34557879fff673706f4a1d9fbd0f3a6f0e` | BSD-3-Clause | **VP8** — the only video codec both ends can negotiate. The deployed FreeSWITCH offers VP8 and VP9 and no H.264 (`docs/reconciliation.md` A-1b) |

**These are the versions the green build already uses**, not new choices: they are the
`workflow_dispatch` defaults at `.github/workflows/build-pjsip.yml:33-47`, proven by run
`34317978694` (`docs/reconciliation.md` B-7). Vendoring changes *where the source comes
from*, not *which source*.

**A dependency with no answer in the "why" column is removed.** All four have one.

### 1.1 Sizes — measured, and the estimate they replace

**Method:** each tag downloaded as a source tarball, extracted, `du -sh` on the extracted
tree. Then committed to a throwaway git repository and `git gc --aggressive --prune=now`,
to measure what the *pack* actually costs — which is what a clone pays.

| Tree | Extracted | **Vendored (final)** |
|---|---|---|
| pjproject | 66 MB | **57 MB** |
| OpenSSL | 138 MB | **49 MB** |
| libvpx | 26 MB | **20 MB** |
| Opus | 16 MB | **15 MB** |
| **Total working tree** | **246 MB** | **141 MB** — 8,133 files |

**Clone size before and after**, as master prompt §2.1 requires — `.git` measured directly:

```
before phase 2a               .git = 30 MB
after,  vendored + pruned     .git = 87 MB   ◄── actual
(unpruned would have been     .git ≈ 145 MB)
```

**The final figure is 141 MB rather than the 134 MB a first pass measured**, and the
difference is a correction worth keeping: `doc/`, `demos/` and `fuzz/` were pruned from
OpenSSL and then **restored**, because they are in OpenSSL's *unconditional* `SUBDIRS`
line and `Configure` walks into them. 9.6 MB against a build that does not configure is
not a trade. §1.3 is the check that now holds that line.

**The master prompt's cost table is high by roughly a factor of four, and the reason is
worth stating** because it changes the decision. §2.1 quotes GitHub API repository sizes —
pjproject 50.6 MB, OpenSSL 351 MB, libvpx 75.7 MB, Opus 25.7 MB, "**~500 MB** for the four
unconditional trees". Those are **git repository** sizes: they include upstream's entire
history. This project vendors a **tarball extract of one tag**, which carries no history at
all. The correct figure is 246 MB extracted, 115 MB packed — and 26 MB packed once pruned.

**Consequence: the 1 GB threshold in §2.1 is not approached by either option**, so the "ADR
before the commit lands" trigger it describes does not fire on size. ADR-007 is still owed —
for the sourcing change itself — but it is not a size exception.

### 1.2 The prune, specified

**Decision: vendor pruned trees.** Recorded in ADR-007.

**The rule:** remove each tree's **own test suite, its documentation, and its pre-generated
build scratch** — artefacts that are neither compiled into the `.so` nor read by
`configure`. Remove nothing else.

| Tree | Removed | Measured saving | Risk |
|---|---|---|---|
| OpenSSL | `test/` | **89.1 MB** | **Verified safe, and the safety is now asserted.** OpenSSL's root `build.info:5-7` guards `SUBDIRS=test` behind `IF[{- !$disabled{tests} -}]`, and every `./Configure` in this repository passes `no-tests` (`.github/workflows/build-pjsip.yml:285-286`). `tools/vendor/verify-openssl-prune.sh` asserts **both**, because the prune is safe only while both hold |
| OpenSSL | ~~`doc/`, `demos/`, `fuzz/`~~ | — | **Pruned, then RESTORED.** All three are in the **unconditional** `SUBDIRS` line (`build.info:4`), so `Configure` walks into each and reads a `build.info` that would not be there. Read the root `build.info` before adding anything to this list |
| pjproject | `tests/`, `pjsip-apps/src/samples/` | 4.7 MB | Low |
| pjproject | `pjsip-apps/src/pjsua/android/`, `pjsip-apps/src/swig/java/android/app/` | small | Low — **and the reason is not size.** Each ships a committed `gradle-wrapper.jar`, and this repository should not carry a second project's wrapper binary. Rule 11 covers `.aar`/`.so` and would not fire on these, so they are removed deliberately. `pjsip-apps/src/swig/java/Makefile` and `pjsua2.i` are **kept** — they are stage 1 |
| libvpx | `test/`, `build_debug/`, `examples/` | 6.1 MB | Low. `build_debug/` is pre-generated scratch |
| Opus | `doc/`, `tests/` | 0.9 MB | Low |
| — | **Total** | **~105 MB** | |

**What is deliberately NOT pruned, and why each would have been a mistake:**

| Kept | Size | Why |
|---|---|---|
| `pjproject/pjsip-apps/src/swig/` | 4.0 MB | **This is stage 1.** `pjsua2.i` and the Java `Makefile` are what SWIG reads (N-13). Pruning it deletes the bindings |
| `pjproject/third_party/webrtc/` | 1.7 MB | `PJMEDIA_HAS_WEBRTC_AEC=1` is in the green build's compile line. This is the acoustic echo canceller — the difference between a usable speakerphone and feedback |
| `pjproject/third_party/*` generally | 16 MB | pjproject bundles `speex`, `srtp`, `yuv`, `g7221`, `gsm`, `ilbc`, `resample` and builds against them by relative path. This is upstream's own vendoring and it is load-bearing |
| `opus/dnn/` | 10.7 MB | `Makefile.am:14` puts it on the include path. Opus 1.5's LACE/NoLACE enhancement lives here; removing it breaks the build, not just a feature |

**`opus/dnn/` is the near-miss worth recording.** At 10.7 MB it is the second-largest single
prune candidate by eye, and pruning it would have broken the build. It was kept because
`grep` found it on the include path, not because it looked important.

### 1.3 Four checks, because every one of them caught something

**A pruned tree that does not build is worse than an unpruned one**, and every failure in
this class is silent: the tree looks complete on the machine that vendored it, and CI fails
on a fresh checkout with an error nowhere near the cause. So each is asserted rather than
trusted. Three of the four caught a real defect on the first vendoring attempt.

| Check | What it asserts | What it caught |
|---|---|---|
| `tools/vendor/verify-vendored.sh` | Every file on disk under `third_party/` is one git tracks | **348 files silently dropped.** `.gitignore`'s `build/` rule matched `third_party/pjproject/build/` — which is not build output, it is the make-based build system every target includes — and the vendored sample apps' own `.gitignore` files excluded the rest |
| `tools/vendor/verify-openssl-prune.sh` | `build.info` still guards `SUBDIRS=test`, `no-tests` is still passed, and every unconditional `SUBDIR` exists | **`doc/`, `demos/` and `fuzz/` had been pruned** out of the unconditional `SUBDIRS` line |
| `.gitattributes` `third_party/** -text` | Git performs no line-ending conversion in a vendored tree | **188 CRLF files would have been rewritten**, so the committed bytes would not be upstream's bytes and rule 12's hash would differ between the vendoring machine and a fresh checkout |
| Architecture **rule 12** | Each tree matches its recorded content hash | The standing check. Verified to reproduce byte-identically from a fresh `git worktree` checkout |

**Still owed: one green CI run that compiles FROM the vendored trees.** `pjsip/build-native.sh`
carries the TLS/Opus/VPX assertions and the 16 KB alignment assertion, ported flag-for-flag
from the green workflow, and `.github/workflows/native-mandate.yml` runs the whole native
stage with **egress blocked** (§2.1.2). Until that job is green, the honest claim is
*"vendored, verified byte-identical, and built by a script ported from a proven build but
not yet proven itself"*.

---

## 2. Local patches

`pjsip/patches/` holds **no `.patch` files** — no local change to any vendored tree exists
yet. It holds `vendored-tree.sha256`, which is rule 12's manifest.

**The rule (N-7).** Every local change is a **numbered patch file**, applied by the build in
order, never an edit to the vendored tree. Each carries: what it changes, why, and whether
it has been sent upstream.

```
pjsip/patches/
  0001-<short-description>.patch
  0002-<short-description>.patch
  README.md          — the index: number, subject, reason, upstream status
```

**Why files and not edits.** An unrecorded edit is invisible at the next version bump:
somebody bumps pjproject, re-applies the series, and the undocumented fix is silently gone —
a fixed bug returns with no commit that removed it. Architecture **rule 12**
(`docs/module-structure.md` §3.2) is the machine check: the tree on disk must equal
upstream-at-the-recorded-commit plus the patches, in order.

**The escalation threshold, decided in advance** (master prompt §2.9): start with patch
files; **fork `pjsip/pjproject` into an organisation remote and vendor from the fork when
the series exceeds what you would rebase by hand.** A patch series stops scaling the moment
you need a fix that is on upstream `master` but in no release — you cannot `git log` a
tarball. The offline test still passes from a fork; only the provenance improves.

---

## 3. The exempt toolchain — not vendored, not built here

**Master prompt §2.1.1.** N-1 governs **dependencies**, not **tools**. The test that
separates them: **does the artifact ship inside the APK?** A `.so` does; a compiler does
not. Everything that ships is built here from vendored source. Everything that *does the
building* is exempt — and pinned, because an unpinned tool is a silently different `.so`.

| Tool | Pinned version | Where CI gets it | Pinned today? |
|---|---|---|---|
| **Android NDK** | **r27c** | `nttld/setup-ndk@v1` (`.github/workflows/build-pjsip.yml:207`) | **Yes.** r27+ is mandatory for 16 KB page sizes — Android 15 and Play submissions (ADR-006) |
| **SWIG** | — | `apt-get install swig` on the runner | **NO — and this is a defect.** `pjsip/api/build.gradle.kts:25-27` states it outright: "whatever `apt-get install swig` gives the runner". Between 4.2.0 and 4.4.1 the typed vector classes rename `add`/`reserve` to `doAdd`/`doReserve`, and 45 generated files differ. **A runner image bump silently changes the JNI surface.** Fix in phase 3a |
| **JDK** | 17 (AAR job), 21 (APK job) | `actions/setup-java` (`:592`, `:747`) | **Yes**, but **two different versions in one pipeline** — worth a look, not necessarily a defect |
| **autotools** | runner default | Ubuntu image | **No.** Drives `configure-android` |
| **CMake** | runner default | Ubuntu image | **No.** Becomes load-bearing at N-4 |
| **Ninja / Make** | runner default | Ubuntu image | **No** |
| **Python** | runner default | Ubuntu image | **No.** OpenSSL's `Configure` is Perl; Python is used by libvpx tooling |
| **Bazel** | 5.3.2 *if* Lyra ships | Lyra's `.bazelversion` | Gate-dependent — §5 |
| **Gradle wrapper + every Maven dependency** | `gradle/libs.versions.toml` | Maven Central / Google | **Yes.** That supply chain is already governed there, and `settings.gradle.kts:19-38` narrows it to two repositories |

**The honest summary of this table: one tool is pinned, one is a known defect, and five run
at whatever the runner image happens to carry.** N-11 (reproducibility) cannot be measured
over an unpinned toolchain, so **pinning these is a prerequisite for budget 13 of §9, not a
tidy-up after it.**

**SWIG is the urgent one** and it is not a theoretical risk: the `.so` exports
`Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java SWIG generated beside it.
Today a comment asks people to be careful. Generating both halves in one build from one
source tree makes the drift structurally impossible — which is the whole argument for N-13
in one sentence — **but only if the SWIG that generates them is the same one every time.**

---

## 4. Licences

| Dependency | SPDX | Full text at | Obligation |
|---|---|---|---|
| pjproject | `GPL-2.0` or commercial | `third_party/pjproject/COPYING` | **See below. This is the release blocker** |
| OpenSSL | `Apache-2.0` | `third_party/openssl/LICENSE.txt` | Attribution; no copyleft |
| Opus | `BSD-3-Clause` | `third_party/opus/COPYING` | Attribution |
| libvpx | `BSD-3-Clause` | `third_party/libvpx/LICENSE` | Attribution |

**Licence text is copied into the tree, not linked.** A link is a promise about a server
somebody else operates.

### 4.1 ADR-002 got sharper, not softer

The licence position is **GPLv2 working assumption, commercial licence UNRESOLVED**
(`docs/architecture.md:33-36`). Vendoring does not soften that — it sharpens it.

Statically linking GPLv2 code into a closed-source APK you distribute is precisely the case
the GPL addresses. **And the source is now in this repository**, which makes the obligation
visible to anyone who clones it rather than buried in a build step.

If "full control" is meant to include the right to ship this closed-source, that is a
**Teluu commercial licence — a purchase, not a task**, and no amount of build ownership
substitutes for it.

**DECIDE before any store submission.** Carried in every phase report until decided.

---

## 5. Lyra — the §2.4 gate

**Status: criterion 1 only, by decision.** The gate is not being run in full; the NDK
conflict is being tested first because it is the fast signal and everything else is wasted
if it fails.

**`third_party/lyra` does not exist and is not planned unless criterion 1 passes.**

### 5.1 Verified upstream state — 2026-09-09, from the GitHub API

| Fact | Value | Source |
|---|---|---|
| `.bazelversion` | **5.3.2** | `google/lyra/.bazelversion`, read directly |
| `com_google_glog` | **`branch = "master"` — unpinned, floats** | `WORKSPACE:94-96` |
| `com_github_gflags_gflags` | **`branch = "android_linking_fix"` — unpinned, and an individual's fork** | `WORKSPACE:100-102` |
| `org_tensorflow` | `commit = "d5b57ca93e506df258271ea00fc29cf98383a374"` (= v2.11.0) | `WORKSPACE:169-172` |
| `com_google_protobuf` | `tag = "v3.15.4"` | `WORKSPACE:27-29` |
| `com_google_absl` | `tag = "20211102.0"` | `WORKSPACE:37-39` |
| `com_google_audio_dsp` | `commit = "14a45c5a…"`, with the comment *"There are no tags for this repo, we are synced to bleeding edge"* | `WORKSPACE:69-73` |
| `fft2d` | a URL, one academic host, no mirror | `WORKSPACE:84-87` |
| Repository size | 19 MB | GitHub API |

**Two dependencies float, and N-11 cannot be measured until they are pinned.** That is not a
reproducibility *finding* — it is an unpinned *input*, and master prompt §2.4.3 is explicit
that pinning glog and the gflags fork to commit hashes, and mirroring `fft2d` into this
repository, is **the first act of the spike, before any build attempt**.

### 5.2 Criterion 1, and why it is checked first

> `liblyra` and its closure build for `arm64-v8a` with **NDK r27+**, and the resulting
> `.so` files are 16 KB aligned.

**The conflict, stated precisely.** Lyra's README pins NDK **r21.4.7075529**. This project
mandates **r27c** for 16 KB page alignment (ADR-006, and Play's requirement for Android
15+). Lyra at r21 and pjproject at r27 is a C++17 libc++ ABI mismatch across the `-llyra`
link, and an r21-built `.so` is not 16 KB aligned. **Either Lyra's build is ported to a
modern NDK, or the two halves cannot ship in one APK.**

**The specific unknown to test:** the NDK ceiling of Bazel 5.3.2's
`android_ndk_repository`. **ASSUMPTION: it is well below r27.** This has not been tested and
it is exactly what criterion 1 exists to settle. It is a CI question, not a laptop one.

### 5.3 The model files, if the gate ever passes

Four files, **~3.6 MB total**.

**ASSUMPTION — these four sizes are the one set of numbers in this document I did not
verify myself.** They are taken from `docs/master-engineering-prompt.md` §9.8, which
attributes them to upstream `main` on 2026-09-09. GitHub's tree and contents APIs both
returned 404 for `google/lyra` when I tried to confirm them, so they are carried forward
labelled rather than restated as fact. **Confirm by `du` on the vendored tree if the gate
ever passes** — the total matters only for §9 budget 8, and the *presence and integrity* of
the files matters far more than their size.

| File | Size |
|---|---|
| `soundstream_encoder.tflite` | 1.78 MB |
| `lyragan.tflite` | 1.48 MB |
| `quantizer.tflite` | 329 KB |
| `lyra_config.binarypb` | 2 bytes |

**Said out loud, per master prompt §2.4.3 item 4: these are prebuilt binaries this
repository cannot compile.** They are trained weights — data, not code. N-3 deliberately
requires vendoring them, and N-1's absolutism must not be read as covering them. **They are
the one exception, and it is written down rather than assumed.**

At 3.6 MB they are a **correctness** problem, not a download-size one: without them the
codec **registers and then fails when a stream opens**, which is worse than not having it,
because it advertises a capability it cannot deliver. Ship a content-hash manifest and
verify at extraction; a truncated asset must be a loud startup failure, not a dead call.

### 5.4 Even on Exit A, there is no peer

The deployed FreeSWITCH offers **PCMU, PCMA, G.729, G.723.1, AMR, Speex, VP8, VP9** and
nothing else (`fs_cli -x "show codec"`, 2026-09-09 — `docs/reconciliation.md` A-1b).

So on Exit A the honest deliverable is *"Lyra compiled from vendored source, registered,
model files verified, and provably selectable, with no deployed peer that accepts it"* —
which is exactly what N-9 asks for, and it is a real result. **What it is not is "Lyra
calling works." Do not report the former as the latter.**

**And note the same sentence is true of Opus today**, which already ships. The codec audit
(`docs/data-structures.md` §4) is what lets the app say so.

---

## 6. The N-10 CI check

**PROPOSED.** A check that this file lists **exactly** the directories under `third_party/`
— no more, no fewer.

**Two failure directions, and both matter.** A directory with no row is an undocumented
dependency. A row with no directory is a document describing something that no longer
exists, which is DoD 14.

**The check must not expect the §3 toolchain rows to be directories.** They are listed
under their own heading precisely because they are *not* in `third_party/`, and a naive
implementation that scans every table in this file will fail on them. That is the one
subtlety in an otherwise trivial check, and it is written here so it is not rediscovered.
