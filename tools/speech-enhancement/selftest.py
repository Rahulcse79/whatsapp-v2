#!/usr/bin/env python3
"""Regression test for the two denoiser harnesses. Seconds, no corpus, stdlib only.

`bench.py` answers "which denoiser should ship"; this answers "do these two binaries still
do what the decision was made on". It is the cheap check to run after bumping a pin, editing
a patch, or touching either harness, and it needs no download and no virtualenv - only
Python's own `wave` module, so it runs anywhere the build does.

Every threshold below is a window, not a floor, and the windows are the findings:

  * The WebRTC suppressor as pjmedia drives it lands near 6 dB, because pjmedia never calls
    `WebRtcNs_set_policy` and `WebRtcNs_InitCore` leaves `aggrMode = 0`, whose
    `denoiseBound = 0.5f` floors the Wiener gain at 0.5. An UPPER bound is asserted as well
    as a lower one: if this ever rises, somebody has started setting the policy, and the
    whole argument for RNNoise would need re-measuring rather than quietly inheriting.
  * Policy 3 reaches the high teens, which is what makes the policy the knob it is.
  * RNNoise clears 20 dB on the same signal.
  * RNNoise's own VAD separates speech from no-speech, since that is the pipeline's VAD
    stage and a VAD that is stuck has to fail loudly rather than read 0.5 for ever.

Run:  python3 selftest.py
"""

from __future__ import annotations

import math
import random
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
NS48 = HERE / "ns48" / "build" / "ns48"
RNN48 = HERE / "rnn48" / "build" / "rnn48"

RATE = 48000
SECONDS = 6
SPEECH_FROM, SPEECH_TO = 1.5, 4.5     # so the mixture is half speech, half noise only


def write_wav(path: Path, samples: list[float], rate: int = RATE) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(
            struct.pack("<h", max(-32768, min(32767, int(s * 32767)))) for s in samples))


def read_wav(path: Path) -> tuple[list[float], int]:
    with wave.open(str(path), "rb") as w:
        raw = w.readframes(w.getnframes())
        rate = w.getframerate()
    return [v / 32768.0 for (v,) in struct.iter_unpack("<h", raw)], rate


def signals() -> tuple[list[float], list[float]]:
    """A steady broadband noise, and the same noise with a speech-like harmonic stack.

    The noise is one-pole lowpassed white, which gives something fan-shaped rather than
    white - a flat spectrum is the easiest thing in the world for a noise estimator and
    would flatter both denoisers. The seed is fixed so a failure is reproducible.
    """
    rng = random.Random(7)
    n = RATE * SECONDS
    noise, prev, a = [], 0.0, 0.97
    for _ in range(n):
        prev = a * prev + (1.0 - a) * rng.gauss(0.0, 1.0)
        noise.append(prev)
    peak = max(abs(v) for v in noise) or 1.0
    noise = [0.08 * v / peak for v in noise]

    speech = [0.0] * n
    f0 = 120.0
    lo, hi = int(SPEECH_FROM * RATE), int(SPEECH_TO * RATE)
    harmonics = list(range(1, 25))
    for i in range(lo, hi):
        t = i / RATE
        speech[i] = sum(math.sin(2 * math.pi * f0 * k * t) / k for k in harmonics)
    speak = max(abs(v) for v in speech) or 1.0
    speech = [0.25 * v / speak for v in speech]

    return noise, [speech[i] + noise[i] for i in range(n)]


def rms_db(samples: list[float]) -> float:
    if not samples:
        return -999.0
    mean_sq = sum(v * v for v in samples) / len(samples)
    return 20.0 * math.log10(math.sqrt(mean_sq) + 1e-12)


def run(cmd: list[str]) -> str:
    done = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if done.returncode != 0:
        raise AssertionError(f"{Path(str(cmd[0])).name} failed: {done.stderr.strip()}")
    return done.stderr


failures: list[str] = []


def check(name: str, ok: bool, detail: str) -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(f"{name}: {detail}")


def main() -> int:
    for binary, builder in ((NS48, "ns48/build.sh"), (RNN48, "rnn48/build.sh")):
        if not binary.exists():
            print(f"selftest: {binary} is missing. Run {builder} first.", file=sys.stderr)
            return 1

    noise, mixture = signals()

    with tempfile.TemporaryDirectory(prefix="se-selftest-") as td:
        tmp = Path(td)
        noise_wav, mix_wav = tmp / "noise.wav", tmp / "mix.wav"
        write_wav(noise_wav, noise)
        write_wav(mix_wav, mixture)
        base = rms_db(noise)

        print(f"\nsteady noise at {base:.2f} dBFS, {SECONDS}s at {RATE} Hz\n")

        # --- WebRTC NS, exactly as pjmedia drives it -------------------------------
        out = tmp / "ns-default.wav"
        run([NS48, noise_wav, out])
        got, _ = read_wav(out)
        reduction = base - rms_db(got)
        check(
            "WebRTC NS at pjmedia's default policy",
            4.0 <= reduction <= 8.0,
            f"{reduction:.2f} dB (expected 4-8: aggrMode 0 floors the Wiener gain at 0.5, "
            f"which caps attenuation near 6 dB)",
        )

        # --- WebRTC NS with the policy raised --------------------------------------
        out = tmp / "ns-p3.wav"
        run([NS48, noise_wav, out, "--policy", "3"])
        got, _ = read_wav(out)
        p3 = base - rms_db(got)
        check(
            "WebRTC NS at policy 3",
            p3 >= 15.0,
            f"{p3:.2f} dB (expected >= 15: denoiseBound 0.09 is about 21 dB)",
        )
        check(
            "the policy is the knob",
            p3 > reduction + 5.0,
            f"policy 3 beats the default by {p3 - reduction:.2f} dB",
        )

        # --- RNNoise ---------------------------------------------------------------
        out = tmp / "rnn-noise.wav"
        log = run([RNN48, noise_wav, out])
        got, _ = read_wav(out)
        rnn = base - rms_db(got)
        check("RNNoise", rnn >= 20.0, f"{rnn:.2f} dB (expected >= 20)")
        check(
            "RNNoise beats the suppressor it replaces",
            rnn > reduction + 10.0,
            f"{rnn - reduction:.2f} dB better than pjmedia's default suppressor",
        )

        vad_noise = float(log.rsplit("mean VAD", 1)[1].strip())
        check(
            "RNNoise VAD on noise only",
            vad_noise < 0.10,
            f"{vad_noise:.3f} (expected < 0.10: there is no speech in this file)",
        )

        out = tmp / "rnn-mix.wav"
        log = run([RNN48, mix_wav, out])
        vad_mix = float(log.rsplit("mean VAD", 1)[1].strip())
        expected = (SPEECH_TO - SPEECH_FROM) / SECONDS
        check(
            "RNNoise VAD on a half-speech mixture",
            0.30 <= vad_mix <= 0.80,
            f"{vad_mix:.3f} (speech occupies {expected:.0%} of the file)",
        )

        got, rate = read_wav(out)
        check("length and rate survive", len(got) == len(mixture) and rate == RATE,
              f"{len(got)} samples at {rate} Hz in, {len(mixture)} expected")

        # --- the refusals, which are the fallback path ------------------------------
        at16 = tmp / "noise16.wav"
        write_wav(at16, noise[: RATE * 2], rate=16000)
        done = subprocess.run([str(RNN48), str(at16), str(tmp / "x.wav")],
                              capture_output=True, text=True)
        check(
            "rnn48 refuses a rate RNNoise was not trained at",
            done.returncode != 0 and "48 kHz model" in done.stderr,
            "refused 16 kHz rather than resampling silently",
        )

        at44 = tmp / "noise44.wav"
        write_wav(at44, noise[: RATE * 2], rate=44100)
        done = subprocess.run([str(NS48), str(at44), str(tmp / "y.wav")],
                              capture_output=True, text=True)
        check(
            "ns48 refuses a rate WebRtcNs_Init rejects",
            done.returncode != 0 and "rejected" in done.stderr,
            "refused 44100 Hz, as ns_core.c:82 does",
        )

    print()
    if failures:
        print(f"selftest: {len(failures)} failure(s)")
        return 1
    print("selftest: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
