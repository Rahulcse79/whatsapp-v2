# Live Call Filtering — on-device verification, 2026-10-06

Two real speakers, two handsets, four turns of ~60 s each, no call (so no echo and no AEC
reference), 1456 scored windows. Raw logs and every parser are in
`~/Desktop/voice-gate-logs-2026-10-06/`. No code was changed.

## Verdict

**The gate blocks other people well and cuts its own user roughly half the time.** It is not
shippable with the default ON as it stands, and no threshold fixes it.

The requirement was "other voices and noise suppressed, user's voice passes clearly".
Measured on the shipped settings (threshold 0.35, closeAfter 4):

| | measured | requirement |
|---|---|---|
| Room noise suppressed | 98.7% of ambient windows held | met |
| Other people suppressed | 97-98% of their speech held | effectively met |
| **User's own voice passes** | **~45% of their speech** | **far short** |

## The 2x2, measured

Each turn is one person speaking continuously for ~60 s into one handset, no call.

| | vs **1003** (Rahul's profile) | vs **1002** (colleague's profile) |
|---|---|---|
| **Rahul speaks** | p50 **+0.322**, max +0.540 — *genuine* | p50 +0.213, max **+0.345** — *impostor* |
| **Colleague speaks** | p50 +0.185, max **+0.320** — *impostor* | p50 **+0.319**, max +0.477 — *genuine* |
| **nobody** (ambient) | p50 +0.005, max +0.105 | p50 +0.091, max +0.312 |

Two independent people, two independent profiles, and both genuine medians land at ~0.32
against a threshold of 0.35. That reproducibility is what rules out "one bad enrolment".

The classes **do** separate — every impostor window stayed under 0.35, and both impostor
maxima (+0.320, +0.345) sit at or below the genuine medians. The separation is just far too
narrow for a hard threshold: the genuine distribution's lower half falls inside the impostor's
range.

## The trade-off surface

Replaying the recorded sequences through the real `SpeakerGate` state machine. Cells are
worst-case-across-both-people: `user-through % / impostor-leak % / ambient-leak %`.

```
thr \ closeAfter |        4            8           12           16           20
      0.28       | 76.6/18.9/5.3  88.9/28.8/7.0  96.8/37.9/8.5  98.4/47.0/9.9 100/55.3/11.3
      0.30       | 70.6/11.4/4.6  84.1/19.4/6.7  93.3/26.5/8.1  95.6/32.7/9.5 97.2/38.3/10.9
      0.32       | 64.7/ 6.1/1.3  78.6/10.6/3.1  89.3/15.2/4.8  94.0/19.7/6.6 96.8/24.2/ 8.3
      0.34       | 55.6/ 2.7/1.3  67.1/ 5.7/3.1  74.2/ 8.7/4.8  79.0/11.7/6.6 82.5/14.8/ 8.3
      0.36       | 40.5/ 1.5/1.3  52.0/ 3.6/3.1  57.9/ 5.6/4.8  61.1/ 7.7/6.6 64.3/ 9.7/ 8.3
```

**No cell reaches 95% user-through at under 10% leak.** Raising `closeAfter` does not rescue
it, which was the obvious hope: an interferer's score spikes above the line often enough that
a longer close delay holds the gate open for them too, so leak scales with it almost as fast
as user-through does.

Least-bad operating points, if it must ship: `0.32 / 8` (79% user, 11% leak) or `0.32 / 12`
(89% user, 15% leak). Both still cut the user more than the bench claimed at any setting.

## Why the bench and the handset disagree — the actionable lead

`SpeakerGate`'s KDoc documents 0.0% user-cut at 0.35/4 from `gate_bench.py` over twelve
speakers. On-device it is ~55%. The bench enrols and tests from the same 16 kHz corpus; the
handset does not, because **the enrolment path and the scoring path are not the same chain**:

- **Scoring**: mic -> platform `VOICE_COMMUNICATION` -> `pjmedia_snd_port` **with RNNoise**
  (`EC_OPTIONS = ECHO_USE_SPEECH_ENHANCER`, `RealPjsipCoreGateway.kt:3906`) -> 48 kHz bridge
  (`CORE_CLOCK_RATE`) -> resampled to the tap's 16 kHz -> embedder.
- **Enrolment**: mic -> platform `VOICE_COMMUNICATION` -> `AudioRecord` at 16 kHz -> embedder.
  **RNNoise never runs.**

So the profile is a vector of audio that has never been denoised, and every live window has
been. `VoiceEnroller.kt:159` deliberately matches the *audio source* for exactly this reason —
the comment says "enrolled through the processing the gate will later see" — but it matches
only the platform preset, not pjmedia's speech enhancer, which sits downstream of it.

**This is a hypothesis with a named mechanism, not a conclusion.** It predicts that enrolling
through the gate's own tap (or scoring from a pre-enhancer tap) would raise same-speaker
scores. Testing it is the next piece of work and it needs no new measurement rig.

Ruled out already: sample rate (both 16 kHz), audio source (both `VOICE_COMMUNICATION`),
bad enrolment (two independent profiles agree to within 0.003), echo contamination (the
no-call runs have no far end at all).

## Three defects found

### 1. The gate runs with no call and holds the microphone open indefinitely

`setLiveCallFiltering(true)` calls `startVoiceGate` unconditionally
(`RealPjsipCoreGateway.kt:2068`) with no check that a call exists, so flipping the Settings
toggle opens the capture device and starts the 4 Hz embedder loop for ever.

Measured: `cmd appops get com.whatsappv2 RECORD_AUDIO` reported `allow; (running)` for
**16m50s** — from the toggle flip, through a call, and still running after the call ended with
`show calls count` = 0. 296 windows were scored over 74 s before any call existed. Only
force-stopping the app closes it.

Cost: the mic indicator is lit with no call, and 130 ms of embedding every 250 ms is about
half a core, indefinitely.

Fix: `startVoiceGate` should return early when no call has active media, and
`setLiveCallFiltering(true)` should not start the gate outside a call.

(It was useful here — it is what made the no-call rig possible — but it is still a defect.)

### 2. The 0.35 threshold does not transfer from the bench to the handset

Covered above. The number is not wrong by a little; the measured user-cut rate is ~55% where
the bench predicted 0.0%.

### 3. How the handset is held moves the score by 0.25

Same speaker, profile, handset and route: p50 **+0.506** held away from the face versus
**+0.259** held at the ear. That swing is larger than the entire margin to the threshold, so
any threshold tuned in one posture is wrong in the other.

## Rig traps worth remembering

- **9196 is an echo extension.** On speakerphone it plays the user's own delayed voice back
  into the mic. It cost one run. It is *not* the main effect — the no-call runs were still
  only +0.32 — but it is worth about 0.07.
- **Ambient score is profile-specific**: the same room scored p50 +0.005 against one profile
  and +0.091 against another. The handoff's alarming "p50 0.146 / max 0.469 / 1.9% crossings"
  was 1002's profile, not a property of the room. Ambient was never the problem: 0 of 513
  ambient windows crossed 0.35 on either handset.
- **Live call filtering was persisted OFF on 1001 and 1003** at session start though the
  shipped default is `true` (`AppSettings.kt:185`). Check it before trusting any measurement.
- Profiles decode as 192 big-endian float32 at offset 20, unit norm, after a 20-byte header of
  version / enrolledSeconds / createdAtEpochMillis / dims. Pairwise cosine identifies whose
  voice a handset holds without asking: 1001 vs 1003 `+0.8501` (same person), 1002 vs either
  `+0.14`/`+0.23`. See `cmp_profiles.py`.

## What is NOT answered

- **Overlapping speech** was never tested. The design predicts 82-94% pass-through by
  intent.
- Whether re-enrolling through the gate's own tap closes the bench/handset gap (defect 2).
- Only two speakers. Two is enough to show the gap is systematic, not enough to set a number.

## State at stop

- Branch `claude/sharp-turing-47d2c3` at `12164540`, clean, pushed, **no code changed**.
- `main` is `c13f3d51`; PR #48 merged 2026-10-06 12:10, so main already has 10 of the 11
  commits. Only `12164540` is outstanding — `main..HEAD` is 1 commit, not 11. PR still
  deferred: on this evidence the feature should not ship defaulted ON.
- App force-stopped on 1002 and 1003; microphone confirmed closed on both.
- `live_call_filtering_enabled` was changed 0 -> 1 on 1003 to run the test and left at 1.
  1002 was already 1. 1001 is still 0. All three profiles are byte-identical to how they
  were found — nothing was re-enrolled.
