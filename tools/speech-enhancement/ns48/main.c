/*
 * ns48 - run the vendored WebRTC noise suppressor over a WAV file, on the host.
 *
 * This is the measuring instrument for stage 1 of the capture-path speech-enhancement
 * work, and the whole point of it is that it is NOT a reimplementation. It compiles the
 * same two source files that are inside `libpjsua2.so`
 *
 *     third_party/pjproject/third_party/webrtc/src/webrtc/modules/audio_processing/ns/
 *         ns_core.c
 *         noise_suppression.c
 *
 * and drives them through the same sequence `pjmedia/src/pjmedia/echo_webrtc.c` drives
 * them through on the handset. A score produced here is therefore a score for the code
 * that ships, not for a paper's description of it.
 *
 * ## What "the same sequence" means, exactly
 *
 * `webrtc_aec_cancel_echo()` splits each captured frame into sub-frames of
 * `echo->subframe_len` samples - 160 for any clock rate above 8000 (`echo_webrtc.c:158`)
 * - and per sub-frame calls `WebRtcNs_Analyze(ns, in)` before the AEC and
 * `WebRtcNs_Process(ns, &in, 1, &out)` after it, always with `num_bands = 1`. With no AEC
 * in the chain the signal reaching `Process` is the signal that reached `Analyze`, which
 * is what this tool does. `num_bands = 1` is reproduced deliberately: it is pjmedia's
 * choice, it is one of the things being measured, and WebRTC's own audio processing
 * module would instead band-split anything above 16 kHz.
 *
 * Consequence, and it is the reason this tool exists: at 48 kHz those 160 samples are
 * 3.33 ms, while `ns_core.c` says "We only support 10ms frames" and sizes its analysis
 * window for 160 samples at 16 kHz. Driving it at 48 kHz is dimensionally valid - and
 * `WebRtcNs_InitCore` does accept 48000 (`ns_core.c:82`) - but every time constant in the
 * noise estimator then advances three times per 10 ms of audio instead of once. Running
 * the same clean+noise mixture through this tool at 48 kHz and at 16 kHz is what turns
 * that from an argument into a number.
 *
 * ## Policy
 *
 * `--policy` is the second thing being measured. pjmedia never calls
 * `WebRtcNs_set_policy`, so the suppressor runs at the `aggrMode = 0` that
 * `WebRtcNs_InitCore` assigns (`ns_core.c:134`), whose `denoiseBound = 0.5f` floors the
 * Wiener gain at 0.5 and so caps attenuation at about 6 dB per bin. Omitting the flag
 * reproduces that exactly; passing 0..3 is the counterfactual.
 *
 * Usage:
 *     ns48 <in.wav> <out.wav> [--policy 0|1|2|3]
 *
 * Input must be 16-bit PCM, mono, at a rate `WebRtcNs_InitCore` accepts (8/16/32/48 kHz).
 * Samples past the last whole 160-sample sub-frame are copied through unprocessed, which
 * is what a real capture path does with a partial frame too.
 */

#include "webrtc/modules/audio_processing/ns/include/noise_suppression.h"

#define SUBFRAME 160  /* echo_webrtc.c: subframe_len for every clock rate above 8000 */

#include "wav.h"

/* ---------------------------------------------------------------------- main */

int main(int argc, char **argv)
{
    const char *in_path = NULL, *out_path = NULL;
    int policy = -1;              /* -1 = do not call set_policy: what pjmedia does */
    int i;
    wav w = {0};
    NsHandle *ns;
    uint32_t off;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--policy") == 0 && i + 1 < argc) {
            policy = atoi(argv[++i]);
            if (policy < 0 || policy > 3) {
                fprintf(stderr, "ns48: --policy takes 0..3 "
                                "(omit it for pjmedia's behaviour, which is mode 0)\n");
                return 2;
            }
        } else if (!in_path)  { in_path  = argv[i];
        } else if (!out_path) { out_path = argv[i];
        } else {
            fprintf(stderr, "ns48: unexpected argument %s\n", argv[i]);
            return 2;
        }
    }
    if (!in_path || !out_path) {
        fprintf(stderr, "usage: ns48 <in.wav> <out.wav> [--policy 0|1|2|3]\n");
        return 2;
    }

    if (!wav_read(in_path, &w)) return 1;

    ns = WebRtcNs_Create();
    if (!ns) { fprintf(stderr, "ns48: WebRtcNs_Create failed\n"); return 1; }

    /* The same call echo_webrtc.c:195 makes, with the same argument: the device clock
     * rate, unsplit. A rate it rejects is a rate the handset would also have failed on,
     * and it fails here the same way - loudly, rather than by processing nothing. */
    if (WebRtcNs_Init(ns, (uint32_t)w.rate) != 0) {
        fprintf(stderr, "ns48: WebRtcNs_Init rejected %d Hz "
                        "(ns_core.c accepts 8000/16000/32000/48000)\n", w.rate);
        WebRtcNs_Free(ns);
        return 1;
    }
    if (policy >= 0 && WebRtcNs_set_policy(ns, policy) != 0) {
        fprintf(stderr, "ns48: WebRtcNs_set_policy(%d) failed\n", policy);
        WebRtcNs_Free(ns);
        return 1;
    }

    for (off = 0; off + SUBFRAME <= w.frames; off += SUBFRAME) {
        float fin[SUBFRAME], fout[SUBFRAME];
        const float *in_bands[1]  = { fin };
        float       *out_bands[1] = { fout };
        int j;

        /* int16 -> float. echo_webrtc.c does exactly this widening into its own
         * `tmp_buf` and keeps full int16 scale; WebRTC's float NS expects that scale,
         * not a normalised [-1, 1]. */
        for (j = 0; j < SUBFRAME; j++) fin[j] = (float)w.pcm[off + j];

        WebRtcNs_Analyze(ns, fin);
        WebRtcNs_Process(ns, in_bands, 1, out_bands);

        for (j = 0; j < SUBFRAME; j++) {
            float v = fout[j];
            if (v >  32767.0f) v =  32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            w.pcm[off + j] = (int16_t)(v < 0 ? v - 0.5f : v + 0.5f);
        }
    }

    if (!wav_write(out_path, &w)) { WebRtcNs_Free(ns); free(w.pcm); return 1; }

    fprintf(stderr, "ns48: %u frames at %d Hz, %u sub-frames, policy %s\n",
            w.frames, w.rate, w.frames / SUBFRAME,
            policy < 0 ? "default (0, as pjmedia leaves it)" :
            policy == 0 ? "0" : policy == 1 ? "1" : policy == 2 ? "2" : "3");

    WebRtcNs_Free(ns);
    free(w.pcm);
    return 0;
}
