/* 
 * Capture-path speech enhancement - RNNoise backend.
 *
 * This file is NOT upstream pjproject. It is added by
 * pjsip/patches/0007-capture-path-speech-enhancement.patch.
 */
#include <pjmedia/speech_enh.h>
#include <pjmedia/errno.h>
#include <pj/assert.h>
#include <pj/log.h>
#include <pj/pool.h>
#include <pj/string.h>

#define THIS_FILE   "speech_enh.c"

#if defined(PJMEDIA_HAS_RNNOISE) && PJMEDIA_HAS_RNNOISE != 0

#include <rnnoise.h>

/* RNNoise's one and only sampling rate. Not a tunable: the band layout the model was
 * trained on is defined in terms of 48 kHz bins, so a different rate is a different model
 * and there is not one.
 */
#define RNNOISE_CLOCK_RATE  48000

struct pjmedia_speech_enh
{
    DenoiseState    *st;
    unsigned         block;             /* rnnoise_get_frame_size(), 480 at 48 kHz   */
    unsigned         samples_per_frame;
    float            speech_prob;       /* the most recent block's, or -1            */

    /* Scratch for the int16 <-> float conversion. Sized at create time from the
     * backend's block size and never reallocated, because this is touched once per
     * captured frame on the audio thread and an allocation there is a glitch.
     */
    float           *in_buf;
    float           *out_buf;
};


PJ_DEF(pj_status_t) pjmedia_speech_enh_create(pj_pool_t *pool,
                                             unsigned clock_rate,
                                             unsigned channel_count,
                                             unsigned samples_per_frame,
                                             unsigned options,
                                             pjmedia_speech_enh **p_enh)
{
    pjmedia_speech_enh *enh;
    unsigned block;

    PJ_ASSERT_RETURN(pool && p_enh, PJ_EINVAL);
    PJ_UNUSED_ARG(options);

    *p_enh = NULL;

    /* Every rejection below is a format this backend cannot process, and every one of them
     * returns PJ_ENOTSUP so the caller runs unenhanced rather than failing the call. They
     * are logged at level 3 because a silently absent denoiser is indistinguishable from a
     * denoiser that is not working, and that cost a day the last time it happened to the
     * echo canceller.
     */
    if (clock_rate != RNNOISE_CLOCK_RATE) {
        PJ_LOG(3,(THIS_FILE, "No speech enhancement: RNNoise is a %d Hz model and the "
                             "capture stream is %d Hz",
                             RNNOISE_CLOCK_RATE, clock_rate));
        return PJ_ENOTSUP;
    }
    if (channel_count != 1) {
        PJ_LOG(3,(THIS_FILE, "No speech enhancement: RNNoise is mono and the capture "
                             "stream has %d channels", channel_count));
        return PJ_ENOTSUP;
    }

    block = (unsigned)rnnoise_get_frame_size();
    if (block == 0 || samples_per_frame % block != 0) {
        /* A partial block cannot be processed and must not be silently dropped, so a
         * frame size that is not a whole number of blocks is refused outright rather
         * than handled by leaving a tail unprocessed every 20 ms - which would be a
         * periodic artefact at the frame rate, i.e. an audible buzz.
         */
        PJ_LOG(3,(THIS_FILE, "No speech enhancement: frame is %d samples, which is not a "
                             "whole multiple of RNNoise's %d-sample block",
                             samples_per_frame, block));
        return PJ_ENOTSUP;
    }

    enh = PJ_POOL_ZALLOC_T(pool, pjmedia_speech_enh);
    PJ_ASSERT_RETURN(enh, PJ_ENOMEM);

    enh->block = block;
    enh->samples_per_frame = samples_per_frame;
    enh->speech_prob = -1.0f;
    enh->in_buf  = (float*) pj_pool_alloc(pool, block * sizeof(float));
    enh->out_buf = (float*) pj_pool_alloc(pool, block * sizeof(float));
    PJ_ASSERT_RETURN(enh->in_buf && enh->out_buf, PJ_ENOMEM);

    /* NULL = the model compiled into librnnoise (src/rnnoise_data.c). There is no model
     * file to find on the filesystem, which is the whole reason this backend was chosen
     * over a TFLite one: nothing to package into the APK and nothing to fail to load.
     */
    enh->st = rnnoise_create(NULL);
    if (!enh->st) {
        PJ_LOG(3,(THIS_FILE, "No speech enhancement: rnnoise_create failed"));
        return PJ_ENOMEM;
    }

    PJ_LOG(4,(THIS_FILE, "Speech enhancement active: RNNoise, %d Hz, %d-sample blocks, "
                         "%d per frame",
                         clock_rate, block, samples_per_frame / block));

    *p_enh = enh;
    return PJ_SUCCESS;
}


PJ_DEF(pj_status_t) pjmedia_speech_enh_capture(pjmedia_speech_enh *enh,
                                              pj_int16_t *frame)
{
    unsigned off;

    PJ_ASSERT_RETURN(enh && frame, PJ_EINVAL);

    for (off = 0; off + enh->block <= enh->samples_per_frame; off += enh->block) {
        unsigned i;

        /* RNNoise takes float at int16 scale, NOT normalised to [-1,1] - the same
         * convention WebRTC's float noise suppressor uses in echo_webrtc.c, which is why
         * neither of them scales.
         */
        for (i = 0; i < enh->block; ++i)
            enh->in_buf[i] = (float) frame[off + i];

        enh->speech_prob = rnnoise_process_frame(enh->st, enh->out_buf, enh->in_buf);

        for (i = 0; i < enh->block; ++i) {
            float v = enh->out_buf[i];

            /* Clamp before narrowing. RNNoise's synthesis can overshoot the input's peak
             * on a transient, and a float above 32767 narrowed to pj_int16_t wraps to a
             * large negative number - which is a click, not a quiet distortion.
             */
            if (v >  32767.0f) v =  32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            frame[off + i] = (pj_int16_t) (v < 0 ? v - 0.5f : v + 0.5f);
        }
    }

    return PJ_SUCCESS;
}


PJ_DEF(float) pjmedia_speech_enh_get_speech_prob(pjmedia_speech_enh *enh)
{
    PJ_ASSERT_RETURN(enh, -1.0f);
    return enh->speech_prob;
}


PJ_DEF(pj_status_t) pjmedia_speech_enh_destroy(pjmedia_speech_enh *enh)
{
    PJ_ASSERT_RETURN(enh, PJ_EINVAL);

    if (enh->st) {
        rnnoise_destroy(enh->st);
        enh->st = NULL;
    }
    /* enh itself, and both scratch buffers, are pool memory and go with the pool. */
    return PJ_SUCCESS;
}

#else   /* PJMEDIA_HAS_RNNOISE */

/* No backend compiled in. The symbols still exist so that sound_port.c needs no
 * conditional compilation of its own: it calls create(), gets PJ_ENOTSUP, holds a NULL
 * pointer, and its capture callback is then byte-for-byte the one that shipped before this
 * patch. That is the fallback path, and it is this short on purpose - a fallback with
 * branches in it is a second implementation to get wrong.
 */

PJ_DEF(pj_status_t) pjmedia_speech_enh_create(pj_pool_t *pool,
                                             unsigned clock_rate,
                                             unsigned channel_count,
                                             unsigned samples_per_frame,
                                             unsigned options,
                                             pjmedia_speech_enh **p_enh)
{
    PJ_UNUSED_ARG(pool);
    PJ_UNUSED_ARG(clock_rate);
    PJ_UNUSED_ARG(channel_count);
    PJ_UNUSED_ARG(samples_per_frame);
    PJ_UNUSED_ARG(options);
    PJ_ASSERT_RETURN(p_enh, PJ_EINVAL);
    *p_enh = NULL;
    return PJ_ENOTSUP;
}

PJ_DEF(pj_status_t) pjmedia_speech_enh_capture(pjmedia_speech_enh *enh,
                                              pj_int16_t *frame)
{
    PJ_UNUSED_ARG(enh);
    PJ_UNUSED_ARG(frame);
    return PJ_ENOTSUP;
}

PJ_DEF(float) pjmedia_speech_enh_get_speech_prob(pjmedia_speech_enh *enh)
{
    PJ_UNUSED_ARG(enh);
    return -1.0f;
}

PJ_DEF(pj_status_t) pjmedia_speech_enh_destroy(pjmedia_speech_enh *enh)
{
    PJ_UNUSED_ARG(enh);
    return PJ_ENOTSUP;
}

#endif  /* PJMEDIA_HAS_RNNOISE */
