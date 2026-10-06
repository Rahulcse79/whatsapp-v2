#!/usr/bin/env python3
"""The offline bench for capture-path speech enhancement: build mixtures, process them,
score them with DNSMOS P.835, print the table the exit criteria are read off.

Run `fetch-corpus.sh` and `ns48/build.sh` first, then:

    python3 bench.py                      # the whole matrix
    python3 bench.py --variants none,ns48-48k-default,ns48-48k-p3
    python3 bench.py --snr 5 --noise fan,traffic,babble

## The exit criteria this exists to answer

    BAK >= 4.0 AND SIG >= 3.8, at 5 dB SNR, with added CPU < 15% of one core
    and added latency < 40 ms.

This script answers the first half. CPU and latency are properties of the handset and are
measured there - a host timing would be measuring this Mac.

## What each variant is

    clean               the clean speech alone, never mixed with anything. Not a
                        candidate - it is the ceiling, and it is here because a SIG of
                        3.46 means nothing until you know whether the corpus itself scores
                        4.5 or 3.6.
    none                the mixture, untouched. What the capture path does today: the
                        `ecOptions` field was 0, so pjmedia built no noise suppressor.
    ns48-48k-default    WebRTC NS at 48 kHz with no `set_policy` call. This is exactly
                        what `RealPjsipCoreGateway.EC_OPTIONS` now switches on, because
                        pjmedia never calls `set_policy` and `ecOptions` has no bit that
                        reaches it. Stage 1, as shipped.
    ns48-48k-p1..p3     the same, with `WebRtcNs_set_policy` raised. Not reachable from
                        Kotlin; these measure what a patch to `echo_webrtc.c` would buy.
    ns48-16k-p3         policy 3 at 16 kHz instead of 48 kHz. The control for a separate
                        question: pjmedia feeds the suppressor 160-sample sub-frames
                        whatever the clock rate, so at 48 kHz each one is 3.33 ms of audio
                        to a filter whose own source says "We only support 10ms frames".
                        If this beats ns48-48k-p3 by much, the block geometry is a second
                        defect and not a detail.

## Two honest limits, printed with every table

1. The corpus is 16 kHz (see fetch-corpus.sh), so the 48 kHz variants run on upsampled
   speech with an empty top two thirds of band. That flatters them.
2. There is no echo in these mixtures and therefore no AEC in the chain, so nothing here
   measures the echo canceller, only the noise suppressor bolted to it. `PJMEDIA_ECHO_WEBRTC`
   is still a real part of stage 1; it is just not what this bench is about.
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import csv
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

HERE = Path(__file__).resolve().parent
WORK = HERE / "work"
NS48 = HERE / "ns48" / "build" / "ns48"
RNN48 = HERE / "rnn48" / "build" / "rnn48"

CORPUS_RATE = 16000
DEVICE_RATE = 48000       # RealPjsipCoreGateway.CORE_CLOCK_RATE
TARGET_DBFS = -25.0       # MS-SNSD's level convention, kept so levels are comparable
# DNSMOS scores 9.01 s windows at a 1 s hop, so clip length sets how many windows get
# averaged per cell AND the cost of the whole matrix. Both bounds are needed. The minimum
# is the model's window: a shorter clip is repeated until it fills one, which averages a
# clip with itself and reports a confidence it has not got. The maximum is why the first
# run of this script took five times as long as predicted - concatenating five MS-SNSD
# utterances gives about 24 s, which is sixteen windows per clip, and sixteen windows of a
# single speaker buys far less than four speakers of three windows for the same money.
MIN_CLIP_SECONDS = 11.0
MAX_CLIP_SECONDS = 11.5
GAP_SECONDS = 0.2         # MS-SNSD's `silence_length` between concatenated utterances

SNRS = (0, 5, 10)
NOISE_CLASSES = ("fan", "traffic", "train", "street", "music", "bells", "babble")

# name -> (engine, rate the denoiser runs at, WebRTC NS policy or None for "do not call
# set_policy"). `None` instead of a tuple means "do not process at all"; "clean" means that
# and additionally scores the reference rather than the mixture.
VARIANTS: dict[str, tuple[str, int, int | None] | None] = {
    "clean": None,
    "none": None,
    "ns48-48k-default": ("webrtc", DEVICE_RATE, None),
    "ns48-48k-p1": ("webrtc", DEVICE_RATE, 1),
    "ns48-48k-p2": ("webrtc", DEVICE_RATE, 2),
    "ns48-48k-p3": ("webrtc", DEVICE_RATE, 3),
    "ns48-16k-p3": ("webrtc", CORPUS_RATE, 3),
    "rnnoise": ("rnnoise", DEVICE_RATE, None),
}

# The ladder of WebRTC policies is a monotone sweep and p1/p2 sit between two points the
# default set already brackets, so they are available but not run unless asked for. Keeping
# them out of the default halves nothing and saves a quarter of a 25-minute matrix.
DEFAULT_VARIANTS = ("clean", "none", "ns48-48k-default", "ns48-48k-p3", "ns48-16k-p3", "rnnoise")


# ------------------------------------------------------------------ audio helpers

def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x))) + 1e-12)


def active_rms(x: np.ndarray, rate: int, floor_db: float = 30.0) -> float:
    """Speech level over the frames that actually carry speech.

    Whole-file RMS is the obvious thing and it is wrong by several dB, because read speech
    is a third silence and the silence drags the level down - so a mixture labelled "5 dB
    SNR" built from whole-file RMS really sits nearer 2. Every variant would still be
    compared against the same mixture, so the ranking survives either way; the axis label
    does not, and the exit criteria name a specific SNR.

    This is the cheap version of ITU-T P.56: 20 ms frames, keep those within `floor_db` of
    the loudest frame, take the RMS of those. Good to about a decibel on read speech, which
    is well inside the noise on a MOS estimate.
    """
    n = int(0.020 * rate)
    frames = x[: len(x) // n * n].reshape(-1, n)
    energies = np.sqrt(np.mean(np.square(frames), axis=1)) + 1e-12
    keep = energies > energies.max() * (10.0 ** (-floor_db / 20.0))
    return float(np.sqrt(np.mean(np.square(frames[keep]))) + 1e-12) if keep.any() else rms(x)


def mix_at_snr(clean: np.ndarray, noise: np.ndarray, snr_db: float, rate: int):
    """Clean + noise at a stated SNR, both referenced to the active speech level.

    NOT MS-SNSD's `snr_mixer`, deliberately. That function computes

        noisescalar = sqrt(rmsclean / 10**(snr/20) / rmsnoise)

    and the square root is a bug: with clean and noise both pre-normalised to the same RMS
    it reduces to 10**(-snr/40), which lands the mixture at **half** the requested SNR in
    dB. A mixture MS-SNSD labels 10 dB is a 5 dB mixture. The formula here is the correct
    one, so a number from this bench is NOT directly comparable with one computed by
    MS-SNSD's own synthesiser at the same nominal SNR.
    """
    # Tile the noise to the speech length, then take a window from the middle: the first
    # second of several of these recordings is a fade-in.
    if len(noise) < len(clean):
        noise = np.tile(noise, int(np.ceil(len(clean) / len(noise))))
    start = (len(noise) - len(clean)) // 2
    noise = noise[start : start + len(clean)]

    clean = clean * (10.0 ** (TARGET_DBFS / 20.0) / active_rms(clean, rate))
    noise = noise * (active_rms(clean, rate) / (rms(noise) * 10.0 ** (snr_db / 20.0)))
    mixture = clean + noise

    # Headroom rather than clipping. Scaled as a pair so the SNR is untouched.
    peak = float(np.max(np.abs(mixture)))
    if peak > 0.99:
        scale = 0.99 / peak
        mixture, clean, noise = mixture * scale, clean * scale, noise * scale
    return mixture, clean, noise


def to_rate(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    """Polyphase resampling at the exact integer ratio 16k:48k, so no rational
    approximation and no measurable passband error."""
    if src == dst:
        return x
    from math import gcd

    g = gcd(src, dst)
    return resample_poly(x, dst // g, src // g)


def residual_noise_db(mixture: np.ndarray, clean: np.ndarray, rate: int) -> float:
    """Level of whatever is left in the frames where the clean reference is silent.

    This is the dB-of-attenuation number, and it is measured on the speech-absent frames of
    the *same* processed signal rather than by sending noise alone through a second
    suppressor instance - which would be a different measurement, because the noise
    estimator's adaptation depends on having seen speech.
    """
    n = int(0.020 * rate)
    k = min(len(mixture), len(clean)) // n * n
    mix_f = mixture[:k].reshape(-1, n)
    cln_f = clean[:k].reshape(-1, n)
    cln_e = np.sqrt(np.mean(np.square(cln_f), axis=1)) + 1e-12
    silent = cln_e < cln_e.max() * (10.0 ** (-40.0 / 20.0))
    if not silent.any():
        return float("nan")
    return 20.0 * np.log10(np.sqrt(np.mean(np.square(mix_f[silent]))) + 1e-12)


# ------------------------------------------------------------------------ the rig

@dataclass(frozen=True)
class Case:
    speaker: str
    noise: str
    snr: int


def build_clips(speech_dir: Path) -> dict[str, np.ndarray]:
    """One concatenated clip per speaker, at least MIN_CLIP_SECONDS long."""
    clips: dict[str, np.ndarray] = {}
    files: dict[str, list[Path]] = {}
    for p in sorted(speech_dir.glob("*.wav")):
        files.setdefault(p.stem.split("_")[0], []).append(p)

    gap = np.zeros(int(GAP_SECONDS * CORPUS_RATE))
    for speaker, paths in files.items():
        parts: list[np.ndarray] = []
        for p in paths:
            audio, rate = sf.read(p, dtype="float64", always_2d=False)
            if rate != CORPUS_RATE:
                raise SystemExit(f"{p} is {rate} Hz; the corpus is {CORPUS_RATE} Hz")
            parts += [np.asarray(audio).reshape(-1), gap]
        clip = np.concatenate(parts)[: int(MAX_CLIP_SECONDS * CORPUS_RATE)]
        if len(clip) / CORPUS_RATE < MIN_CLIP_SECONDS:
            raise SystemExit(
                f"{speaker}: only {len(clip) / CORPUS_RATE:.1f}s of speech; DNSMOS needs "
                f"{MIN_CLIP_SECONDS}s. Add utterances to fetch-corpus.sh."
            )
        clips[speaker] = clip
    return clips


def run_denoiser(
    signal16: np.ndarray, engine: str, rate: int, policy: int | None, tmp: Path
) -> np.ndarray:
    """Up to `rate`, through the real denoiser binary, back to 16 kHz for scoring.

    A subprocess per clip rather than a shared library bound into Python, on purpose: the
    binaries are the thing being measured, and running them exactly as a person would run
    them by hand means a number in a table can always be reproduced with one command line.
    The process cost is a few milliseconds against roughly three seconds of DNSMOS.
    """
    at_rate = to_rate(signal16, CORPUS_RATE, rate)
    src, dst = tmp / "in.wav", tmp / "out.wav"
    sf.write(src, np.clip(at_rate, -1.0, 1.0), rate, subtype="PCM_16")

    if engine == "rnnoise":
        cmd = [str(RNN48), str(src), str(dst)]
    else:
        cmd = [str(NS48), str(src), str(dst)]
        if policy is not None:
            cmd += ["--policy", str(policy)]
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"{Path(cmd[0]).name} failed: {done.stderr.strip()}")

    out, out_rate = sf.read(dst, dtype="float64", always_2d=False)
    return to_rate(np.asarray(out).reshape(-1), out_rate, CORPUS_RATE)


# Per-process caches. A worker handles many cells, and the clips, the noise and the loaded
# ONNX session are identical across all of them.
#
# This is not a micro-optimisation. The first version of this script passed each task its
# audio as a numpy array in the tuple `pool.map` pickles, which put roughly 3 MB on the
# queue per cell and 270 MB for the full matrix - and the pool simply stopped, every worker
# idle, nothing to see in any log, because the parent was blocked writing to a queue nobody
# was draining yet. Sending keys and resolving them in the worker makes the queue carry
# tens of bytes per cell instead.
_CACHE: dict[str, object] = {}


def _worker_state(models: tuple[str, ...]):
    if "scorer" not in _CACHE:
        from dnsmos import Dnsmos

        _CACHE["scorer"] = Dnsmos(*models)
        _CACHE["clips"] = build_clips(WORK / "speech")
        _CACHE["noise"] = {}
    return _CACHE


def _noise(name: str) -> np.ndarray:
    cache: dict[str, np.ndarray] = _CACHE["noise"]  # type: ignore[assignment]
    if name not in cache:
        audio, rate = sf.read(WORK / "noise" / f"{name}.wav", dtype="float64", always_2d=False)
        cache[name] = to_rate(np.asarray(audio).reshape(-1), rate, CORPUS_RATE)
    return cache[name]


def score_case(args) -> list[dict]:
    """One (speaker, noise, SNR) cell, all variants. Runs in a worker process."""
    case, variants, models = args
    state = _worker_state(models)
    scorer = state["scorer"]
    clip = state["clips"][case.speaker]  # type: ignore[index]
    mixture, clean, _ = mix_at_snr(clip, _noise(case.noise), case.snr, CORPUS_RATE)
    rows = []

    with tempfile.TemporaryDirectory(prefix="ns48-") as td:
        tmp = Path(td)
        for name in variants:
            spec = VARIANTS[name]
            if name == "clean":
                processed = clean          # the ceiling: no noise was ever added
            elif spec is None:
                processed = mixture
            else:
                processed = run_denoiser(mixture, spec[0], spec[1], spec[2], tmp)
            k = min(len(processed), len(clean))
            scores = scorer.score(processed[:k], CORPUS_RATE)
            rows.append(
                dict(
                    speaker=case.speaker,
                    noise=case.noise,
                    snr=case.snr,
                    variant=name,
                    SIG=scores["SIG"],
                    BAK=scores["BAK"],
                    OVRL=scores["OVRL"],
                    P808=scores.get("P808", float("nan")),
                    noise_dbfs=residual_noise_db(processed[:k], clean[:k], CORPUS_RATE),
                )
            )
    return rows


# ------------------------------------------------------------------------- output

def table(rows: list[dict], variants: list[str], noises: list[str], snrs: list[int]) -> None:
    """Mean over speakers, grouped by SNR. One block per SNR, variants down, noise across."""
    def mean(sel, field):
        vals = [r[field] for r in sel]
        return sum(vals) / len(vals) if vals else float("nan")

    for snr in snrs:
        print(f"\n  SNR {snr} dB")
        print(f"  {'variant':<18}" + "".join(f"{n:>13}" for n in noises) + f"{'MEAN':>15}")
        print("  " + "-" * (18 + 13 * len(noises) + 15))
        for v in variants:
            cells = []
            for n in noises:
                sel = [r for r in rows if r["snr"] == snr and r["variant"] == v and r["noise"] == n]
                cells.append(f"{mean(sel, 'SIG'):.2f}/{mean(sel, 'BAK'):.2f}")
            allsel = [r for r in rows if r["snr"] == snr and r["variant"] == v]
            flag = ""
            if snr == 5 and allsel and v != "clean":
                ok = mean(allsel, "BAK") >= 4.0 and mean(allsel, "SIG") >= 3.8
                flag = "  <- PASS" if ok else ""
            print(
                f"  {v:<18}" + "".join(f"{c:>13}" for c in cells)
                + f"{mean(allsel, 'SIG'):>7.2f}/{mean(allsel, 'BAK'):<7.2f}" + flag
            )
    print("\n  cells are SIG/BAK; higher is better; 1..5. Treat gaps under 0.2 as noise.")


def attenuation(rows: list[dict], variants: list[str], snrs: list[int]) -> None:
    print("\n  Background attenuation, dB relative to the unprocessed mixture")
    print("  (residual level in the frames where the clean reference is silent)")
    print(f"  {'variant':<18}" + "".join(f"{f'SNR {s}':>10}" for s in snrs))
    print("  " + "-" * (18 + 10 * len(snrs)))
    base = {
        s: np.nanmean([r["noise_dbfs"] for r in rows if r["variant"] == "none" and r["snr"] == s])
        for s in snrs
    }
    for v in variants:
        if v == "clean":
            continue
        cells = []
        for s in snrs:
            vals = [r["noise_dbfs"] for r in rows if r["variant"] == v and r["snr"] == s]
            cells.append(f"{base[s] - np.nanmean(vals):>9.1f}" if vals and not np.isnan(base[s]) else "        -")
        print(f"  {v:<18}" + "".join(f"{c:>10}" for c in cells))


# --------------------------------------------------------------------------- main

def variants_requested(spec: str) -> list[str]:
    return [v.strip() for v in spec.split(",") if v.strip()]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument(
        "--variants",
        default=",".join(DEFAULT_VARIANTS),
        help=f"comma-separated subset of {','.join(VARIANTS)}",
    )
    ap.add_argument("--noise", default=",".join(NOISE_CLASSES), help="comma-separated subset")
    ap.add_argument("--snr", default=",".join(str(s) for s in SNRS), help="comma-separated dB")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) - 1))
    # P.808 is a single-opinion overall score and the exit criteria are written in P.835's
    # SIG/BAK, so it is off by default - and not as a tidy-up. It costs 4.6 s of the 7.3 s
    # it takes to score one clip (63%), which over the full matrix is the difference
    # between a three-minute run and a nine-minute one.
    ap.add_argument("--p808", action="store_true", help="also compute DNSMOS P.808 (3x slower)")
    ap.add_argument("--csv", default=str(WORK / "results.csv"))
    args = ap.parse_args()

    needed = {VARIANTS[v][0] for v in variants_requested(args.variants) if VARIANTS.get(v)}
    if "webrtc" in needed and not NS48.exists():
        print(f"bench: {NS48} is missing. Run ns48/build.sh first.", file=sys.stderr)
        return 1
    if "rnnoise" in needed and not RNN48.exists():
        print(f"bench: {RNN48} is missing. Run rnn48/build.sh first.", file=sys.stderr)
        return 1
    models = (str(WORK / "dnsmos" / "sig_bak_ovr.onnx"),)
    if args.p808:
        models += (str(WORK / "dnsmos" / "model_v8.onnx"),)
    for m in models:
        if not Path(m).exists():
            print(f"bench: {m} is missing. Run fetch-corpus.sh first.", file=sys.stderr)
            return 1

    variants = variants_requested(args.variants)
    unknown = [v for v in variants if v not in VARIANTS]
    if unknown:
        print(f"bench: unknown variant(s) {unknown}; known: {list(VARIANTS)}", file=sys.stderr)
        return 1
    noises = [n.strip() for n in args.noise.split(",") if n.strip()]
    snrs = [int(s) for s in args.snr.split(",")]

    # Fail here rather than inside a worker nine minutes in: build_clips raises on a
    # too-short speaker, and every noise class is opened once.
    clips = build_clips(WORK / "speech")
    for n in noises:
        path = WORK / "noise" / f"{n}.wav"
        if not path.exists():
            print(f"bench: {path} is missing. Run fetch-corpus.sh first.", file=sys.stderr)
            return 1
        sf.info(path)

    tasks = [
        (Case(spk, n, snr), variants, models)
        for spk in sorted(clips)
        for n in noises
        for snr in snrs
    ]
    print(
        f"bench: {len(tasks)} cells x {len(variants)} variants "
        f"= {len(tasks) * len(variants)} clips, {args.jobs} workers"
    )

    rows: list[dict] = []
    sys.path.insert(0, str(HERE))
    with futures.ProcessPoolExecutor(max_workers=args.jobs) as pool:
        for i, out in enumerate(pool.map(score_case, tasks), 1):
            rows += out
            print(f"\r  {i}/{len(tasks)} cells", end="", flush=True)
    print()

    Path(args.csv).parent.mkdir(parents=True, exist_ok=True)
    with open(args.csv, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)

    print(f"\n  DNSMOS P.835, mean over {len(clips)} speakers. Exit bar: BAK >= 4.0 and "
          "SIG >= 3.8 at 5 dB SNR.")
    table(rows, variants, noises, snrs)
    attenuation(rows, variants, snrs)
    print(
        "\n  Caveats, both structural:\n"
        "   * the corpus is 16 kHz, so the 48 kHz variants see an empty band above 8 kHz\n"
        "     and are flattered relative to a real handset (see fetch-corpus.sh);\n"
        "   * no echo is present, so the AEC half of EC_OPTIONS is untested here.\n"
        f"\n  rows: {args.csv}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
