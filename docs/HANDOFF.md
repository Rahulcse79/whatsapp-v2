# Handoff — paste this whole file as the opening message to the next agent

> **Repo:** `whatsapp-v2` · **Branch:** `docs/native-mandate-design` · **Written 2026-09-10.**
> `docs/master-engineering-prompt.md` governs *how* to work. `docs/calling-defects-prompt.md`
> is the corrected spec for the seven calling defects — **read it before anything else**, it
> carries the measured baseline. This file says what is done, what is half-done, what is
> wrong, and what to do next.
>
> **Read §3 first.** Everything in it is now resolved *in code* — but §3.4 is not, and it is
> the one that decides whether any of this is true: **nothing here has been run on a
> handset.** §4 says why the build that would produce the APK cannot run on this Mac, and
> what one command from the user unblocks it.

---

## 0. The one-paragraph state

Two root causes behind the reported calling failures were found by reading a device trace and
probing the server directly, and both are now fixed in code with JVM tests: an endpoint-wide
codec wipe (item 1) and an oversized INVITE (item 2). **Item 2 is complete as of this pass** —
ICE is off by default and a migration turns it off on accounts already saved, which is the 54
bytes the SRTP trim alone did not close (§3.1). Items 5 and 7 are done and tested. Item 3 is
partly done. Item 4 was reframed and not delivered. Item 6 was diagnosed and is not fixable
here. **Lyra is 0% implemented — only a dependency count exists.** §6, the call-history naming
defect, is **done in code with tests**. Two things that would have kept CI red were found and
fixed (§3.5). **Nothing in any of this has been verified on hardware and no APK has been
built** — that is the single largest thing still owed.

---

## 1. Verified facts you can build on — do not re-derive these

All measured on 2026-09-10 against the live setup. Trace read off the handset; server probed
from a laptop on the same network.

| Fact | Value |
|---|---|
| Server | `192.168.80.145`, presents as `iriscloud` (FreeSWITCH derivative, `mod_sofia`) |
| Handset | Zebra TC15, Android 13 (API 33), `arm64-v8a`, USB debugging, IP `192.168.137.140` |
| NAT | handset `192.168.137.140` arrives at the server as `192.168.100.150` |
| Extensions | `7000`–`7005`; `7000` is the handset |
| **UDP 5060** | **works** — SIP `OPTIONS` answered `200 OK` |
| **TCP 5060** | **`ECONNREFUSED`** |
| **TLS 5061** | **closed** |
| **Max SIP payload the path carries** | **1472 bytes.** 1475 gets no answer. 1472+8+20 = 1500 = Ethernet MTU; the path drops IP fragments silently |
| REGISTER round trip | **57 ms** including the 401 digest exchange |
| The app's audio INVITE | **1742 bytes** — 7 retransmits over 32 s, zero responses (Timer B) |
| The app's 781-byte INVITE | got `100 Trying` on the first attempt — this is the control |
| Codecs the library really registers | `opus/48000/2`, `G722/16000`, PCMU, PCMA, GSM, iLBC, AMR-WB, AMR, speex×3, L16×2 |
| Video registry | `VP8/102, H264/99, VP8/103, VP9/106` — **H264 comes from `MediaCodec`, not OpenH264** |
| LeakCanary | 1 leak, **2686 bytes per Telecom bind cycle**, retained by `android.telecom.ConnectionService$1.this$0` — the framework's own binder. Zero orphaned connections from app code |

**Two claims in the old spec were disproved and are already corrected in
`docs/calling-defects-prompt.md` §2.6.** Opus and G.722 *do* register (the audit's log line
was filtered); and "the request never reached anything" is specifically *because it exceeds
the path MTU and there is no TCP to fall back to*.

---

## 2. What is committed and green

Commits `038359e`, `2682eb6`, `c9b1bcf`, then `7936b92`, `d6d4390` and `ca229d1` from the
afternoon pass. All pushed.

- **Item 1 — answered call disconnects. FIXED.** Root cause: `applyPriorities` set priority
  `0` on every registered codec no account preference named. An account saved with
  `audio=[lyra]` matched nothing, so **all** audio codecs went to 0, endpoint-wide and
  persistently. `pjmedia_endpt_create_audio_sdp` stops at the first disabled codec →
  zero-format m-lines → outgoing `m=audio 0 RTP/AVP 0`, and answering produced
  `PJMEDIA_SDPNEG_ENOMEDIA` and a `488` the app sent *itself*.
  Fix: `domain/…/codec/CodecPriorities.kt` — a preference set matching nothing changes no
  priority; one account cannot disable a codec another needs. 9 tests.
- **Item 5 — dialer selection. FIXED**, with the per-call/per-navigation trap covered: it
  survives navigation, is cleared by a *successful* call, and is kept after a refused one.
- **Item 7 — unregistered account. FIXED.** `PlaceCallUseCase` now registers then dials,
  bounded at 5 s (57 ms observed; the bound covers three Timer A retransmissions). New
  `PlaceCallError.NotRegistered`. 5 tests.
- **Item 3 — audit honesty. PARTLY DONE.** Registry now logged verbatim with priorities;
  `NoPeerAccepts` → `ExpectedUnsupportedByServer(source)` carrying its evidence; an ERROR
  fires when every audio codec sits at priority 0. **Not done:** per-codec on-device round
  trips (N-9), and deriving strandedness from real evidence.
- **CI red fixed** (`Unnecessary safe call` under `-Werror`), and `bin/` is now gitignored —
  stale IDE output made the architecture test scan its own violation fixtures and fail every
  rule, locally only.

**Added in the pass of 2026-09-10 (afternoon):**

- **Item 2 finished** — ICE off by default, plus the account-store migration that turns it off
  on rows already saved. §3.1.
- **The call-history naming defect** — resolved at page load, with the extension rather than a
  URI as the last resort. §6.
- **`:data:sip:detekt` and `:test:arch:test` un-broken** — both were red at `48cf6d6`. §3.5.
- **`AccountConfigFactory.kt`** — the account-config translation, out of a gateway that was
  over `LargeClass`. Compiled and tested locally using the §4 workaround, which is how the
  one bug in it was caught before it was pushed.

**Verified green locally, 2026-09-10 15:56, all under `-PwarningsAsErrors=true`:**

| Module | Tests |
|---|---|
| `:domain` | 466 |
| `:data:sip` | 199 (via the §4 workaround) |
| `:feature:accounts` | 77 |
| `:feature:calls` | 62 |
| `:data:account` | 59 |
| `:core:common` | 51 |
| `:test:arch` | 35 |
| `:feature:dialer` | 34 |
| `:feature:history` | 23 |
| `:feature:settings` | 11 |
| `:data:contacts` | 10 |
| `:data:calllog` | 8 |

**0 failures, 0 errors.** `./gradlew detekt` is green across every module. `:app:test` is the
one thing not run: it needs the native libraries, which need the toolchain §4 describes.

---

## 3. Defects found in the previous agent's own work — all resolved except §3.4

### 3.1 ~~Item 2 is NOT fixed~~ — RESOLVED. ICE is off, and the migration turns it off too.

The SRTP trim saves 216 bytes, not the 232 that was estimated, so it left the INVITE at
**1526 — 54 bytes over** the 1472 the path carries. ICE is the 198 bytes that close the gap,
and ICE had not been changed.

Three changes, all with tests:

- `NatPolicy.DEFAULT.iceEnabled` is now **false** (`domain/…/model/NatPolicy.kt`), and
  `SipAccountDraft` takes its opening value from it rather than repeating a literal.
- **Account store version 1 → 2** turns `ice_enabled` off on every row already saved
  (`data/account/…/db/SipAccountDatabase.kt`). Without it the handset that reported the
  defect keeps its old row and the fix does nothing there. `SipAccountMigrationTest` builds a
  real version 1 database from the committed `1.json` — table, indices and identity hash —
  and asserts the migrated result. `2.json` is committed.
- The gateway's own comments carried the **estimated** 116/76 byte figures; they now carry
  the measured 108/84, and the account-config assembly moved out to `AccountConfigFactory.kt`
  (see §3.5).

Arithmetic, all measured: `1742 − 216 (two AES_256 lines) − 198 (ICE) = 1328`, which is 144
bytes under the hard limit and still 56 short of RFC 3261 §18.1.1's 200-byte headroom. Both
numbers are in `SdpBudgetTest` and in `docs/architecture.md` §4.11.1.

**A fact worth carrying forward:** `SipAccount.stunServer` is collected, validated and
persisted, and **nothing below `:domain` reads it** — no STUN server ever reaches `UaConfig`.
So ICE could only ever gather host candidates, which is why turning it off costs nothing.
Wiring STUN up is a separate change with its own measurement, and nobody has done it.

**Still owed: re-measure on the handset.** The arithmetic says 1328. Only a call says so.

### 3.2 ~~Uncommitted, never-run changes~~ — RESOLVED, committed and green

The constant corrections (`AES_256` 116→**108**, `AES_128` 76→**84**) and the rewritten
tests are committed. **9 tests ran and passed.** The working tree is clean; nothing is
outstanding here. The corrected arithmetic is what §3.1 above is based on.

### 3.3 ~~`SdpBudget` is inert~~ — RESOLVED by saying so plainly

It is a documented constant, not a guard, and its KDoc now says that in those words: nothing
in Kotlin ever sees the datagram PJSIP builds, so no code here can refuse an oversized one.
What it also now names is the three settings that *do* hold the offer down, each pinned by a
test — ICE off by default (`NatPolicyTest`), ICE off on rows already saved
(`SipAccountMigrationTest`), and two crypto suites rather than four
(`AccountConfigFactory.OFFERED_CRYPTO_SUITES`).

**And the limit of that, stated in the same place:** a change that adds bytes some *other*
way — a codec, an `fmtp` line, a second `m=` line — fails no test. Only a call on hardware
catches it.

### 3.4 Nothing has been verified on hardware

The spec's own rule is *"a calling defect is not fixed until a call has been placed, answered
and heard."* No APK was built, no call placed. Every item above is "compiles and passes JVM
tests" and nothing more.

### 3.5 Two things were keeping CI red, and they were not the code under review

Both were red **at `48cf6d6`**, before this pass touched anything, and both are fixed. The
previous handoff's "verified green locally: `:test:arch:test`, detekt" was wrong on both
counts — the detekt run it describes cannot have included `:data:sip`.

- **`:data:sip:detekt` failed with four findings.** `RealPjsipCoreGateway` went over
  `LargeClass` when the SRTP and NAT work added two hundred lines to a class that was
  already at the line, and `toAccountConfig` went over both `CyclomaticComplexMethod` and
  `NestedBlockDepth`. Fixed by moving the account-config translation into
  `AccountConfigFactory.kt` — no gateway state in it, the same reason `auditCodecs` lives in
  its own file — and splitting it into a NAT, an encryption and a video part. The fourth was
  a blank line before a brace in `DeclaredFeatureSet.kt`. **`./gradlew detekt` is green.**
- **`:test:arch:test` failed every rule at once, locally.** Eclipse and the IDE's Kotlin
  plugin copy sources into `<module>/bin/main`, including `test/arch`'s own `fixtures/` —
  the files that violate every rule *on purpose*. Gitignoring `bin/` fixed the commit and not
  the scan, which walks the filesystem. `"bin"` is now in `ArchitectureRules.EXCLUDED`.

**Still: check `gh pr checks docs/native-mandate-design` before assuming anything.** CI
compiles `:data:sip`, which nothing on this Mac can, so its verdict on that module is the
only one there is.

---

## 4. The local build is *mostly* not blocked — the workaround is one flag

The claim in the previous handoff was that `:data:sip` "cannot compile on this Mac". It can.
`:pjsip:api:generatePjsua2Bindings` fails its own precondition check — SWIG's Java typemaps
are missing — but **the bindings it would generate are already on disk** at
`pjsip/api/build/generated/pjsua2/` (313 files), so excluding the task compiles the module:

```bash
./gradlew :data:sip:compileDebugKotlin :data:sip:test -x :pjsip:api:generatePjsua2Bindings -PwarningsAsErrors=true
```

**Use it. It earns its keep immediately**: on the first run it caught a real bug in this
pass's own refactor — `pushParameters` is gateway state, not a `StackAccount` field, and the
extracted `toAccountConfig` had captured it by accident. Nothing but a compiler finds that.

What the exclusion does *not* do is produce an APK: `:app:assembleDebug` needs the native
libraries, which need the real toolchain. That still wants:

```bash
sudo port selfupdate && sudo port install swig-java
```

**Ask the user to run it; you cannot.** Once unblocked, the user's own verification command is:

```bash
./gradlew :app:assembleDebug -Ppjsip.abis=arm64-v8a -Ppjsip.swig=/opt/local/bin/swig -Ppjsip.swig.version=4.4.1
```

Gradle on this machine is slow (3–15 min). **Never run two Gradle invocations at once** — it
corrupts the build directory and every test class then fails to load with
`ClassNotFoundException`, which looks like a code failure and is not. If that happens:
`rm -rf <module>/build` and re-run singly.

---

## 5. Lyra — 0% implemented. Only the closure has been counted.

`PJMEDIA_HAS_LYRA_CODEC` is still **0**. `third_party/lyra` does not exist.
`AudioCodec.LYRA` is still out of `CodecPreferences.DEFAULT`. No build has been attempted.
**Do not describe any of this as working.** Full findings: `docs/lyra-criterion-1.md`.

What the count established, from `google/lyra` at tag `v1.3.2`:

- **Lyra's own code is small** — 20 non-test `.cc`, 34 `.h`. Its direct deps (absl
  `20211102.0`, glog, gulrak filesystem `v1.3.6`) all have CMake.
- **Rock 1 — TensorFlow Lite pinned at v2.11.0**, ~1.35 GB, reached through
  `lyra/tflite_model_wrapper.cc`, and the **XNNPACK delegate is included directly** so it is
  not optional. TFLite has an official CMake build with Android support; the risk is a 2022
  tree of `cpuinfo`/XNNPACK/ruy sources under a 2024 NDK.
- **Rock 2 — `com_google_audio_dsp`** has **no CMake build at all**, reached at 12 call sites
  for 6 targets (`signal_vector_util`, `number_util`, `resampler_q`, `mfcc`, `spectrogram`,
  `spectrogram:inverse_spectrogram`, `portable:read_wav_file`). All would be hand-written.
- **Cheap parts:** all four model files ship in the repo (**3.5 MB**), and
  `third_party/pjproject/aconfigure.ac:2582-2640` already implements `--with-lyra=DIR` — it
  link-tests `LyraDecoder::Create`, then sets `ac_lyra_model_path` and defines the flag.
  **pjproject needs no change.**

**Do this first and stop if it fails:** build **TFLite v2.11.0 for `arm64-v8a` with NDK r27c
via its own CMake, XNNPACK enabled**. Nothing else matters until that is known. If it cannot
be patched cheaply, that is ADR-008 **Exit B** — write it up, ship no code. A working clone is
already at
`/private/tmp/claude-501/-Users-rahulsingh-Desktop-whatsapp-v2/e0bd43b1-5ce8-4cf8-bae4-9ba7eaf5c9bd/scratchpad/lyra`
(may be gone; re-clone `--depth 1 --branch v1.3.2`).

Constraints: **do not edit `third_party/` directly — patches only** (rule 12). Vendor via
`tools/vendor/vendor.sh` + `pins.sh` + `record-hashes.sh`, no build-time downloads (N-2).
`config_site.h` is the one source of truth (N-8). Run `:test:arch:test`.

**And the fact that survives either exit:** the deployed server offers PCMU, PCMA, G.729,
G.723.1, AMR, Speex, VP8, VP9 — **no Lyra**. Even on Exit A the honest deliverable is
*"Lyra compiled, registered, model files verified, provably selectable, with no deployed peer
that accepts it."*

---

## 6. Call history showed a URI where it should show a name — DONE in code, unverified on device

**Requested by the user, 2026-09-10. Implemented in this pass.** The decision and its
rejected alternatives are recorded in `docs/architecture.md` §4.11.3, as every other DECIDE
in this project is.

**What was decided: resolve the name as the page loads, and keep the snapshot as the
fallback.** Both causes needed separate answers and both got one.

1. **`contactName` is still a snapshot, and still written once** — that is deliberate and it
   survives a contact being deleted. What it could not do was learn, so `CallLogTitles`
   (`domain/…/usecase/CallLogTitles.kt`) now asks the address book for the *current* name.
   Precedence: **current name → stored snapshot → the peer's `remoteDisplayName` → the
   address.** The first two are the user's own word for the person and both outrank the
   third, which is criterion 3 and was the ordering that already existed.
2. **The last resort is `SipUri.label()`, not `render()`** — `7001`, not
   `sip:7001@192.168.80.145`. A URI with no user part falls back to the host.

**Where it runs.** `CallLogPagingSource.load`, once per row loaded, on Paging's fetch
dispatcher — so it covers `ALL` and `MISSED` alike, and no contacts read happens per scrolled
row (criterion 5). `HistoryRow.Call` carries the resolved title, so the row composable and the
detail sheet read a value rather than resolving one.

**One thing that had to change underneath.** `ContactsContractRepository` remembers absences
as well as matches, so an address looked up before its contact existed stayed "nobody" for
the life of the process — which would have left the reported case half-fixed on the very
screen that reported it. It now registers a `ContentObserver` on `ContactsContract` (lazily,
on the first lookup that has permission — Hilt builds it long before the user answers that
prompt) and empties the cache on any change. `LookupCache` is synchronised for that reason.

**Tests.** `CallLogTitlesTest` (8, including the missed-call case and the deleted-contact
case), `CallLogPagingSourceTest` (4 new, both filters plus the per-row cost), two in
`SipUriTest`, three in `ContactsContractRepositoryTest`. `:feature:history` is green.

**Not done: seen working on the handset.** Same as everything else in this file.

## 7. Not delivered, deliberately

- **Item 4 — "production-ready, fix all bugs."** Has no completion test, so no honest report
  can claim it. `docs/calling-defects-prompt.md` §3.4 replaces it with the six measurable
  budgets in `docs/dod-sweep.md`, **none of which has a number yet**.
- **Item 6 — memory leaks.** The one leak is the framework's own `ConnectionService` binder,
  2686 bytes per bind cycle, not reachable from app code. Not fixable here; the spec's
  acceptance criterion explicitly allows naming it instead. **Not measured across 20 call
  cycles** — that number is still owed.

---

## 8. Server-side work neither agent can do

- **No TCP listener on `192.168.80.145`.** A video INVITE is ~2700 bytes and cannot fit a
  1472-byte datagram after any trim, so **video calling cannot work until the server accepts
  TCP.** Raise it with whoever operates the server.
- **`mod_opus` is not installed.** Configured in `modules.conf.xml`, `.so` absent. Installing
  it halves the bandwidth of every call with **no client change** — the APK already registers
  and offers Opus.

---

## 9. Housekeeping — actioned, with one left

- ~~`log.txt` at the repo root~~ — deleted. 0 bytes, untracked, stale.
- ~~The two prompt files at the repo root~~ — both are under `docs/` now (the move was
  sitting uncommitted in the working tree). **The links to them were broken by that move and
  are fixed**: `README.md` and `docs/architecture.md` both pointed at the old root paths.
- `docs/phase-2-checkpoint.md` is still referenced by nothing. Left alone: deleting a
  document is the user's call, not an agent's.

---

## 10. How to work here

- **Measure before diagnosing.** Three diagnoses in this project were reached by reading code
  and the device disproved all three — including two by the agent writing this file.
- **Probe the server directly.** Bisecting the MTU with SIP `OPTIONS` took four minutes and
  settled a question source-reading had got wrong twice.
- Build with `-PwarningsAsErrors=true`; CI does, and a local green build without it means
  nothing.
- Verify on the handset, not an emulator.
- Grep every symbol, flag and codec id before naming it.
- **Report honestly.** "Implemented", "registered" and "verified on hardware" are three
  different claims, and only the last ends an argument.
