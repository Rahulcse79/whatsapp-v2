"""Speaker embeddings: a kaldi-compatible front end and the ECAPA model that eats it.

The voice profile is one 192-dimensional vector. Enrolment averages it over a minute or
two of the user's speech; during a call the same vector is computed over a short window
and compared by cosine similarity. Everything here is the *host* half — the reference the
on-device implementation is checked against, and the thing the bench measures.

## The front end is the risky part, not the model

`voxceleb_ECAPA512_LM.onnx` takes `feats [B, T, 80]` and nothing else: it has no opinion
about how those 80 numbers were produced, and it will happily return a confident-looking
embedding from features that do not match the ones it was trained on. A front end that is
subtly wrong therefore does not fail, it degrades - and it degrades in the direction of
"this model is not very good", which is the wrong conclusion to draw.

So this reproduces Kaldi's `compute-fbank-feats` as WeSpeaker drives it, detail by detail,
and every detail below is one that changes the answer:

  - the waveform is scaled to **int16 range** before anything else. Kaldi's energy floor
    and dither are defined in those units, and feeding [-1, 1] shifts every log-mel value
    by a constant ~90 dB;
  - **DC removal per frame**, then **pre-emphasis 0.97** with the first sample repeated;
  - a 25 ms frame every 10 ms, `snip_edges` - no padding, so the last partial frame is
    dropped rather than zero-filled;
  - **512-point FFT** (the next power of two above 400), power spectrum;
  - 80 triangular mel bins between 20 Hz and Nyquist, on Kaldi's mel scale
    (`1127 ln(1 + f/700)`), unnormalised - not Slaney's, which librosa defaults to and
    which scales each filter by its width;
  - `log(max(x, eps))`, and finally **cepstral mean normalisation over the utterance**,
    which is what makes the embedding indifferent to the microphone.
"""

from __future__ import annotations

import numpy as np

SAMPLE_RATE = 16000
N_MELS = 80
FRAME_LENGTH = 400     # 25 ms
FRAME_SHIFT = 160      # 10 ms
N_FFT = 512            # next power of two above FRAME_LENGTH
PREEMPH = 0.97
LOW_FREQ = 20.0
EMBEDDING_DIM = 192


def _mel(f: np.ndarray | float) -> np.ndarray | float:
    """Kaldi's mel scale. NOT Slaney's, which librosa uses by default."""
    return 1127.0 * np.log(1.0 + np.asarray(f) / 700.0)


def _mel_filterbank(n_mels: int, n_fft: int, rate: int) -> np.ndarray:
    """Kaldi's triangular bank, unnormalised, over the FFT's positive bins."""
    nyquist = rate / 2.0
    lo, hi = _mel(LOW_FREQ), _mel(nyquist)
    # n_mels + 2 edges gives n_mels overlapping triangles.
    edges = np.linspace(lo, hi, n_mels + 2)
    # Kaldi works in FFT-bin space, so the edges come back to Hz and then to bins.
    hz = 700.0 * (np.exp(edges / 1127.0) - 1.0)
    bins = hz * n_fft / rate
    freqs = np.arange(n_fft // 2 + 1, dtype=np.float64)

    bank = np.zeros((n_mels, n_fft // 2 + 1))
    for m in range(n_mels):
        left, centre, right = bins[m], bins[m + 1], bins[m + 2]
        rising = (freqs - left) / max(centre - left, 1e-10)
        falling = (right - freqs) / max(right - centre, 1e-10)
        bank[m] = np.maximum(0.0, np.minimum(rising, falling))
    return bank


_BANK = _mel_filterbank(N_MELS, N_FFT, SAMPLE_RATE)
_WINDOW = np.hamming(FRAME_LENGTH)


def fbank(samples: np.ndarray, rate: int = SAMPLE_RATE) -> np.ndarray:
    """80-bin log-mel filterbank, mean-normalised, as `[T, 80]`.

    `samples` is mono float in roughly [-1, 1]; it is scaled to int16 range here rather
    than by the caller, because getting that wrong is invisible and costly - see the
    module docstring.
    """
    if rate != SAMPLE_RATE:
        raise ValueError(f"the embedding model is {SAMPLE_RATE} Hz and was given {rate}")

    wave = np.asarray(samples, dtype=np.float64).reshape(-1) * (1 << 15)
    count = 1 + (len(wave) - FRAME_LENGTH) // FRAME_SHIFT if len(wave) >= FRAME_LENGTH else 0
    if count <= 0:
        return np.zeros((0, N_MELS), dtype=np.float32)

    idx = np.arange(FRAME_LENGTH)[None, :] + FRAME_SHIFT * np.arange(count)[:, None]
    frames = wave[idx]

    frames = frames - frames.mean(axis=1, keepdims=True)          # remove DC, per frame
    emphasised = np.empty_like(frames)
    emphasised[:, 0] = frames[:, 0] - PREEMPH * frames[:, 0]      # kaldi repeats sample 0
    emphasised[:, 1:] = frames[:, 1:] - PREEMPH * frames[:, :-1]
    windowed = emphasised * _WINDOW

    power = np.abs(np.fft.rfft(windowed, n=N_FFT, axis=1)) ** 2
    energies = power @ _BANK.T
    feats = np.log(np.maximum(energies, np.finfo(np.float32).eps))

    # Cepstral mean normalisation over the whole utterance: what makes the embedding a
    # statement about the speaker rather than about the microphone and the room.
    return (feats - feats.mean(axis=0, keepdims=True)).astype(np.float32)


class SpeakerEmbedder:
    """WeSpeaker's `voxceleb_ECAPA512_LM`, loaded once.

    192 dimensions, VoxCeleb-trained, CC-BY-4.0. Single-threaded on purpose, as the
    DNSMOS session is: the bench fans out over processes and letting onnxruntime also fan
    out oversubscribes the machine.
    """

    def __init__(self, model_path: str) -> None:
        import onnxruntime as ort

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        opts.inter_op_num_threads = 1
        self._session = ort.InferenceSession(
            model_path, sess_options=opts, providers=["CPUExecutionProvider"]
        )

    def embed(self, samples: np.ndarray, rate: int = SAMPLE_RATE) -> np.ndarray:
        """One L2-normalised embedding for one utterance.

        Normalised here rather than by the caller so that cosine similarity is a plain dot
        product everywhere, and so that two callers cannot disagree about whether it was
        done.
        """
        feats = fbank(samples, rate)
        if len(feats) == 0:
            return np.zeros(EMBEDDING_DIM, dtype=np.float32)
        out = self._session.run(None, {"feats": feats[None, :, :]})[0][0]
        norm = np.linalg.norm(out)
        return (out / norm).astype(np.float32) if norm > 0 else out.astype(np.float32)


def similarity(one: np.ndarray, other: np.ndarray) -> float:
    """Cosine similarity of two embeddings from [SpeakerEmbedder.embed], in [-1, 1]."""
    return float(np.dot(one, other))
