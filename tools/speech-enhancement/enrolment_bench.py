#!/usr/bin/env python3
"""Can a voice profile tell the user from everybody else, under call conditions?

This is the gate on the whole personalised-voice feature, and it is deliberately the first
thing built. If a profile cannot separate the enrolled speaker from an interfering one on
a short, noisy, already-denoised window, then no amount of enrolment UI, profile storage
or gating logic is worth writing - and the honest answer is that the feature cannot be
built on this device.

Run:  python3 enrolment_bench.py            (after fetch-corpus.sh and speaker-corpus)

## What is measured, and why these conditions

A speaker-verification paper reports an equal error rate on clean studio speech. That
number is irrelevant here. The gate sees:

  - **short windows.** A gate that waits two seconds to decide has already sent two
    seconds of somebody else's conversation. 0.5-2.0 s is the range worth knowing.
  - **noise**, at the SNRs people actually call from, and
  - **RNNoise's output, not the microphone's.** The capture path denoises first, so the
    embedding is computed on audio a denoiser has already altered. That matters in both
    directions: it removes the noise the embedding would otherwise be confused by, and it
    also removes some of the speaker.

## The number that decides it is the false-reject rate, not the EER

An impostor getting through for 200 ms is a glitch. The *user* being gated out is the
feature destroying the call, and it is the failure they cannot diagnose. So the table
below reports, at a threshold tuned for 1% false accepts, how often the enrolled speaker
is wrongly cut. Anything above a per cent or so and the gate has to be biased open and
leak more.
"""

from __future__ import annotations

import glob
import os
import subprocess
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from speaker import SpeakerEmbedder, similarity  # noqa: E402

WORK = HERE / "work"
MODEL = WORK / "speaker" / "ecapa512.onnx"
RNN48 = HERE / "rnn48" / "build" / "rnn48"
RATE = 16000
ENROL_UTTERANCES = 4          # the rest of each speaker's files are held out for testing
WINDOWS = (0.5, 1.0, 2.0)     # seconds
SNRS = (0, 5, 10)
TARGET_FALSE_ACCEPT = 0.01


def speakers() -> dict[str, list[Path]]:
    by_speaker: dict[str, list[Path]] = defaultdict(list)
    for p in sorted((WORK / "speakers").glob("*.wav")):
        by_speaker[p.stem.split("_")[0]].append(p)
    return {k: v for k, v in by_speaker.items() if len(v) > ENROL_UTTERANCES}


def read(path: Path) -> np.ndarray:
    audio, rate = sf.read(path, dtype="float64", always_2d=False)
    audio = np.asarray(audio).reshape(-1)
    if rate != RATE:
        from math import gcd

        g = gcd(rate, RATE)
        audio = resample_poly(audio, RATE // g, rate // g)
    return audio


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x))) + 1e-12)


def mix(clean: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    if len(noise) < len(clean):
        noise = np.tile(noise, int(np.ceil(len(clean) / len(noise))))
    noise = noise[: len(clean)]
    return clean + noise * (rms(clean) / (rms(noise) * 10.0 ** (snr_db / 20.0)))


def denoise(audio: np.ndarray) -> np.ndarray:
    """Through the real RNNoise binary at 48 kHz, as the capture path does."""
    up = resample_poly(audio, 3, 1)
    with tempfile.TemporaryDirectory(prefix="enrol-") as td:
        src, dst = Path(td) / "a.wav", Path(td) / "b.wav"
        sf.write(src, np.clip(up, -1.0, 1.0), 48000, subtype="PCM_16")
        done = subprocess.run([str(RNN48), str(src), str(dst)], capture_output=True, text=True)
        if done.returncode != 0:
            raise SystemExit(f"rnn48 failed: {done.stderr.strip()}")
        out, _ = sf.read(dst, dtype="float64", always_2d=False)
    return resample_poly(np.asarray(out).reshape(-1), 1, 3)


def windows_of(audio: np.ndarray, seconds: float) -> list[np.ndarray]:
    """Non-overlapping windows with speech in them.

    Silence carries no speaker identity, and scoring it would measure how often the gate
    gets a coin-flip right on an empty window. The capture path has RNNoise's own voice
    probability for exactly this; here a simple energy test on the clean source stands in.
    """
    n = int(seconds * RATE)
    out = []
    for start in range(0, len(audio) - n + 1, n):
        chunk = audio[start : start + n]
        if rms(chunk) > 0.02 * rms(audio):
            out.append(chunk)
    return out


def equal_error_rate(targets: np.ndarray, impostors: np.ndarray) -> tuple[float, float]:
    """EER and the threshold it occurs at, by scanning every observed score.

    A scan rather than an interpolation: the score sets here are small enough that the
    exact crossing is meaningless, and a scan cannot disagree with the FRR number below
    about what a threshold does.
    """
    candidates = []
    for t in np.unique(np.concatenate([targets, impostors])):
        far = float(np.mean(impostors >= t))
        frr = float(np.mean(targets < t))
        candidates.append((abs(far - frr), (far + frr) / 2.0, float(t)))
    _, eer, threshold = min(candidates)
    return eer, threshold


def false_reject_at(targets: np.ndarray, impostors: np.ndarray, far: float) -> tuple[float, float]:
    """How often the enrolled speaker is cut, at a threshold giving `far` false accepts."""
    threshold = float(np.quantile(impostors, 1.0 - far))
    return float(np.mean(targets < threshold)), threshold


def main() -> int:
    for path, hint in ((MODEL, "fetch-corpus.sh --speaker"), (RNN48, "rnn48/build.sh")):
        if not path.exists():
            print(f"enrolment-bench: {path} is missing. Run {hint} first.", file=sys.stderr)
            return 1

    by_speaker = speakers()
    if len(by_speaker) < 3:
        print("enrolment-bench: need at least 3 speakers in work/speakers", file=sys.stderr)
        return 1

    embedder = SpeakerEmbedder(str(MODEL))
    noise = {n: read(WORK / "noise" / f"{n}.wav") for n in ("babble", "street", "traffic")}

    print(f"\n  {len(by_speaker)} speakers, enrolled on {ENROL_UTTERANCES} utterances each")
    print("  threshold set for 1% false accepts; FRR is how often the user is cut\n")

    profiles, held_out = {}, {}
    for name, files in by_speaker.items():
        enrol = np.concatenate([read(f) for f in files[:ENROL_UTTERANCES]])
        profiles[name] = embedder.embed(enrol)
        held_out[name] = np.concatenate([read(f) for f in files[ENROL_UTTERANCES:]])

    header = f"  {'condition':<26}{'window':>8}{'EER':>9}{'FRR@1%FA':>11}{'thresh':>9}"
    print(header)
    print("  " + "-" * (len(header) - 2))

    from scipy.signal import resample_poly as _rp

    conditions: list[tuple[str, callable]] = [
        ("clean", lambda a: a),
        # Controls. The 16k->48k->16k round trip is what `denoise` does around RNNoise, so
        # if it costs anything the RNNoise rows below are being blamed for it.
        ("clean + resample trip", lambda a: _rp(_rp(a, 3, 1), 1, 3)),
        ("clean + RNNoise", lambda a: denoise(a)),
    ]
    for snr in SNRS:
        conditions.append((f"babble {snr} dB", lambda a, s=snr: mix(a, noise["babble"], s)))
    conditions.append(("babble 5 dB + RNNoise", lambda a: denoise(mix(a, noise["babble"], 5))))
    conditions.append(("street 5 dB + RNNoise", lambda a: denoise(mix(a, noise["street"], 5))))

    for label, transform in conditions:
        processed = {name: transform(audio) for name, audio in held_out.items()}
        for seconds in WINDOWS:
            targets, impostors = [], []
            for name, audio in processed.items():
                for chunk in windows_of(audio, seconds):
                    live = embedder.embed(chunk)
                    targets.append(similarity(profiles[name], live))
                    for other in profiles:
                        if other != name:
                            impostors.append(similarity(profiles[other], live))
            t, i = np.array(targets), np.array(impostors)
            if len(t) == 0 or len(i) == 0:
                continue
            eer, _ = equal_error_rate(t, i)
            frr, threshold = false_reject_at(t, i, TARGET_FALSE_ACCEPT)
            print(f"  {label:<26}{seconds:>7.1f}s{eer * 100:>8.1f}%{frr * 100:>10.1f}%{threshold:>9.2f}")

    print(
        "\n  EER is the usual speaker-verification number and is here for comparison only.\n"
        "  FRR@1%FA is the one that decides the feature: an impostor leaking for one window\n"
        "  is a glitch, the user being gated out is the call ruined.\n"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
