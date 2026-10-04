# Voice profile — can a speaker gate work here? — 2026-10-05

The measurement that decides the personalised-voice feature, taken before any of it was
built. Two benches, both on the host:

```bash
python3 enrolment_bench.py    # can a profile tell the user from everyone else?
python3 gate_bench.py         # what does a gate built on it do to a call?
```

12 speakers from MS-SNSD (VCTK), enrolled on 4 utterances each (~25 s), tested on the
held-out rest. Embedding is WeSpeaker `voxceleb_ECAPA512_LM` — ECAPA-TDNN, 192 dimensions,
24.9 MB ONNX, CC-BY-4.0.

## 1. Verification: excellent when clean, poor on short noisy windows

`FRR@1%FA` is how often **the user is wrongly cut** at a threshold allowing 1% false
accepts. It is the number that matters; EER is for comparison with the literature.

| condition | 0.5 s | 1.0 s | 2.0 s |
|---|---|---|---|
| clean | 16.2% | 0.8% | **0.0%** |
| clean + resample round trip | 15.8% | 0.8% | **0.0%** |
| clean + RNNoise | 11.1% | 2.4% | **0.0%** |
| babble 10 dB | 30.4% | 9.7% | 3.4% |
| babble 5 dB | 38.7% | 13.7% | 5.2% |
| babble 0 dB | 54.5% | 33.9% | 17.2% |
| babble 5 dB + RNNoise | 46.1% | 32.3% | 13.8% |
| street 5 dB + RNNoise | 21.6% | 25.0% | 10.3% |

**Two seconds is the window.** The model is perfect on clean two-second windows and
useless on half-second ones; this is what sets `SpeakerGate.WINDOW_MILLIS`.

**RNNoise is not the problem, residual babble is.** The two control rows say so: the
16 k→48 k→16 k round trip costs nothing, and RNNoise on *clean* speech costs nothing at
2 s. What hurts is that RNNoise cannot remove other people's speech — it is a noise
suppressor and speech is what it keeps — so what reaches the embedder is a two-speaker
mixture, and the embedding of a mixture sits between the two speakers.

## 2. The gate: 0% of the user cut, ~93% of a nearby conversation stopped

Per-window error rates are not the product question. A gate decides over time, with
memory, and it is allowed to be asymmetric. Each speaker is the user in turn, every other
is the interferer, street noise at 10 dB, rolling 2 s window, decision every 250 ms,
opening after one matching window:

| close after | threshold | user cut | others let through |
|---|---|---|---|
| 3 (0.75 s) | 0.35 | 1.5% | 4.6% |
| **4 (1.0 s)** | **0.35** | **0.0%** | **6.9%** |
| 6 (1.5 s) | 0.35 | 0.0% | 11.4% |

**The leak is the closing delay, not misclassification.** "Others let through" is
*identical* at thresholds 0.35, 0.40 and 0.45 and moves only with the delay — it is the
first second of each interfering turn, against a mean turn of 10.8 s. On a real call where
somebody talks nearby for thirty seconds, the leak is ~3%.

**Three windows is too few**: it starts cutting the user, which is the line this does not
cross. So the delay is set by the shortest that never cut anyone, and the threshold by
where the false-reject rate reaches zero — not by the leak, which it does not affect.

## 3. What this cannot do, measured rather than hedged

**Overlapped speech: 82–94% let through.** When two people talk at once the microphone has
both voices in it and a gate is a switch, not a separator. That number is the *correct*
behaviour — the alternative is cutting the user off whenever somebody talks over them —
but it is the honest limit of Tier 1. Separating overlap needs target-speaker extraction.

**This is not "100% noise removal", and that remains unachievable.** The gate removes
other people's voices *while the user is not speaking*, which is most of what people mean
when they say they can hear the office. It does nothing during overlap, and the steady
background (fan, traffic, road) is RNNoise's job, measured separately in `RESULTS.md` at
~26.8 dB.

## 4. Corpus caveat

MS-SNSD is read speech from VCTK at 16 kHz: one microphone, one room, no session
variability. A real profile is enrolled on one day, on one handset, and used on others —
with a cold, over Bluetooth, in a car. Every number here is therefore an **upper bound**,
and the margin that matters is the one between "0.0% user cut" and the first threshold
that is not zero. That margin is why the gate opens on one window and closes on four.
