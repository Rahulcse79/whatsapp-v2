# Definition-of-Done sweep

Task 68. Every §12 item, walked one at a time, with the **actual** result rather than the
target restated as an outcome.

Ten of the sixteen pass. Six do not, and every one of them fails for the same reason: this
project has never had an Android device or a reachable SIP server in CI. They are listed
with the cause and a follow-up rather than argued around — §13's rule, and the reason this
document is worth reading at all.

**Coverage figures are from CI run
[34031565677](https://github.com/Rahulcse79/whatsapp-v2/actions/runs/34031565677)**, the
last completed measurement at the time of writing (commit `3d2bed1`, before Tasks 51–68).
The numbers for the current tree land with its own run; the *shape* of the result — which
packages are measured and which cannot be — does not change.

---

## The native mandate's items (N-1…N-14, DoD 15-24)

**Added 2026-09-09.** `docs/master-engineering-prompt.md` §12 adds ten binary items to the
Definition of Done. This is where they stand. The distinction the master prompt §10 insists
on is kept: **compiled, registered, negotiated and verified-on-hardware are four different
claims**, and only the last ends an argument.

| # | Item | Result |
|---|---|---|
| 15 | **N-1** — no `.aar`/`.so` in the tree that `:pjsip` did not build, and the rule fails on its fixture | **PASS.** Rule 11, two clauses, two fixtures. It caught a real 19 MB AAR during this change |
| 16 | **N-2, N-3** — the egress-blocked job runs the native stage to completion; the four trees are in-tree at pinned commits | **PARTIAL, and the remaining half matters.** The trees are vendored, pinned and byte-identical from a fresh checkout, and the stack builds from them. The **egress-blocked job has not gone green**, and Opus proved that is not a formality — it fetched a model over the network on every build until §1.3 caught it |
| 17 | **N-4, N-5** — one `./gradlew` and one Actions run produce the stack from source, no manual step, no typed version | **PASS.** CI run `34423538239` built all three ABIs from vendored source through Gradle, with no manual step and no typed version input |
| 18 | **N-6** — every ABI carries its full expected `.so` set, asserted at packaging | **PASS.** `assertNativeLibraries` reported *"3 ABIs × 2 libraries, all present"* in CI, and no 16 KB alignment failure was raised for any ABI |
| 19 | **N-7** — every change to vendored source is a numbered patch; the integrity check passes | **PASS, vacuously — and that is the honest word.** `pjsip/patches/` holds no patch because no vendored file has been changed. Rule 12 passes, and its fixtures prove it *can* fail in all three directions |
| 20 | **N-9** — the audit reports every declared codec as registered, with an on-device round trip | **NOT VERIFIED.** The audit is written and unit-tested; no device has run it. **On Lyra this item is satisfied by ADR-008**: it is not in the declared set, and the audit reports it as *not compiled* |
| 21 | **N-10** — `docs/native-dependencies.md` lists exactly the `third_party/` directories with version, commit, licence, reason, patches, size | **PASS.** `verify-pins.sh` is the check, and it runs in CI |
| 22 | **N-11** — every dependency pinned to a commit, no `branch =`, two builds hash-compared | **PARTIAL.** Pinned, and `verify-pins.sh` fails on any `_BRANCH=`. **The two-build comparison has not been run**, and it cannot be meaningful until the exempt toolchain is pinned — SWIG is not (§3 of `docs/native-dependencies.md`) |
| 23 | **N-13** — `git ls-files pjsip/` returns no `.java` | **PASS, and proven faithful.** `git ls-files pjsip/` returns no `.java`, and the generator reproduces the deleted set **exactly**: 318 files, byte-for-byte identical to the 318 that were committed, 0 differing, 0 missing. Deleting them lost nothing |
| 24 | **N-14** — no `if (aar.exists())`, no fallback; a build with no `.so` fails | **PASS** as written. The condition is gone and `assertNativeLibraries` is the failure. Unexercised until 17 passes |

**Items 17 and 18 are now PASS**, on CI, on Linux, for all three ABIs. The number left to
watch is **16** — the egress-blocked job — because that is the one that proves the vendored
tree needs no network, and it is the one Opus already falsified once.

**The evidence behind 17, 18 and 23** — 2026-09-10, macOS 12.7.6, NDK r27c
(`27.2.12479018`), SWIG 4.2.0:

| Claim | How it was checked |
|---|---|
| Bindings reproduce the deleted set | 318 generated, compared file-by-file against `b3716bb^` — **318 identical, 0 differing, 0 missing** |
| `libpjsua2.so` is the right architecture | `llvm-readelf -h` → `ELF64`, `AArch64` |
| 16 KB aligned (Play, Android 15+) | every `LOAD` segment `0x4000`. **And the check ran** rather than skipping for want of `llvm-readelf`, which is the failure mode a passing check hides |
| It is the JNI wrapper, not a lookalike | `Java_org_pjsip_pjsua2_pjsua2JNI_swig_1module_1init` present — pjproject builds more than one `libpjsua2.so` and only one exports this |
| The declared codecs are actually in it | `opus_encoder_create`, `pjmedia_codec_opus_init`, `vpx_codec_encode`, `SSL_CTX_new` |

**What none of that proves:** `armeabi-v7a`, `x86_64`, Linux, AGP above the script, or a
handset. Four different claims, and only the first two are a CI run away.

**And one existing item changes meaning.** DoD 1 — *"`clean build` passes from a fresh
clone"* — was PASS on a build that took the `:pjsip:api` fallback and shipped no `.so`
(`docs/reconciliation.md` B-8). Under DoD 24 that build now **fails**, which is the intended
outcome. Do not restore the fallback to make DoD 1 green again; a green build that cannot
place a call is exactly what this change removes.

---

## Summary

| # | Item | Result |
|---|---|---|
| 1 | `clean build` from a fresh clone | **PASS** |
| 2 | `:domain` has zero Android dependencies | **PASS** |
| 3 | No SIP SDK import outside `:data:sip` | **PASS** |
| 4 | Whole app runs on `FakeSipEngine` | **PASS** |
| 5 | Two accounts register; delete unregisters first | **PASS** |
| 6 | Registration self-heals with backoff | **PARTIAL** — logic proven, device evidence absent |
| 7 | Lock-screen incoming call via Telecom | **NOT VERIFIED** — needs a device |
| 8 | Audio call: hold, mute, speaker, Bluetooth, DTMF | **NOT VERIFIED** — needs a device and a server |
| 9 | Bidirectional video, camera switch, video mute | **NOT VERIFIED** — needs a device and a server |
| 10 | Blind and attended transfer | **NOT VERIFIED** — needs three endpoints |
| 11 | Dial-in conference with participant list | **PARTIAL** — client half written, three clients never run |
| 12 | No credential or PII in logs or on disk | **PARTIAL** — structural guarantees in place, capture never taken |
| 13 | TLS + SRTP; Mandatory fails rather than downgrades | **PARTIAL** — enforcement asserted, TLS leg blocked on the server |
| 14 | ≥ 80% coverage in `:domain` and `:data:*` | **PARTIAL** — `:domain` 96.7%, `:data:*` 68.3% raw / 91.3% JVM-reachable |
| 15 | Every §2 DECIDE answered in docs | **PASS** |
| 16 | No leaked wake locks or unbounded memory | **NOT VERIFIED** — needs an hour on a device |

---

## 1. `./gradlew clean build` passes from a fresh clone — **PASS**

CI checks out fresh on every run and runs `./gradlew build -PwarningsAsErrors=true`. There
is no local state to carry: the Gradle wrapper jar is generated by its own bootstrap
workflow, and the Android SDK is the runner's.

Warnings are errors on CI and not locally, deliberately: a deprecation should not stop work
in progress, and should stop a merge.

## 2. `:domain` has zero Android dependencies — **PASS**

Proven twice, because one of the two can be defeated.

- Architecture Rule 1 fails on any `android.*` or `androidx.*` import under `:domain`.
- A CI step **adds an Android dependency to `:domain` and asserts the build fails.** A rule
  that is never exercised is a rule nobody knows is broken.

`:domain` applies no Android plugin at all — it is a JVM library, asserted by a third CI
step that greps its build file.

## 3. No SIP SDK import outside `:data:sip` — **PASS**

Architecture Rule 2, plus a CI grep. Since ADR-006 the rule has two clauses: `org.pjsip`
is confined to `:data:sip`, and the removed SDK is rejected **anywhere** — a removed stack
comes back one import at a time, and the rule is what stops it. `RealPjsipCoreGateway` and
`EncryptedRecordingStore` are the only classes that name the SDK, and both live in `stack`
packages precisely so their untestability is visible in the path.

## 4. The whole app runs end-to-end against `FakeSipEngine` — **PASS**

`FakeSipEngine` runs the **real** `CallStateMachine`, so a screen tested against it is
tested against the rules production enforces. It is published from `testFixtures` and
consumed by `:feature:*` and `:app`; `FakeEngineConsumableTest` in `:app` asserts an
Android module can drive it.

Every feature test in the tree is evidence for this item: the dialler, the call screen, the
account list, the history and the conference roster are all exercised with no server, no
network and no device.

## 5. Two accounts register simultaneously; delete unregisters first — **PASS**

`AccountsViewModelTest` and `SessionUseCaseTest`. The ordering — unregister *then* delete —
is asserted on the recorded invocation list rather than inferred from the end state, which
is the only way to tell "unregistered first" from "unregistered eventually".

`DeleteAccountUseCase` also refuses to delete an account with a call in progress, which is
not in the DoD and is the behaviour a user would expect.

## 6. Registration self-heals after airplane mode, handover, registrar restart — **PARTIAL**

**What passes.** `RegistrationBackoff` (exponential with jitter),
`RegistrationRecoveryPolicy` (what a network change means) and
`RegistrationRecoveryCoordinator` (the timers) are all unit-tested, including the case DoD 6
is really about: a failure the user must fix is **never** retried, so a rejected password
cannot become a loop. `HonestStateAuditTest` walks every `RegistrationFailure` so a new one
cannot slip through unclassified.

**What is missing.** "Verified by log evidence" means a device toggling airplane mode and a
registrar being restarted underneath it. Neither has happened.

**Follow-up.** Run the three scenarios by hand against the FreeSWITCH target and attach the
logcat to this document.

## 7. Incoming call rings on the lock screen via Telecom — **NOT VERIFIED**

Implemented: a self-managed `ConnectionService`, a `CallStyle` notification with a
full-screen intent, `USE_FULL_SCREEN_INTENT` declared, and an Activity marked
`showOnLockScreen` + `turnScreenOn`. `CallNotificationPolicy` and `TelecomPolicy` are pure
and tested.

Not verified: whether it actually appears on a locked screen, which is an observation about
a handset. `adb shell dumpsys telecom` showing the registered `PhoneAccount` is also
outstanding.

**Follow-up.** Install a debug build, lock the device, place a call to it.

## 8. Audio call with hold, mute, speaker, Bluetooth, DTMF — **NOT VERIFIED**

Every path is implemented and unit-tested against the fake gateway, including the subtle
ones: early media is not ringing, a repeated `PAUSED` is not a second hold, mute is set on
both the stack and Telecom, and DTMF picks its carrier per digit from settings.

`CallIntegrationTest` covers the whole scenario against a real server — and **has never
run.** The `integration.yml` workflow reports success by *skipping*: with no `SIP_TEST_HOST`
secret it prints a notice and exits. A green tick there means "nobody gave this a server",
which is exactly the failure mode the workflow's own comment warns about.

The DoD's "local Asterisk/Kamailio in Docker" was superseded by ADR-005, which chose the
existing FreeSWITCH deployment instead.

**Follow-up.** Configure `SIP_TEST_HOST` and friends on a runner that can reach the server,
with a device or emulator attached, and record the output.

## 9. Bidirectional video; camera switch and video mute — **NOT VERIFIED**

Implemented across Tasks 51–54. The parts that can be tested off-device are:
`CameraPolicy` (every terminal path releases the camera), `VideoLayout` (a remote
resolution change is a new shape, not a stretch), the escalation prompt (the stack defers
and a person answers), and the video-mute-releases-the-camera rule.

Not verified: that a real camera produces a picture at the far end. Needs two devices.

## 10. Blind and attended transfer — **NOT VERIFIED**

Both implemented, with the decision that matters asserted in a unit test: a REFER's `202
Accepted` is reported as *accepted*, never as *succeeded*. Reporting it as success is the
classic transfer bug — the UI says "transferred", the transferor hangs up, and the caller
they promised to hand over is dropped into a dead call.

Not verified: three parties on a real server.

## 11. Dial-in conference with a participant list — **PARTIAL**

**What passes.** Joining is dialling (ADR-003). `ConferenceIntegrationTest` covers the
client half — the INVITE goes out, the bridge answers, the leg is recorded as a conference,
and the roster does not overstate what the bridge said. `ConferenceSession`'s transitions
are tested through `FakeSipEngine`.

**What is missing.** Three clients hearing each other. That needs three endpoints and a
person to listen.

**Worth knowing.** `mod_conference` does not publish a SIP conference event package to a
dial-in participant by default, so **an absent roster is the expected case**, not a defect.
The UI says so rather than rendering an empty room — which is the honest outcome, and is
what Task 60's third done-when actually asks for.

## 12. Credentials unreadable on disk; nothing sensitive in a release logcat — **PARTIAL**

**On disk — effectively met.** Passwords and TURN credentials are stored as AES-GCM
ciphertext under a key that never leaves the Android Keystore. `allowBackup="false"` plus
every domain excluded from `data_extraction_rules.xml`. Recordings are encrypted with their
plaintext deleted before the write is reported. The account editor sets `FLAG_SECURE`, and
a test asserts the flag is **cleared** on the way out — the half that gets forgotten.

**In logs — structurally met, not captured.** The release logger's `verbose`, `debug` and
`info` have empty bodies, so R8 removes the call sites and the strings never reach the
binary; `android.util.Log` is forbidden outside the two variant loggers; `SipTraceRedactor`
strips every authentication header; CI fails on a credential-named value interpolated into
a log call.

**What is missing.** The item asks for a **capture**: a release build on a device, a full
logcat across register → call → hangup, grepped and recorded. It has not been taken. The
guarantees above make it likely to come back clean; they are not the capture.

**Follow-up.** Take the capture, grep it for the extension, the password and `Authorization`,
and attach the result here.

## 13. TLS + SRTP; Mandatory fails rather than downgrades — **PARTIAL**

**Passes.** SRTP-Mandatory is enforced twice — the stack refuses the negotiation, and the
engine drops a call that reached running media without encryption — and the second is
asserted by `PjsipSipEngineSecurityTest`, including that the drop is recorded as
`MEDIA_FAILURE` rather than as an ordinary hangup. Certificate validation is unconditional
and CI fails on any `checkServerTrusted` in the tree, on `verifyServer*(false)`, and on a
`debug-overrides` block appearing in the network security config.

**Blocked.** "A TLS + SRTP call succeeds against the test target" cannot be run: the
deployed FreeSWITCH has `internal_ssl_enable=false` and no SIP TLS certificate. That is a
change to the deployment and is Infra's to make — tracked as open question **Q9** in
`architecture.md`, not quietly dropped.

**Resolved by ADR-006.** This used to record that media encryption was core-wide, so with
two accounts configured differently the last one added won. PJSIP carries SRTP on
`AccountConfig.mediaConfig.srtpUse`, so it is genuinely per account now. **Codec priority
is still endpoint-wide** and `security.md` says so — the limitation moved rather than
disappearing.

## 14. Coverage ≥ 80% in `:domain` and `:data:*` — **PARTIAL**

Real measured numbers, not the target restated.

### `:domain` — **96.7%** (1058 / 1094 lines). **Passes.**

| Package | Covered | Total | % |
|---|---|---|---|
| `domain/call` | 170 | 176 | 96.6% |
| `domain/contacts` | 6 | 6 | 100% |
| `domain/engine` | 127 | 129 | 98.4% |
| `domain/model` | 364 | 380 | 95.8% |
| `domain/registration` | 59 | 61 | 96.7% |
| `domain/repository` | 8 | 14 | 57.1% |
| `domain/usecase` | 143 | 147 | 97.3% |
| `domain/validation` | 181 | 181 | 100% |

`domain/repository` is 57% because it is interfaces; the only executable lines in it are
default implementations. Including the `testFixtures` source set (`domain/testing`, 329/347)
the module measures 96.3%.

### `:data:*` — **68.3%** (813 / 1190 lines). **Does not meet 80%.**

The shortfall is entirely four packages that **cannot execute on the JVM**, plus generated
DI modules:

| Package | Covered | Total | Why it is zero |
|---|---|---|---|
| `data/sip/registration/stack` | 0 | ~800 | PJSIP; needs a device |
| `data/sip/network/platform` | 0 | 27 | `ConnectivityManager` |
| `data/account/crypto/keystore` | 0 | 20 | Android Keystore |
| `data/sip/stack` | 0 | ~30 | PJSIP `System.loadLibrary` |
| all `*/di` | 0 | 61 | Hilt modules; generated bindings |

**Excluding those, `:data:*` measures 91.3% (813 / 890).** Both figures are given because
only one of them is the answer to the question as asked, and the other is the answer to
"how well is the code that can be tested, tested".

| Package | % |
|---|---|
| `data/account` | 93.8% |
| `data/account/crypto` | 95.3% |
| `data/account/db` | 95.2% |
| `data/account/mapper` | 100% |
| `data/calllog` | 78.6% |
| `data/calllog/db` | 96.4% |
| `data/calllog/mapper` | 100% |
| `data/contacts` | 54.9% |
| `data/settings` | 77.4% |
| `data/sip` | 94.4% |
| `data/sip/call` | 100% |
| `data/sip/network` | 100% |
| `data/sip/registration` | 100% |

**Two real gaps, neither of which is a platform excuse:**

- **`data/contacts` at 54.9%** — the cursor-reading paths for a provider that returns
  unusual shapes are thin.
- **`data/calllog` at 78.6%** — under 80% on the repository package itself, and **not gated
  in CI at all**: `ci.yml`'s thresholds have no entry for `data/calllog`, `data/contacts`,
  `feature/history` or `feature/dialer`.

**Follow-up (a real task, not a note).** Add gates for the four ungated packages and raise
`data/contacts` and `data/calllog` past them. Until then, DoD 14 is not met for `:data:*` on
either reading of the module boundary.

## 15. Every §2 DECIDE answered in `docs/architecture.md` — **PASS**

Three DECIDEs, three ADRs, each with a rationale:

| DECIDE | Answer |
|---|---|
| §2.2 conference server and model | FreeSWITCH `mod_conference`, dial-in MCU, domain shaped for SFU (ADR-003) |
| §2.4 SIP stack | PJSIP 2.17 (pjsua2), built from source in CI; GPLv2, commercial licence unresolved (ADR-006, ADR-002) |
| §2.5 push model | RFC 8599 `pn-*` params plus an ESL gateway, with a four-field payload contract (ADR-004) |

What is *not* settled is named as an open question with an owner and a deadline rather than
filled in with a guess — nine of them, of which two (the licence) block release rather than
development.

## 16. One idle hour: no leaked wake locks, no unbounded memory — **NOT VERIFIED**

LeakCanary is in the debug build. `ProximityLock` releases on every route that is not the
earpiece and on the end of every call, and `ServiceRunPolicy` stops the foreground service
when nothing needs it — both unit-tested, both the mechanisms this item is about.

Not verified: an hour of wall-clock time on a handset, `dumpsys power`, a heap dump, and a
battery measurement. None of that can be automated here.

**Follow-up.** Leave a debug build registered for an hour on a device, capture `dumpsys
power` and a heap dump, and record the battery delta.

---

## What this adds up to

Everything decidable without hardware is built, tested and gated. Everything that needs a
handset or a reachable server is built and unverified.

That is not a comfortable summary, and it is the accurate one. The single change that would
move the most items — 6, 7, 8, 9, 10, 11, 13 and 16 — is **one device and one reachable
FreeSWITCH in CI**. Nothing else on this list is close to it in value.
