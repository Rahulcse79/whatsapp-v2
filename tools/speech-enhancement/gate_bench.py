#!/usr/bin/env python3
"""What a speaker gate actually does to a call, as opposed to how it scores on windows.

`enrolment_bench.py` answers "how often is one window classified correctly". That is the
standard speaker-verification question and it is not the product question, which is:

    how much of the user's own speech does this cut, and
    how much of the other person's speech does it let through?

The two are not the same number. A gate decides over time, with memory, and it is allowed
to be asymmetric - opening instantly and closing slowly turns a per-window error rate into
something much better on one axis and slightly worse on the other. That asymmetry is the
whole design, because the two failures are not equally bad: a colleague leaking for
300 ms is a glitch, and the user being cut mid-word is the feature ruining the call.

## The three things a microphone actually hears

  - **USER ONLY** - the user talking, room noise behind them. The gate must stay open.
  - **OTHERS ONLY** - the user silent, somebody else talking nearby. The gate must close.
    This is the dominant complaint and the case worth most.
  - **OVERLAP** - both at once. A gate cannot fix this; only true extraction can, and
    that is Tier 2. Measured anyway, so the limit is a number rather than a caveat.

Run:  python3 gate_bench.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from enrolment_bench import ENROL_UTTERANCES, mix, read, rms, speakers  # noqa: E402
from speaker import SpeakerEmbedder, similarity  # noqa: E402

WORK = HERE / "work"
MODEL = WORK / "speaker" / "ecapa512.onnx"
RATE = 16000

WINDOW_SECONDS = 2.0      # what the embedding sees
HOP_SECONDS = 0.25        # how often it decides, so latency is a hop and not a window
OPEN_AFTER = 1            # consecutive matching hops to open: instant, by design
CLOSE_AFTER = 6           # consecutive non-matching hops to close: 1.5 s of certainty


class SpeakerGate:
    """Rolling-window speaker gate with asymmetric hysteresis.

    Opens on the first hop that looks like the user and closes only after [CLOSE_AFTER]
    consecutive hops that do not. Starts **open**: the cost of being wrong at the start of
    a call is a moment of leaked background, against a user whose first word is missing.
    """

    def __init__(self, profile: np.ndarray, embedder: SpeakerEmbedder, threshold: float) -> None:
        self._profile = profile
        self._embedder = embedder
        self._threshold = threshold
        self._against = 0
        self.open = True

    def update(self, window: np.ndarray) -> bool:
        score = similarity(self._profile, self._embedder.embed(window))
        if score >= self._threshold:
            self._against = 0
            self.open = True
        else:
            self._against += 1
            if self._against >= CLOSE_AFTER:
                self.open = False
        return self.open


def run_gate(audio: np.ndarray, gate: SpeakerGate) -> np.ndarray:
    """The gate's open/closed decision for every hop of [audio]."""
    window = int(WINDOW_SECONDS * RATE)
    hop = int(HOP_SECONDS * RATE)
    decisions = []
    for end in range(hop, len(audio) + hop, hop):
        chunk = audio[max(0, end - window) : end]
        if len(chunk) < hop:
            break
        decisions.append(gate.update(chunk))
    return np.array(decisions, dtype=bool)


def main() -> int:
    if not MODEL.exists():
        print(f"gate-bench: {MODEL} is missing.", file=sys.stderr)
        return 1

    by_speaker = speakers()
    embedder = SpeakerEmbedder(str(MODEL))
    room = read(WORK / "noise" / "street.wav")

    names = sorted(by_speaker)
    profiles, held = {}, {}
    for name in names:
        files = by_speaker[name]
        profiles[name] = embedder.embed(np.concatenate([read(f) for f in files[:ENROL_UTTERANCES]]))
        held[name] = np.concatenate([read(f) for f in files[ENROL_UTTERANCES:]])

    print(f"\n  {len(names)} speakers. Window {WINDOW_SECONDS}s, hop {HOP_SECONDS}s, "
          f"open after {OPEN_AFTER} hop, close after {CLOSE_AFTER}.")
    print("  Each speaker is the user in turn; every other speaker is the interferer.\n")

    header = (f"  {'threshold':>9}{'user cut':>11}{'others let through':>20}"
              f"{'overlap let through':>21}")
    print(header)
    print("  " + "-" * (len(header) - 2))

    for threshold in (0.20, 0.25, 0.30, 0.35):
        cut, kept_user = 0, 0
        leaked, total_other = 0, 0
        leaked_overlap, total_overlap = 0, 0

        for user in names:
            other = names[(names.index(user) + 1) % len(names)]
            user_audio = mix(held[user], room, 10)
            other_audio = mix(held[other], room, 10)
            n = min(len(user_audio), len(other_audio))
            overlap_audio = user_audio[:n] + other_audio[:n]

            for audio, bucket in (
                (user_audio, "user"),
                (other_audio, "other"),
                (overlap_audio, "overlap"),
            ):
                gate = SpeakerGate(profiles[user], embedder, threshold)
                decisions = run_gate(audio, gate)
                if len(decisions) == 0:
                    continue
                if bucket == "user":
                    cut += int(np.sum(~decisions))
                    kept_user += len(decisions)
                elif bucket == "other":
                    leaked += int(np.sum(decisions))
                    total_other += len(decisions)
                else:
                    leaked_overlap += int(np.sum(decisions))
                    total_overlap += len(decisions)

        print(
            f"  {threshold:>9.2f}"
            f"{cut / max(kept_user, 1) * 100:>10.1f}%"
            f"{leaked / max(total_other, 1) * 100:>19.1f}%"
            f"{leaked_overlap / max(total_overlap, 1) * 100:>20.1f}%"
        )

    print(
        "\n  'user cut' is the failure that ruins a call and must be near zero.\n"
        "  'others let through' is the win: how much of a nearby conversation still goes out.\n"
        "  'overlap let through' is expected to be high - a gate cannot separate two voices\n"
        "  speaking at once, and 100% there simply means it does not cut the user off when\n"
        "  somebody talks over them.\n"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
