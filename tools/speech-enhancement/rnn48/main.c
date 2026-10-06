/*
 * rnn48 - run RNNoise over a WAV file, on the host, as the stage 2 candidate.
 *
 * The companion to `ns48`, and deliberately its mirror image: same WAV reader, same
 * sub-frame-at-a-time shape, same "copy the tail through unprocessed" behaviour, so that a
 * difference between an `ns48` number and an `rnn48` number is a difference between the two
 * denoisers and not between two test rigs.
 *
 * ## Why RNNoise is the stage 2 candidate rather than DeepFilterNet3
 *
 * DeepFilterNet3 is the better denoiser on paper and was the first choice. It is also a
 * PyTorch model with no official TFLite export, and its deep-filtering stage is built on
 * complex-valued operations that the torch -> onnx -> tf -> tflite route does not carry
 * across intact. Getting it onto this handset is a model-conversion project with an
 * uncertain outcome, not an integration task.
 *
 * RNNoise is the opposite trade and it is what this measures:
 *
 *   - It is C99 with the weights already in the tree (`src/rnnoise_data.c`, 4.9 MB of
 *     them), so there is no model file to convert, ship, or fail to load.
 *   - It is natively 48 kHz in 480-sample (10 ms) frames, which is exactly the rate and a
 *     clean multiple of the frame the capture path already produces. Nothing resamples.
 *   - It is BSD-licensed - notably *not* a new GPL obligation on top of pjproject's.
 *   - `vec_neon.h` means the arm64 build is vectorised rather than scalar.
 *
 * It is also a 2017-generation GRU with a ~90k-parameter model, so it is not expected to
 * match DeepFilterNet3. Whether it clears the exit bar is the measurement; it is not an
 * assumption.
 *
 * ## The VAD comes free
 *
 * `rnnoise_process_frame` returns the model's own speech probability for the frame. The
 * pipeline this work is part of wants a VAD stage after the denoiser, and this is one -
 * already computed, from a network that has just examined the frame for exactly that.
 * The mean is reported so the bench can sanity-check it against where the speech actually
 * is; nothing here gates on it, because gating belongs in the capture path and not in a
 * measuring instrument.
 *
 * Usage:
 *     rnn48 <in.wav> <out.wav>
 *
 * Input must be 16-bit PCM mono at 48000 Hz. Any other rate is refused rather than
 * resampled: RNNoise's band layout is tied to 48 kHz, and a resampler hidden inside a
 * measuring tool is a second thing being measured.
 */

#include <math.h>

#include "rnnoise.h"
#include "wav.h"

int main(int argc, char **argv)
{
    const char *in_path = NULL, *out_path = NULL;
    wav w = {0};
    DenoiseState *st;
    int frame, i;
    uint32_t off, frames_done = 0;
    double vad_sum = 0.0;

    for (i = 1; i < argc; i++) {
        if (!in_path)       in_path  = argv[i];
        else if (!out_path) out_path = argv[i];
        else { fprintf(stderr, "rnn48: unexpected argument %s\n", argv[i]); return 2; }
    }
    if (!in_path || !out_path) {
        fprintf(stderr, "usage: rnn48 <in.wav> <out.wav>   (16-bit PCM mono, 48000 Hz)\n");
        return 2;
    }

    if (!wav_read(in_path, &w)) return 1;
    if (w.rate != 48000) {
        fprintf(stderr, "rnn48: %s is %d Hz. RNNoise is a 48 kHz model and this tool does "
                        "not resample - see the header.\n", in_path, w.rate);
        free(w.pcm);
        return 1;
    }

    frame = rnnoise_get_frame_size();   /* 480 at 48 kHz: 10 ms */
    st = rnnoise_create(NULL);          /* NULL = the model compiled into the library */
    if (!st) { fprintf(stderr, "rnn48: rnnoise_create failed\n"); free(w.pcm); return 1; }

    for (off = 0; off + (uint32_t)frame <= w.frames; off += (uint32_t)frame) {
        /* RNNoise takes float at int16 scale, not normalised to [-1, 1] - the same
         * convention WebRTC's float NS uses, which is why neither harness scales. */
        float buf[480], out[480];
        for (i = 0; i < frame; i++) buf[i] = (float)w.pcm[off + i];

        vad_sum += rnnoise_process_frame(st, out, buf);
        frames_done++;

        for (i = 0; i < frame; i++) {
            float v = out[i];
            if (v >  32767.0f) v =  32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            w.pcm[off + i] = (int16_t)(v < 0 ? v - 0.5f : v + 0.5f);
        }
    }

    if (!wav_write(out_path, &w)) { rnnoise_destroy(st); free(w.pcm); return 1; }

    fprintf(stderr, "rnn48: %u frames at %d Hz, %u blocks of %d, mean VAD %.3f\n",
            w.frames, w.rate, frames_done, frame,
            frames_done ? vad_sum / frames_done : 0.0);

    rnnoise_destroy(st);
    free(w.pcm);
    return 0;
}
