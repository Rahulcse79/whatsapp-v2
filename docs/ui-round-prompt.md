# Prompt: the WhatsApp-shaped UI round, and a full automated sweep

> Paste this whole file as the opening message of a fresh Claude Code session in
> `whatsapp-v2`. It is self-contained: it carries the lab, the commands, the standards and
> the traps, so nothing below has to be rediscovered.

---

## 0. Orientation — read these first, in this order

1. `docs/master-engineering-prompt.md` — how work is done here. §1 (the five principles),
   §2 (the native mandate), §7 (the review rubric). Not optional.
2. `docs/HANDOFF.md` §0f → §0a, newest first. §0f is client-side conferencing, §0e a full
   feature sweep, §0d an ANR, §0c the earlier sweep. Where two sections disagree, the newer
   one wins.
3. `docs/architecture.md` ADR-003 (conference), ADR-008 (Lyra), ADR-009 (local mixing).

**Branch:** `docs/native-mandate-design`. **Never push** unless Rahul says so in the
session — he pushes by hand. Commit freely and small.

**Another session may share this working tree.** Re-check `git status` and `git log -1`
before committing; HEAD has moved under an agent mid-task more than once.

---

## 1. The lab

**Handsets.** Two Zebra TC15s, Android 13, 8 cores @ 1.8 GHz, arm64 only.

| | serial | accounts |
|---|---|---|
| Phone A | `24110524701351` | `localfs` = 1001, office = **7001** |
| Phone B | `24143524701316` | `localfs` = 1002, office = **7002** |

The office extensions have been renumbered at least twice — **read the accounts screen,
do not trust any number written down, including these.**

**One USB cable.** `adb devices` first; only the cabled phone is driveable and Rahul
answers the other. `adb` is at `/Users/rahulsingh/Downloads/platform-tools/adb`.

**Local FreeSWITCH** on the Mac: `/usr/local/freeswitch/bin/freeswitch -nc -nonat`,
`fs_cli` beside it, config under `/usr/local/freeswitch/etc/freeswitch`. Test extensions:
**9196** echo (audio+video), **9197** bridge-to-loopback (transferable), **9198** tone,
**9199** unrouted, **3000** `mod_conference`. `00_whatsapp_v2_bypass.xml` gives
`bypass_media` for 1001↔1002, which Lyra needs because the server cannot decode it.

Inbound call without the other phone:

```
fs_cli -x "bgapi originate {origination_caller_id_number=9196,origination_caller_id_name=Echo,absolute_codec_string='PCMU'}user/1001 &echo"
```

Drop `absolute_codec_string` to get an `m=video` offer. The server has **no mod_opus and no
G722** — only PCMU, PCMA, G729, G723.1, Speex, L16. It answers **every** blind REFER with
200, so the transfer-refused path cannot be produced on it.

**SIP capture:** sngrep's *live* capture writes an empty 24-byte pcap on this Mac. Use
`tcpdump -i en0 -n -s0 -w f.pcap "udp port 5060"` and read it with `sngrep -I f.pcap`.

---

## 2. Build and test

```bash
./build.sh --install              # debug APK, installs; --reuse-native for Kotlin-only
./gradlew testDebugUnitTest test detekt \
    -x :pjsip:api:generatePjsua2Bindings -x :pjsip:buildPjsua2Native
```

`./build.sh` finds the NDK and SWIG itself and needs no environment prefix. It builds
**debug only** — there is no release path in it, and `assembleRelease` has never been run
in this tree (R8 is on for release and completely unexercised).

**The gate is 1213 tests and detekt clean.** Anything red is yours to fix, including tests
you had to update — updating a test is fine, *deleting* the claim it made is not.

**detekt caps that will bite:** `LargeClass` on `RealPjsipCoreGateway` and `PjsipSipEngine`
(both at the bound — new pure logic goes to **file level** or a new file), `LongMethod` 60,
`ReturnCount` 3, `TooManyFunctions` 20 per class, import ordering, no unused imports.

**Architecture rules** (`:test:arch`): no `Color(0x…)` or `N.dp`/`N.sp` literals outside
`:core:designsystem` (rule 8), every design-system component previewed in light **and** dark
via `@ThemePreviews` (rule 7), no raw `Thread(` (rule 5), no LiveData/RxJava.

---

## 3. How to report

Four different claims, and you must say which you have:
**"compiles" / "registered" / "connected on the wire" / "heard".**

- "On the wire" needs RTP counters — payload type, packets, bitrate, loss — from pjsua's
  own stats, not from SDP and not from the UI.
- **"Heard" cannot be automated.** Never claim it; list it as owed for a human.
- Every UI change needs a screenshot, **in both light and dark**.
- After any device test:
  `adb logcat -d | grep -E "Fatal signal|FATAL EXCEPTION|Assert failed|ANR in|Skipped [0-9]{3,} frames"`,
  compare `pidof com.whatsappv2` before and after, and LeakCanary must report
  **0 APPLICATION LEAKS**.

Commit small, with the evidence in the message, in the voice of the existing commits.

---

## 4. Traps, learned the hard way — do not rediscover these

1. **`CallState.isEstablished` is TRUE for `Held`.** This has caused three separate
   defects: a held call mixed into a conference contributing no audio, a held video call
   keeping the camera open, and a merge reading the call list before a resume landed.
   Anything reading `isEstablished` to mean "can carry media right now" is probably wrong.
2. **A resume is a re-INVITE.** The call stays `Held`, then passes through `Resuming` with
   no media, before `Connected`. Reading the call list straight after issuing one finds
   nothing ready.
3. **Toggling Wi-Fi can move this handset to a different SSID** (it has ~18 saved, and
   jumps to a Windows hotspot on `192.168.137.x`). Check
   `adb shell ip -4 addr show wlan0` against `ipconfig getifaddr en0` before blaming code.
   A `sofia status ... reg` row proves nothing on its own — registrations live an hour;
   compare the **contact port** or `EXP`.
4. **`uiautomator dump` only captures the focused window.** A heads-up notification is
   invisible to it. Use `screencap` for anything that is not the focused window.
5. **The in-call chrome auto-hides after 4 s over video.** Reveal, dump and tap must all
   happen inside that window or the tap lands on the video and toggles it back.
6. **A constant declared twice will drift.** `PJSUA_MAX_CALLS` lived in `config_site.h`
   *and* in `uaConfig.maxCalls`, and the runtime one silently won. The ABI list and the
   SWIG pin had the same shape until they were moved to `gradle.properties`.
7. **`config_site.h` changes need a full `./build.sh`** — `--reuse-native` will silently
   keep the old `.so`.

---

## 5. What to build

Six items. Each says what "done" means; none is a licence to redesign what is already
working.

### 5.1 Settings moves to the Chats top bar

Settings is currently a **bottom tab** (`AppDestination.TOP_LEVEL` = Chats, Calls,
Settings). Move it: a gear at the **top right of Chats**, and take it out of the bar, so
the bar carries **Chats and Calls** only.

Note the history: a bottom bar was removed in Tasks 69/70 for having one destination, then
restored when Chats arrived. Two destinations still earns a bar; one would not. If you find
yourself down to one, say so rather than leaving a bar that navigates nowhere.

`AppRootNavigationTest` presses `tabTag(AppDestination.SETTINGS)` in three tests — those
need to press the gear instead. Keep what each test *claims*.

### 5.2 Dark and light mode, chosen in Settings

Today `WhatsAppV2Theme(darkTheme = isSystemInDarkTheme(), dynamicColor = false)` follows
the system and there is no user control. `AppSettings` has no theme field.

Add one — **System / Light / Dark** — persisted through `AppSettingsRepository` like every
other setting, and a colour-scheme choice if you offer one. The theme must apply without a
restart, and survive process death.

**Check every screen in both themes.** The bottom bar was drawing as a black slab in dark
because Material's default `containerColor` is a *raised* surface — that class of bug shows
in exactly one theme, which is why rule 7 exists.

### 5.3 Calls: direction filters and audio-vs-video, visible

Partly built. `CallLogQuery` already carries **text, direction and a date range**, applied
in SQL by `CallLogRepository.search`; the screen exposes a search field and direction chips
(All / Incoming / Outgoing / Missed). What is missing:

- **A date-range control.** The data layer, the fake and the tests already support
  `fromEpochMillis`/`toEpochMillis`; there is no picker.
- **Audio vs video, obvious on every row.** The log records which a call was and the row
  does not show it. A missed *video* call and a missed *audio* call look identical.
- The filter row only appears once something is narrowed — decide whether that is right.

Keep search in the store. Filtering a loaded page searches the twenty rows on screen and
calls it a search, which looks fine until the match is on row four hundred.

### 5.4 Bottom bar spacing

There is unwanted space around the bar and above its icons. Fix the spacing and the insets
properly rather than nudging padding until it looks right: the shell's `Scaffold` takes
`contentWindowInsets = WindowInsets(0)` and contributes **only** the bar, because every
destination owns its own `Scaffold` and would otherwise pad twice.

Ask Rahul to point at the gap if it is not obvious — it was described as "extra space icon
upper" and that has more than one reading.

### 5.5 Chats top bar: registration status, with the extension

Show the registered extension and its state as a **coloured indicator with the number**:
green registered, orange trying/reconnecting, red failed. `RegistrationState` already
distinguishes `Registered` / `Registering` / `Unregistered` / `Failed`, and `Failed`
carries a `RegistrationFailure` reason — use it rather than inventing a second state model.

Colour must not be the only channel (rule 8's spirit and plain accessibility): pair it with
the state in words or a glyph.

Also add a **default-extension provision**: with several accounts, one is the default and
the user should be able to see and change which, from here rather than only from the
account list. `SipAccount.isDefault` already exists.

### 5.6 Look and feel

Make it read like a real calling app. Concretely, not as a mood: consistent spacing from
`AppTheme.spacing`, real touch targets, avatars where people are listed, an empty state
that says what to do, and transitions that do not jump. Anything hardcoded goes to
`:core:designsystem`.

---

## 6. Then sweep the whole app

USB debugging is on; drive it. Exercise **every** feature and check after **each step** for
crashes, ANRs, main-thread stalls, leaks and app-level `E` logs:

registration and re-registration · logout/login · restart auto-register · outgoing and
incoming audio · incoming with the app in front, backgrounded, and with the screen off ·
DTMF (confirm at the server) · video: on, flip, off, on · **rotation mid-call** · hold and
resume, audio and video · mute, speaker, keypad · call waiting: hold-and-answer, swap, and
ending the active leg of a pair · blind transfer · attended transfer · conference 3000 ·
**local mixing (Merge) from 2 to 8 participants** · recording start/stop/seal · network
loss and recovery · the new search and filters · both themes.

Find and fix every crash, bug, race, leak, media-routing problem and performance issue.
**Rebuild and retest after each significant fix.** Do not stop at the first one.

Known-open, so you can judge whether you have made them worse:

- Two of eight conference legs showed `RX 0pkt` at teardown while all eight showed full TX.
  Unexplained.
- A Telecom **timeout** and a genuine refusal produce the same sentence, "Your phone is on
  another call". `CallNotPermitted` means "already on a cellular call", which a timeout is
  not — telling them apart means widening `PlatformCallRegistry.registerOutgoing` from
  `Boolean` through `:domain`, its fake and its tests.
- `EncryptedRecordingStore.init { sweepAbandoned() }` runs on the injecting thread, which
  is main.
- **Recordings cannot be played.** They seal correctly as encrypted `.rec` files and there
  is no list, no player and no export — `decrypt()` exists and nothing calls it.

---

## 7. Report

A table of what was exercised and PASS/FAIL; every defect with its root cause and fix;
screenshots of each UI change in **both** themes; CPU and memory where you changed
something that costs either; and the remaining limitations stated plainly — including
anything only a human can confirm.

Do not use the phrase "production-ready". Say what was measured, on what, and what was not.
