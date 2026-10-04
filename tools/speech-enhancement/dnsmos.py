"""DNSMOS P.835 - SIG / BAK / OVRL, the metric the exit criteria are written in.

A faithful port of Microsoft's `DNSMOS/dnsmos_local.py` from the DNS-Challenge
repository, kept deliberately close to it. The point of DNSMOS is comparability with
published numbers, and that only holds if the preprocessing matches: the 9.01 s window,
the 1 s hop, the un-normalised float waveform, the third-order polynomial that maps the
network's raw outputs onto the P.835 scale. Every one of those is a chance to be
accidentally 0.3 MOS optimistic, so none of them is reinvented here.

What the three scores mean, since the exit criteria put a threshold on two of them:

    SIG   speech quality on its own - has the speech been distorted?
    BAK   background intrusiveness - how much noise is left, and how annoying is it?
    OVRL  overall quality.

They move in opposite directions under a noise suppressor, which is exactly why both
matter. Any suppressor can drive BAK to 5 by attenuating everything; SIG is what stops
that being called a success.

Scale is 1..5. These are *estimates* of a subjective mean opinion score, with roughly
0.1-0.2 MOS of noise between runs of the same condition, so a 0.05 difference in a table
is not a difference. Treat ~0.2 as the smallest meaningful gap.
"""

from __future__ import annotations

import numpy as np
import onnxruntime as ort

SAMPLE_RATE = 16000
WINDOW_SECONDS = 9.01
WINDOW_SAMPLES = int(WINDOW_SECONDS * SAMPLE_RATE)  # 144160, the model's input width


class Dnsmos:
    """One loaded DNSMOS session. Construct once; scoring is thread-confined per instance."""

    def __init__(self, primary_model: str, p808_model: str | None = None) -> None:
        # Single-threaded on purpose: bench.py runs clips in a process pool, and letting
        # onnxruntime also fan out oversubscribes the machine and makes timings useless.
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        opts.inter_op_num_threads = 1
        self._sess = ort.InferenceSession(
            primary_model, sess_options=opts, providers=["CPUExecutionProvider"]
        )
        self._p808 = (
            ort.InferenceSession(p808_model, sess_options=opts, providers=["CPUExecutionProvider"])
            if p808_model
            else None
        )

    @staticmethod
    def _polyfit(sig: float, bak: float, ovr: float) -> tuple[float, float, float]:
        """The non-personalised mapping from `dnsmos_local.py:get_polyfit_val`.

        Copied coefficient for coefficient. There is a second set for the *personalised*
        model (pDNSMOS), which is trained for target-speaker extraction - the Phase 2
        feature this work explicitly does not implement - and using it against the
        non-personalised weights would silently shift every score.
        """
        p_sig = np.poly1d([-0.08397278, 1.22083953, 0.0052439])
        p_bak = np.poly1d([-0.13166888, 1.60915514, -0.39604546])
        p_ovr = np.poly1d([-0.06766283, 1.11546468, 0.04602535])
        return float(p_sig(sig)), float(p_bak(bak)), float(p_ovr(ovr))

    @staticmethod
    def _melspec(audio: np.ndarray) -> np.ndarray:
        """The 120-bin log-mel the P.808 head takes. Imported lazily: librosa costs about
        a second to import and the P.808 score is optional."""
        import librosa

        mel = librosa.feature.melspectrogram(
            y=audio, sr=SAMPLE_RATE, n_fft=321, hop_length=160, n_mels=120
        )
        return ((librosa.power_to_db(mel, ref=np.max) + 40) / 40).T

    def score(self, audio: np.ndarray, sample_rate: int) -> dict[str, float]:
        """Score one clip. `audio` is mono float in roughly [-1, 1]; 16 kHz is required.

        Resampling is the caller's job rather than this function's, because the caller
        knows which resampler it used everywhere else and a metric that quietly introduces
        a second one is a metric that measures two things.
        """
        if sample_rate != SAMPLE_RATE:
            raise ValueError(
                f"DNSMOS is a {SAMPLE_RATE} Hz metric and was handed {sample_rate} Hz. "
                "Resample before calling - see bench.py, which uses resample_poly."
            )

        audio = np.asarray(audio, dtype=np.float64).reshape(-1)
        # The reference repeats a short clip until it fills one window. Keeping that
        # behaviour matters: anything shorter would otherwise be silently unscoreable.
        while len(audio) < WINDOW_SAMPLES:
            audio = np.append(audio, audio)

        hops = int(np.floor(len(audio) / SAMPLE_RATE) - WINDOW_SECONDS) + 1
        sig_s: list[float] = []
        bak_s: list[float] = []
        ovr_s: list[float] = []
        p808_s: list[float] = []

        for idx in range(hops):
            seg = audio[idx * SAMPLE_RATE : idx * SAMPLE_RATE + WINDOW_SAMPLES]
            if len(seg) < WINDOW_SAMPLES:
                continue
            feats = seg.astype(np.float32)[np.newaxis, :]
            raw_sig, raw_bak, raw_ovr = self._sess.run(None, {"input_1": feats})[0][0]
            s, b, o = self._polyfit(raw_sig, raw_bak, raw_ovr)
            sig_s.append(s)
            bak_s.append(b)
            ovr_s.append(o)
            if self._p808 is not None:
                # `seg[:-160]` is not a typo and not tidy-up-able: it is what the
                # reference feeds, and it is what makes the mel frame count 900.
                mel = self._melspec(seg[:-160]).astype(np.float32)[np.newaxis, :, :]
                p808_s.append(float(self._p808.run(None, {"input_1": mel})[0][0][0]))

        if not sig_s:
            raise ValueError("clip produced no scoreable window")

        out = {
            "SIG": float(np.mean(sig_s)),
            "BAK": float(np.mean(bak_s)),
            "OVRL": float(np.mean(ovr_s)),
            "windows": float(len(sig_s)),
        }
        if p808_s:
            out["P808"] = float(np.mean(p808_s))
        return out
