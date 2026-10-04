# Offline bench for capture-path speech enhancement

Measures what a noise suppressor in the capture path is worth, on a Mac, with no handset
and no server. It exists because "it sounds better" is not a decision procedure and
because the alternative — flashing an APK to judge each candidate by ear — costs twenty
minutes a question and cannot be repeated identically.

```bash
./ns48/build.sh          # the WebRTC noise suppressor, from the vendored sources
./rnn48/build.sh         # RNNoise, from third_party/rnnoise
python3 selftest.py      # 10 checks, seconds, no corpus, stdlib only
./fetch-corpus.sh        # speech, noise and the DNSMOS model into work/ (gitignored)
python3 bench.py         # the full matrix; ~25 min on four cores
```

There is a second question this directory answers, with its own two benches and its own
results file:

```bash
python3 enrolment_bench.py   # can a voice profile tell the user from everybody else?
python3 gate_bench.py        # what does a gate built on it do to a call?
```

Those need `work/speaker/ecapa512.onnx` and `work/speakers/`, both fetched by
`fetch-corpus.sh`. **`VOICE-PROFILE.md` holds that run**, and it is what set every
constant in `domain/.../voice/SpeakerGate.kt`.

**`RESULTS.md` holds the run the shipped configuration was decided on.** Read that first;
re-run `bench.py` when something changes, and `selftest.py` after every change to either
harness or either vendored tree.

`bench.py` needs `numpy`, `scipy`, `soundfile` and `onnxruntime`; `--p808` additionally
needs `librosa`. A virtualenv is the easy way:

```bash
python3 -m venv .venv && .venv/bin/pip install numpy scipy soundfile onnxruntime
```

## What is being measured, and where it sits

The signal path on the handset is

```
mic → platform AEC/NS (VOICE_COMMUNICATION) → pjmedia AEC + NS → conference bridge
    → resample 48k→16k → Lyra → RTP
```

The stage this bench covers is **pjmedia AEC + NS**, at the point `rec_cb()` hands the
frame on (`third_party/pjproject/pjmedia/src/pjmedia/sound_port.c:162`). That point is
chosen because every captured frame passes it exactly once: in a four-party mesh the
suppression cost is paid once rather than once per leg, so it is constant in party count.

It has to happen before Lyra and not after. At 3.2 kbit/s Lyra is generative — it
resynthesises speech from a learned prior rather than coding the waveform — so noise at
its input is not merely passed through, it spends bits and steers the generator. Cleaning
up afterwards is not an option either, because by then the noise is baked into what the
decoder invented.

## The instrument

`ns48/` is the part worth trusting: a ~200-line host program that compiles
`ns_core.c` and `noise_suppression.c` **out of the vendored tree** — the same two files
that are inside `libpjsua2.so` — and drives them through the same call sequence
`pjmedia/src/pjmedia/echo_webrtc.c` uses on the device, down to the 160-sample sub-frames
and `num_bands = 1`. It is not a reimplementation and not a reference from a paper, so a
number it produces is a number about the shipping code. `ns48/main.c`'s header documents
the correspondence call by call.

`rnn48/` is its sibling for RNNoise, built against `third_party/rnnoise` once that is
vendored and against an `RNNOISE_PREFIX` install before it is — that order is the point,
since the decision to add a vendored dependency should be made on a number and this is how
the number gets taken first.

`selftest.py` is the fast regression check: synthetic signals, stdlib only, and every
threshold is a window rather than a floor. The WebRTC suppressor's window has an **upper**
bound as well as a lower one, so that if its attenuation ever rises somebody has started
calling `WebRtcNs_set_policy` and the whole comparison needs re-measuring rather than
quietly inheriting.

`dnsmos.py` is a faithful port of Microsoft's `dnsmos_local.py`: DNSMOS P.835 estimates
three subjective scores on a 1–5 scale.

| | |
|---|---|
| **SIG** | speech quality on its own — has the speech been damaged? |
| **BAK** | background intrusiveness — how much noise is left, and how annoying is it? |
| **OVRL** | overall. |

Both of the first two matter, because they move in opposite directions. Any suppressor can
drive BAK to 5 by attenuating everything; SIG is what stops that counting as success. Run
to run, DNSMOS moves by roughly 0.1–0.2 MOS on the same condition, so **treat gaps under
0.2 as noise**.

`bench.py` builds the mixtures, runs each variant, scores them, and prints SIG/BAK per
noise class per SNR plus a dB-of-attenuation table. Rows land in `work/results.csv`.

## The exit criteria it answers

```
BAK >= 4.0  AND  SIG >= 3.8   at 5 dB SNR
added CPU < 15% of one core
added latency < 40 ms
```

Only the first line is answerable here. CPU and latency are properties of a Samsung handset
under thermal pressure; a timing taken on this Mac would be a fact about this Mac. Those two
are measured on the device.

And the first line needs restating against a ceiling the corpus imposes. **The clean,
unmixed speech scores SIG 3.62 and BAK 3.99**, so `SIG >= 3.8` is above the ceiling and
unmeetable by any processing whatever, and `BAK >= 4.0` is the ceiling to within a
hundredth. `bench.py` prints a `clean` row for exactly this reason: an absolute MOS
threshold is only meaningful next to what the input scores. The form that survives is

> SIG within DNSMOS's noise (±0.2) of the `clean` row, and BAK at least the `clean` row's.

See `RESULTS.md`.

## What this bench cannot tell you

Four limits, all structural, none of them fixable by running it for longer. `bench.py`
prints the first two under every table so they cannot be skimmed past.

1. **The corpus is 16 kHz.** MS-SNSD is a 16 kHz corpus, so the 48 kHz variants run on
   upsampled speech with nothing at all above 8 kHz. The suppressor therefore has an empty
   top two thirds of band to not damage, which **flatters** every 48 kHz result. The bias
   is in the safe direction for a go/no-go — a configuration that fails here fails on a
   handset too — but a configuration that passes here has not yet passed.
2. **There is no echo, so the AEC is untested.** The mixtures are speech plus noise with
   no loudspeaker path, so nothing here exercises `PJMEDIA_ECHO_WEBRTC` itself. The AEC is
   a real part of the configuration; it is just not what this measures.
3. **Nothing downstream of the suppressor is in the chain** — no resampling to 16 kHz, no
   Lyra, no jitter buffer, no concealment. A suppressor that leaves artefacts a generative
   codec happens to amplify would score well here.
4. **Background *voices* are out of scope by decision, not by oversight.** `babble` is in
   the matrix because it is the honest worst case for a statistical suppressor, and it is
   expected to do badly: babble is speech, and a noise estimator that removed it would
   remove the talker too. Separating a known talker from other talkers needs target-speaker
   extraction and voice enrolment, which is deferred (Phase 2). Read the `babble` column as
   a measurement of the limitation, not as a failure to be tuned away.

## Variants

| variant | what it is |
|---|---|
| `clean` | the clean speech, never mixed. The ceiling — a SIG of 3.5 means nothing until you know whether the corpus itself scores 4.5 or 3.6 |
| `none` | the mixture untouched. What the capture path did before this work: `ecOptions` was 0, so pjmedia built no suppressor at all |
| `ns48-48k-default` | WebRTC NS at 48 kHz with no `set_policy` call. pjmedia never calls `WebRtcNs_set_policy` and `ecOptions` has no bit that reaches it, so the suppressor runs at `aggrMode = 0`. **Not a candidate any more:** the flag that creates it is read only by the WebRTC backend, and that backend aborts at 48 kHz (`RESULTS.md`). Kept because it is what justified going to stage 2 |
| `ns48-48k-p1` … `p3` | the same with the policy raised. Not reachable from Kotlin; these price a patch to `echo_webrtc.c` |
| `ns48-16k-p3` | policy 3 at 16 kHz instead of 48. The control for a second question — see below |
| `rnnoise` | RNNoise, and **what ships**. A GRU denoiser, natively 48 kHz in 10 ms blocks, weights compiled into the library, BSD-licensed. `rnn48/main.c` documents why it and not DeepFilterNet3 |

## Why there is a 16 kHz control

`echo_webrtc.c` splits each captured frame into sub-frames of 160 samples for every clock
rate above 8000, and hands each one to the suppressor with `num_bands = 1`. At 16 kHz that
is 10 ms, which is what `ns_core.c` says it supports. At 48 kHz — this app's
`CORE_CLOCK_RATE` — the same 160 samples are **3.33 ms**, and every time constant in the
noise estimator then advances three times per 10 ms of audio. WebRTC's own audio
processing module never does this: above 16 kHz it band-splits and runs the suppressor on
the low band.

Whether that matters is an empirical question, and `ns48-16k-p3` against `ns48-48k-p3` is
how it gets answered rather than argued. If the 16 kHz run keeps speech that the 48 kHz
run destroys, the block geometry is a second defect in its own right and any patch has to
fix both it and the policy.
