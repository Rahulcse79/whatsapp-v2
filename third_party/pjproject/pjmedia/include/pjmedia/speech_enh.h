/* 
 * Capture-path speech enhancement.
 *
 * This file is NOT upstream pjproject. It is added by
 * pjsip/patches/0007-capture-path-speech-enhancement.patch; see that patch for the
 * reasoning and the measurements behind it.
 */
#ifndef __PJMEDIA_SPEECH_ENH_H__
#define __PJMEDIA_SPEECH_ENH_H__

/**
 * @file speech_enh.h
 * @brief Machine-learning speech enhancement for the capture path.
 */
#include <pjmedia/types.h>

PJ_BEGIN_DECL

/**
 * @defgroup PJMEDIA_SPEECH_ENH Speech enhancement
 * @ingroup PJMEDIA_PORT
 * @brief Removes background noise from captured audio
 * @{
 *
 * A single-purpose denoiser for the capture path, sitting after the echo canceller and
 * before the frame is handed to the conference bridge. It is deliberately NOT a
 * pjmedia_port: it is driven from pjmedia_snd_port's capture callback, which is the one
 * place every captured frame passes exactly once, so in a multi-party call the cost is
 * paid once rather than once per leg.
 *
 * The backend is RNNoise, and it is compiled in only when PJMEDIA_HAS_RNNOISE is non-zero.
 * With it zero, pjmedia_speech_enh_create() returns PJ_ENOTSUP and every caller is
 * expected to carry on without enhancement - which is the same path taken when the
 * backend is present but refuses the stream's format.
 */

/**
 * Opaque enhancer instance.
 */
typedef struct pjmedia_speech_enh pjmedia_speech_enh;


/**
 * Create a speech enhancer for a capture stream.
 *
 * The enhancer is strict about format and reports PJ_ENOTSUP rather than resampling or
 * reframing, because both would be a hidden second signal path in the middle of the one
 * being measured. The caller's correct response to PJ_ENOTSUP is to run without an
 * enhancer, and a failure here must never fail the call.
 *
 * @param pool              Pool to allocate the instance from. The backend's own state is
 *                          NOT pool-allocated and is released by
 *                          pjmedia_speech_enh_destroy(), which the caller must call.
 * @param clock_rate        Sampling rate. RNNoise is a 48000 Hz model; anything else is
 *                          PJ_ENOTSUP.
 * @param channel_count     Must be 1.
 * @param samples_per_frame Samples per frame, which must be a whole multiple of the
 *                          backend's block size (480 samples, 10 ms, at 48 kHz).
 * @param options           Reserved; pass 0.
 * @param p_enh             Receives the instance.
 *
 * @return                  PJ_SUCCESS, or PJ_ENOTSUP when no backend is compiled in or the
 *                          format is one it cannot process, or PJ_ENOMEM.
 */
PJ_DECL(pj_status_t) pjmedia_speech_enh_create(pj_pool_t *pool,
                                              unsigned clock_rate,
                                              unsigned channel_count,
                                              unsigned samples_per_frame,
                                              unsigned options,
                                              pjmedia_speech_enh **p_enh);

/**
 * Enhance one captured frame, in place.
 *
 * Call from the capture callback and from nowhere else: the instance keeps per-stream
 * recurrent state and carries no lock, exactly as pjmedia_echo_capture() does on the same
 * thread in the same callback.
 *
 * @param enh       The enhancer.
 * @param frame     samples_per_frame 16-bit samples, replaced by the enhanced ones.
 *
 * @return          PJ_SUCCESS. A frame is never dropped and never left half-processed.
 */
PJ_DECL(pj_status_t) pjmedia_speech_enh_capture(pjmedia_speech_enh *enh,
                                               pj_int16_t *frame);

/**
 * The backend's speech probability for the most recent frame, in [0.0, 1.0].
 *
 * This is a voice activity detector that has already been paid for: RNNoise computes it
 * per block as part of denoising, so reading it costs nothing. Returns -1.0 if no frame
 * has been processed yet.
 *
 * @param enh       The enhancer.
 *
 * @return          Speech probability, or -1.0.
 */
PJ_DECL(float) pjmedia_speech_enh_get_speech_prob(pjmedia_speech_enh *enh);

/**
 * Destroy the enhancer and release the backend's state.
 *
 * @param enh       The enhancer.
 *
 * @return          PJ_SUCCESS.
 */
PJ_DECL(pj_status_t) pjmedia_speech_enh_destroy(pjmedia_speech_enh *enh);

/**
 * @}
 */

PJ_END_DECL

#endif  /* __PJMEDIA_SPEECH_ENH_H__ */
