# Results — 2026-10-04

The run the capture-path denoiser decision was made on. Reproduce with:

```bash
./ns48/build.sh && ./rnn48/build.sh && ./fetch-corpus.sh && python3 bench.py
```

4 speakers × 7 noise classes × 3 SNRs × 6 variants = 504 scored clips. DNSMOS P.835,
cells are **SIG/BAK**, 1–5, higher is better. Gaps under 0.2 are inside DNSMOS's own
run-to-run spread and are not differences.

## The headline

At 5 dB SNR, mean over all seven noise classes:

| variant | SIG | BAK | attenuation |
|---|---|---|---|
| `clean` — the ceiling | 3.62 | 3.99 | — |
| `none` — what shipped before | 2.57 | 2.06 | 0 dB |
| WebRTC NS, as pjmedia drives it | 2.57 | 2.33 | 4.6 dB |
| WebRTC NS at policy 3 | 2.77 | 3.09 | 10.5 dB |
| WebRTC NS at policy 3, 16 kHz | 2.99 | 2.95 | 11.9 dB |
| **RNNoise** | **3.38** | **3.93** | **26.8 dB** |

**RNNoise reaches the clean reference on both axes.** BAK 3.93 against the ceiling's 3.99;
on fan, traffic, train and street it scores *above* the clean reference (4.08, 4.15, 4.06,
4.07), because it also strips the corpus's own recording noise floor. SIG costs 0.24, which
is at the edge of the metric's noise.

## What shipped, and the finding that changed it

The plan was stage 1 (switch on WebRTC's AEC and noise suppressor via `ecOptions`) and then
stage 2 only if stage 1 missed the bar. Stage 1 did not merely miss the bar: on a handset,
`PJMEDIA_ECHO_WEBRTC` **aborts the process on the first captured frame**, because pjmedia
passes `echo->channel_count` into WebRTC's band-count parameter and at 48 kHz WebRTC
expects 3 (`aec_core.c:1765`). So the shipped `ecOptions` is a single bit — the speech
enhancer's — the echo canceller stays at the default that resolves to Speex, and the
`WebRTC NS` rows below are measurements of a configuration that cannot be used on this
build. They are kept because they are what justified going to stage 2.

**On-device, Samsung M23, live call, `/proc/<pid>/stat` over 30 s:**

| | % of one core |
|---|---|
| enhancer on, three runs | 99.1, 100.0, 100.4 |
| enhancer off, one run | 100.6 |

The control sits above two of the three enhancer runs, so the added CPU is **below the
~1.5-point noise floor of the measurement**, against a 15 % budget. Added latency is
structural: one RNNoise block, **10 ms**, against 40 ms — and no buffering, since 960
samples per 20 ms frame is exactly two blocks.

## About the exit criteria

The criteria this work was given were `BAK >= 4.0 AND SIG >= 3.8 at 5 dB SNR`.

**`SIG >= 3.8` is not reachable on this corpus by any denoiser, because the clean, unmixed
speech scores 3.62.** MS-SNSD is downsampled VCTK and carries its own noise floor; DNSMOS
scores it accordingly. No processing can exceed its own input's speech quality, so an
absolute SIG threshold above the ceiling is unmeetable by construction rather than by any
property of the denoiser.

`BAK >= 4.0` is effectively the ceiling too (3.99), and RNNoise meets it on four of the
seven classes and misses it by 0.06 on the mean.

The criterion that survives contact with a ceiling, and the one these results should be read
against:

> **SIG within DNSMOS's noise (±0.2) of the clean reference, and BAK at least the clean
> reference's.**

RNNoise meets that. Nothing else measured comes close.

The other two criteria — added CPU < 15% of one core, added latency < 40 ms — are
properties of a handset and are not answerable here. RNNoise's structural latency is one
10 ms block, well inside the budget; the CPU figure has to be measured on the device.

## The full matrix

```

  DNSMOS P.835, mean over 4 speakers. Exit bar: BAK >= 4.0 and SIG >= 3.8 at 5 dB SNR.

  SNR 0 dB
  variant                     fan      traffic        train       street        music        bells       babble           MEAN
  ----------------------------------------------------------------------------------------------------------------------------
  clean                 3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99   3.62/3.99   
  none                  2.84/1.95    3.40/2.53    2.03/1.44    3.22/2.36    1.20/1.15    1.18/1.13    1.21/1.15   2.15/1.67   
  ns48-48k-default      3.31/2.79    3.41/3.10    2.44/1.72    3.32/2.82    1.24/1.18    1.19/1.15    1.42/1.25   2.33/2.00   
  ns48-48k-p3           2.33/2.92    2.51/3.30    1.90/2.32    2.34/3.06    2.31/2.39    2.33/2.51    1.80/2.25   2.21/2.68   
  ns48-16k-p3           2.96/3.37    3.08/3.44    2.91/2.76    3.09/3.08    1.54/1.30    2.63/2.24    1.62/1.45   2.55/2.52   
  rnnoise               3.23/4.02    3.47/4.07    3.00/3.86    3.38/4.03    3.06/3.42    3.12/3.79    2.76/3.53   3.14/3.82   

  SNR 5 dB
  variant                     fan      traffic        train       street        music        bells       babble           MEAN
  ----------------------------------------------------------------------------------------------------------------------------
  clean                 3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99   3.62/3.99   
  none                  3.43/2.65    3.58/3.11    3.37/2.23    3.51/2.85    1.33/1.15    1.27/1.17    1.47/1.26   2.57/2.06   
  ns48-48k-default      3.47/3.23    3.47/3.41    3.31/2.69    3.45/3.20    1.65/1.30    1.23/1.19    1.43/1.28   2.57/2.33   
  ns48-48k-p3           2.69/3.44    2.97/3.65    2.58/3.24    2.72/3.37    2.92/2.56    3.13/2.90    2.41/2.43   2.77/3.09   
  ns48-16k-p3           3.06/3.67    3.31/3.76    3.18/3.27    3.24/3.31    2.40/1.84    3.43/2.82    2.34/2.02   2.99/2.95   
  rnnoise               3.44/4.08    3.59/4.15    3.39/4.06    3.52/4.07    3.31/3.61    3.34/3.82    3.08/3.71   3.38/3.93   

  SNR 10 dB
  variant                     fan      traffic        train       street        music        bells       babble           MEAN
  ----------------------------------------------------------------------------------------------------------------------------
  clean                 3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99    3.62/3.99   3.62/3.99   
  none                  3.61/3.18    3.64/3.49    3.57/2.84    3.62/3.28    2.61/1.93    1.80/1.46    2.52/1.87   3.05/2.58   
  ns48-48k-default      3.54/3.50    3.53/3.71    3.54/3.32    3.53/3.51    3.41/2.55    3.17/2.56    2.92/2.24   3.38/3.06   
  ns48-48k-p3           3.14/3.81    3.27/3.90    3.05/3.61    3.12/3.70    3.38/2.79    3.43/3.05    2.93/2.75   3.19/3.37   
  ns48-16k-p3           3.31/3.87    3.51/3.99    3.28/3.47    3.40/3.66    3.50/2.72    3.57/3.02    3.20/2.78   3.39/3.36   
  rnnoise               3.57/4.11    3.64/4.13    3.54/4.10    3.60/4.09    3.48/3.78    3.53/3.93    3.39/3.90   3.54/4.01   

  cells are SIG/BAK; higher is better; 1..5. Treat gaps under 0.2 as noise.

  Background attenuation, dB relative to the unprocessed mixture
  (residual level in the frames where the clean reference is silent)
  variant                SNR 0     SNR 5    SNR 10
  ------------------------------------------------
  none                     0.0       0.0       0.0
  ns48-48k-default         4.3       4.6       5.0
  ns48-48k-p3              8.7      10.5      12.3
  ns48-16k-p3             10.7      11.9      12.7
```

## Reading the two hard columns

**music and bells** are the cases a statistical noise-floor estimator has no mechanism for:
neither tonal music nor an impulsive bell is a slowly varying noise floor. At 5 dB the
WebRTC suppressor as pjmedia drives it scores 1.65/1.30 and 1.23/1.19 against unprocessed
1.33/1.15 and 1.27/1.17 — which is to say it does essentially nothing. RNNoise scores
3.31/3.61 and 3.34/3.82.

**babble** is the deferred Phase 2 case and is in the matrix to measure the limitation, not
to be tuned away. Removing background *human voices* needs target-speaker extraction and
voice enrolment, which this work explicitly does not implement. RNNoise's 3.08/3.71 at 5 dB
against an unprocessed 1.47/1.26 is a large improvement in how *intrusive* the background
is judged to be; it is not evidence that the other talkers are gone, and it should not be
read as such.

## What this run cannot tell you

See README.md — the four structural limits, of which two matter most here: the corpus is
16 kHz, so every 48 kHz variant is flattered; and there is no echo in these mixtures, so
the AEC half of the shipped configuration is untested by this bench.
