# Handoff: client-side video conference

Paste this as your first message. Branch `fix/video-call-verification`, pushed.

## Setup (re-read it — it moves every session)

| Thing | Value |
|---|---|
| Mac + FreeSWITCH | `ipconfig getifaddr en0` — it changes constantly |
| `fs_cli` | `/usr/local/freeswitch/bin/fs_cli` |
| Phones | 1001 M14 (Android 15) · 1005 SM-E236B (14) · 1000 + 1003 TC15 (13) · a Pixel 10a (17) with no account yet |
| Build + install | `./build.sh` then `adb -s <dev> install -r app/build/outputs/apk/debug/app-debug.apk` |
| Tests | `./gradlew -Ppjsip.native=false :domain:test :data:sip:testDebugUnitTest :feature:calls:testDebugUnitTest :test:arch:test -x :pjsip:api:generatePjsua2Bindings` |

**When phones stop registering with a 408, it is almost always FreeSWITCH, not the app.**
`sofia status` will show the profile RUNNING on the right address while the socket is still
bound to a previous one. Check `netstat -an | grep 5060`, then
`fs_cli -x 'reloadxml'; fs_cli -x 'sofia profile internal restart'`.

`adb tcpip 5555` does not survive a reboot or a network change; re-enable it over USB.

## What is done (committed)

1. **`e17c6178` — MediaCodec encoder selection.** PJSIP picked hardcoded `OMX.*` names;
   Android 15/17 ship none, yet `createCodecByName` still returns a handle, so the chosen
   encoder produced **0 packets/s** with a live camera and preview. Encoders are now
   enumerated via `MediaCodecList`, ranked hardware-Codec2 first, replaced in-place if they
   will not configure, condemned for the next call if they starve, with a last-resort pass
   so a device is never left with none. Also fixes three output-buffer leaks in
   `encode_begin`.
2. **`66f236b7` — a tile per participant.** The local mix no longer composes one window;
   each call renders into its own surface and `ConferenceVideoGrid` lays them out with
   extension labels and mute badges. Also fixed: the grid had no tap target, so in-call
   controls could not be brought back once they faded.
3. **`6bfb0de3` — H.264 preferred over VP8.** VP8 was offered first and pjmedia offers it
   with `max-fs=580`, pinning the call to **1088x612** at ~150 kbps.
   **`SipAccount.codecs` is persisted per account** — existing accounts keep VP8 first
   until reordered in the account editor.

Verified on device: 1:1 both directions ~1.5 Mbps H.264 zero loss; a 3-party conference
composed on the handset with **zero channels to 3000**, `bypass_media=true`, every RTP peer
a handset. 1672 JVM tests green including architecture rules.

## What is open

1. **Members do not see the host's grid.** The host has N-1 calls and draws N-1 tiles. A
   member has **one** call and receives **one** composed picture, so it has nothing to lay
   out — no labels, no per-tile boxes. The agreed fix is **full mesh**: every participant
   calls every other, so every phone has N-1 streams and draws the identical grid.
   Scoped, not started:
   - host announces the roster in-dialog (`Call.sendRequest`, the pattern
     `subscribeToConferenceRoster` already uses; replies arrive on `onCallTsxState`)
   - members auto-dial the others, with a glare rule (only dial extensions above your own)
   - **`VideoMix` then returns nothing** — with everyone connected to everyone, nobody
     composes anything; this is the small part
   - join/leave propagation
   Cost: a member goes from encoding 1 stream to N-1.
2. **Encoder starvation on the SM-E236B is contained, not cured.** `c2.android.avc.encoder`
   stops yielding input buffers after minutes. Each replacement died *sooner* than the last
   (13 min, then 44 s), which points at something output-side rather than the encoder.
   **Do not re-add a mid-call swap** — it was tried and stranded the device.
   Next step: instrument dequeue-vs-release counts per component before touching it.
3. **Host→peer bitrate asymmetry** — the host sent ~150 kbps to each peer while receiving
   ~1.2 Mbps. May be the VP8 cap above; recheck now that H.264 is preferred.
4. **4-party and join/leave/rejoin/hold/5th-refusal are untested on hardware.**

## Traps

- Media statistics are logged live every 15 s as `Media trace` (rx/tx pkt/s, kbps, loss,
  peer). Trust these, not `/proc/net/dev` — adb-over-Wi-Fi screenshots inflate wlan0.
- Turn on **Settings → SIP trace** to see PJSIP's own log (`PjsipTrace`), which is the only
  place codec faults are visible.
- Never `pkill` Gradle. Edits to `third_party/` need a numbered patch in `pjsip/patches/`
  plus `tools/vendor/record-hashes.sh`, in that order, or architecture rule 12 fails.
- The in-call chrome auto-hides; reveal and tap in one `adb shell` command or the tap misses.
