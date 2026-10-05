# WeSpeaker `voxceleb_ECAPA512_LM`

The speaker-embedding model the voice profile is built from (ADR-013).

| | |
|---|---|
| Upstream | https://huggingface.co/Wespeaker/wespeaker-ecapa-tdnn512-LM |
| File | `voxceleb_ECAPA512_LM.onnx` |
| SHA-256 | `d71b85d9b48058ef68004f04f1b78acebefb9dfcf542e19b976a12a5ad1f10b0` |
| Architecture | ECAPA-TDNN, 512 channels |
| Input | `feats [B, T, 80]` — 80-bin Kaldi log-mel filterbank, mean-normalised |
| Output | `embs [B, 192]` |
| Licence | **CC-BY-4.0** (model card). Training data is VoxCeleb |
| Toolkit licence | Apache-2.0 — see `LICENSE` |

**Attribution is a condition of the licence**, so the About screen names it. This is the
only dependency in the tree whose licence asks for something rather than merely permitting
— see `docs/architecture.md` ADR-002.

## Why the ONNX is shipped rather than converted

TFLite was the preferred target: it is already linked into `libpjsua2.so` for Lyra, so a
`.tflite` would have needed no new runtime at all. Two routes were tried and both failed,
on 2026-10-05:

- `onnx2tf` aborts at `BatchNormalization_11` with a layout mismatch in the 1-D
  convolution axes, with and without `-kat`. Going further needs model-specific
  parameter-replacement JSON.
- int8 dynamic quantisation produces a 6.1 MB model with no CPU kernel —
  `NOT_IMPLEMENTED: ConvInteger`. ECAPA is Conv1d-heavy, so quantising only `MatMul`
  saves little.

So the model ships as ONNX and runs on ONNX Runtime Android, which executes it exactly as
`tools/speech-enhancement` measured it on the host. That is the point: the numbers in
`VOICE-PROFILE.md` are statements about *this file*.
