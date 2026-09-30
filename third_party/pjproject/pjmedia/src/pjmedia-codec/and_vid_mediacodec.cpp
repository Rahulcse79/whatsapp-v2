/*
 * Copyright (C)2020 Teluu Inc. (http://www.teluu.com)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

#include <pjmedia-codec/and_vid_mediacodec.h>
#include <pjmedia-codec/h264_packetizer.h>
#include <pjmedia-codec/vpx_packetizer.h>
#include <pjmedia/vid_codec_util.h>
#include <pjmedia/errno.h>
#include <pj/atomic_queue.h>
#include <pj/log.h>
#include <pj/os.h>
#include <pj/string.h>

#if defined(PJMEDIA_HAS_ANDROID_MEDIACODEC) && \
            PJMEDIA_HAS_ANDROID_MEDIACODEC != 0 && \
    defined(PJMEDIA_HAS_VIDEO) && (PJMEDIA_HAS_VIDEO != 0)

#include <android/log.h>

/* Android AMediaCodec: */
#include "media/NdkMediaCodec.h"

/* For enumerating codecs: the NDK has no MediaCodecList before API 36. */
#include <jni.h>

/*
 * Constants
 */
#define THIS_FILE                   "and_vid_mediacodec.cpp"
#define AND_MEDIA_KEY_COLOR_FMT     "color-format"
#define AND_MEDIA_KEY_WIDTH         "width"
#define AND_MEDIA_KEY_HEIGHT        "height"
#define AND_MEDIA_KEY_BIT_RATE      "bitrate"
#define AND_MEDIA_KEY_PROFILE       "profile"
#define AND_MEDIA_KEY_FRAME_RATE    "frame-rate"
#define AND_MEDIA_KEY_IFR_INTERVAL  "i-frame-interval"
#define AND_MEDIA_KEY_MIME          "mime"
#define AND_MEDIA_KEY_REQUEST_SYNCF "request-sync"
#define AND_MEDIA_KEY_CSD0          "csd-0"
#define AND_MEDIA_KEY_CSD1          "csd-1"
#define AND_MEDIA_KEY_MAX_INPUT_SZ  "max-input-size"
#define AND_MEDIA_KEY_ENCODER       "encoder"
#define AND_MEDIA_KEY_PRIORITY      "priority"
#define AND_MEDIA_KEY_STRIDE        "stride"
#define AND_MEDIA_KEY_LATENCY       "latency"
#define AND_MEDIA_KEY_LOW_LATENCY   "low-latency"
#define AND_MEDIA_I420_PLANAR_FMT   0x13
#define AND_MEDIA_QUEUE_TIMEOUT     2000*100

#define DEFAULT_WIDTH           352
#define DEFAULT_HEIGHT          288

#define DEFAULT_FPS             15
#define DEFAULT_AVG_BITRATE     256000
#define DEFAULT_MAX_BITRATE     256000

#define SPS_PPS_BUF_SIZE        64

#define MAX_RX_WIDTH            1280
#define MAX_RX_HEIGHT           800

/* Maximum duration from one key frame to the next (in seconds). */
#define KEYFRAME_INTERVAL       1

#define AND_MED_H264_PT         PJMEDIA_RTP_PT_H264_RSV2
#define AND_MED_VP8_PT          PJMEDIA_RTP_PT_VP8_RSV1
#define AND_MED_VP9_PT          PJMEDIA_RTP_PT_VP9_RSV1

#define BUFFER_MAX_ITEM         16



/* Slots an and_med_buf_info queue can hold before a put overwrites the oldest.
 *
 * BUFFER_MAX_ITEM slots, one of which is spent distinguishing full from empty:
 * pj_atomic_queue's put() advances the READ pointer when the next write would
 * land on it, so the sixteenth item silently destroys the first. That is not an
 * error anybody sees -- put() returns PJ_SUCCESS either way and there is no
 * counter for it upstream -- and for a queue of MediaCodec buffer INDICES it is
 * a permanent leak: the index that was overwritten is one this codec now never
 * releases, and MediaCodec's pool is finite. The ledger below counts those.
 */
#define BUFFER_QUEUE_CAPACITY   (BUFFER_MAX_ITEM - 1)

/* How often a starved or leaking codec says so. Per frame is a flood that hides
 * the counters it is meant to surface; at 30 fps this is one line per 10 s. */
#define AND_MEDIA_IN_REPORT_EVERY  300

/* Prefer software components for a format that is encoded AND decoded at the
 * same time.
 *
 * A real-time call runs both directions of one format concurrently, and on this
 * project's handsets the accelerated pair could not be validated as
 * concurrently stable:
 *
 *   hardware encoder + hardware decoder   encode 4.4 fps, decode 0.5 fps, and
 *                                         the encoder recovered to 30 fps
 *                                         within three seconds of the decoder
 *                                         going idle -- one engine, serialised
 *   hardware encoder + software decoder   nondeterministic: the same build and
 *                                         call gave decode 28.8 fps once and a
 *                                         dead decoder the next time
 *                                         (in-cb 4, null 3, out-cb 0, C2 error)
 *   software encoder + software decoder   the configuration the far handset has
 *                                         run in every measurement without a
 *                                         single failure
 *
 * There is no way to validate concurrency at selection time without a
 * configure-and-start probe, and this file already refuses those: one wedged
 * the PJSIP thread on an SM-E236B and the account never registered. So the
 * choice is made deterministically rather than discovered per device, and it is
 * made on the side of a pipeline that always runs.
 *
 * This is a POLICY, not a device rule: no model, SoC, Android version or
 * component name is consulted, and the only input is the platform's own
 * isHardwareAccelerated(). A device whose accelerated pair is fine pays a lower
 * frame rate for it; correctness and a pipeline that never dies are worth more
 * at this checkpoint than peak fps, and revisiting it belongs with the adaptive
 * work, not here. Setting this to 0 restores hardware-first for both halves.
 */
#ifndef AND_MEDIA_BIDIR_PREFER_SOFTWARE
#   define AND_MEDIA_BIDIR_PREFER_SOFTWARE  1
#endif

#define API_AT_LEAST(x) __builtin_available(android x, *)

typedef struct and_med_buf_info {
    pj_int32_t index;
    pj_int32_t size;
    pj_uint32_t flags;
} and_med_buf_info;

/*
 * Factory operations.
 */
static pj_status_t and_media_test_alloc(pjmedia_vid_codec_factory *factory,
                                    const pjmedia_vid_codec_info *info );
static pj_status_t and_media_default_attr(pjmedia_vid_codec_factory *factory,
                                      const pjmedia_vid_codec_info *info,
                                      pjmedia_vid_codec_param *attr );
static pj_status_t and_media_enum_info(pjmedia_vid_codec_factory *factory,
                                   unsigned *count,
                                   pjmedia_vid_codec_info codecs[]);
static pj_status_t and_media_alloc_codec(pjmedia_vid_codec_factory *factory,
                                     const pjmedia_vid_codec_info *info,
                                     pjmedia_vid_codec **p_codec);
static pj_status_t and_media_dealloc_codec(pjmedia_vid_codec_factory *factory,
                                       pjmedia_vid_codec *codec );


/*
 * Codec operations
 */
static pj_status_t and_media_codec_init(pjmedia_vid_codec *codec,
                                    pj_pool_t *pool );
static pj_status_t and_media_codec_open(pjmedia_vid_codec *codec,
                                    pjmedia_vid_codec_param *param );
static pj_status_t and_media_codec_close(pjmedia_vid_codec *codec);
static pj_status_t and_media_codec_modify(pjmedia_vid_codec *codec,
                                      const pjmedia_vid_codec_param *param);
static pj_status_t and_media_codec_get_param(pjmedia_vid_codec *codec,
                                         pjmedia_vid_codec_param *param);
static pj_status_t and_media_codec_encode_begin(pjmedia_vid_codec *codec,
                                            const pjmedia_vid_encode_opt *opt,
                                            const pjmedia_frame *input,
                                            unsigned out_size,
                                            pjmedia_frame *output,
                                            pj_bool_t *has_more);
static pj_status_t and_media_codec_encode_more(pjmedia_vid_codec *codec,
                                           unsigned out_size,
                                           pjmedia_frame *output,
                                           pj_bool_t *has_more);
static pj_status_t and_media_codec_decode(pjmedia_vid_codec *codec,
                                      pj_size_t count,
                                      pjmedia_frame packets[],
                                      unsigned out_size,
                                      pjmedia_frame *output);

/* Definition for Android AMediaCodec operations. */
static pjmedia_vid_codec_op and_media_codec_op =
{
    &and_media_codec_init,
    &and_media_codec_open,
    &and_media_codec_close,
    &and_media_codec_modify,
    &and_media_codec_get_param,
    &and_media_codec_encode_begin,
    &and_media_codec_encode_more,
    &and_media_codec_decode,
    NULL
};

/* Definition for Android AMediaCodec factory operations. */
static pjmedia_vid_codec_factory_op and_media_factory_op =
{
    &and_media_test_alloc,
    &and_media_default_attr,
    &and_media_enum_info,
    &and_media_alloc_codec,
    &and_media_dealloc_codec
};

static struct and_media_factory
{
    pjmedia_vid_codec_factory    base;
    pjmedia_vid_codec_mgr       *mgr;
    pj_pool_factory             *pf;
    pj_pool_t                   *pool;
} and_media_factory;

enum and_media_frm_type {
    AND_MEDIA_FRM_TYPE_DEFAULT = 0,
    AND_MEDIA_FRM_TYPE_KEYFRAME = 1,
    AND_MEDIA_FRM_TYPE_CONFIG = 2
};

typedef struct h264_codec_data {
    pjmedia_h264_packetizer     *pktz;

    pj_uint8_t                   enc_sps_pps_buf[SPS_PPS_BUF_SIZE];
    unsigned                     enc_sps_pps_len;
    pj_bool_t                    enc_sps_pps_ex;

    pj_uint8_t                  *dec_sps_buf;
    unsigned                     dec_sps_len;
    pj_uint8_t                  *dec_pps_buf;
    unsigned                     dec_pps_len;
} h264_codec_data;

typedef struct vpx_codec_data {
    pjmedia_vpx_packetizer      *pktz;
} vpx_codec_data;

typedef struct and_media_codec_data
{
    pj_pool_t                   *pool;
    pj_uint8_t                   codec_idx;
    pjmedia_vid_codec_param     *prm;
    pj_bool_t                    whole;
    void                        *ex_data;

    /* Encoder state */
    AMediaCodec                 *enc;
    unsigned                     enc_input_size;
    pj_uint8_t                  *enc_frame_whole;
    unsigned                     enc_frame_size;
    unsigned                     enc_processed;
    AMediaCodecBufferInfo        enc_buf_info;
    int                          enc_output_buf_idx;
    /* Consecutive encode attempts that found no input buffer. A component that
     * never yields one is not busy, it is broken -- see and_media_enc_mark_bad. */
    unsigned                     enc_starved;
    /* Encoder swaps attempted during this open; see and_media_codec_open. */
    unsigned                     enc_retries;

    /* Decoder state */
    AMediaCodec                 *dec;
    pj_uint8_t                  *dec_buf;
    pj_uint8_t                  *dec_input_buf;
    unsigned                     dec_input_buf_len;
    pj_size_t                    dec_input_buf_max_size;
    pj_ssize_t                   dec_input_buf_idx;
    unsigned                     dec_has_output_frame;

    /* Consecutive decode calls that produced nothing. A decoder that opens and
     * never decodes -- a retired OMX name the platform still answers to -- looks
     * exactly like this, and is invisible at registration. Past
     * AND_MEDIA_DEC_DEAD_RUN the component is condemned so the NEXT call picks
     * another; the current call is left alone, because swapping a decoder under
     * a running stream is the uncontrolled mid-call swap this file already
     * refuses to do for encoders. */
    unsigned                     dec_no_output_run;

    /* Decoder input-buffer accounting (Phase 3 root-cause).
     *
     * "failed to get input Buffer [0]" says only that the available-index queue
     * was empty; it cannot say whether MediaCodec ever offered an index at all.
     * These separate the two, which are completely different defects: a callback
     * that never fires is an initialisation or component problem, and a callback
     * that fires while the queue still empties is a consumption/ownership one.
     */
    unsigned                     dec_cb_in;      /* onInputAvailable fired    */
    unsigned                     dec_in_ok;      /* an index was taken        */
    unsigned                     dec_in_empty;   /* queue had none            */
    unsigned                     dec_in_null;    /* index given, buffer NULL  */
    unsigned                     enc_cb_in;      /* the same, for the encoder */

    /* The output half of the same question.
     *
     * The input counters proved input is healthy -- every callback consumed,
     * no NULL buffer -- while the picture count stayed at zero, so the failing
     * boundary is at or after queueInputBuffer. These separate the two
     * remaining possibilities: a component that never offers an output buffer
     * (dec_cb_out stays 0) from one that offers buffers we mishandle
     * (dec_cb_out climbs while dec_out_empty climbs with it).
     */
    unsigned                     dec_cb_out;     /* onOutputAvailable fired   */
    unsigned                     enc_cb_out;     /* the same, for the encoder */
    unsigned                     dec_out_empty;  /* output queue had none     */
    unsigned                     dec_queued;     /* queueInputBuffer returned OK */
    pj_bool_t                    enc_keyframe_checked; /* start code verified  */

    /* The encoder output-buffer ledger.
     *
     * The invariant being measured: every index taken out of
     * enc_avail_output_buf reaches exactly one AMediaCodec_releaseOutputBuffer,
     * on every control-flow path. MediaCodec's output pool is finite and it
     * reports exhaustion by ceasing to offer INPUT buffers, so an unreleased
     * index shows up several seconds later and two functions away, as
     * "Encoder failed to get input Buffer". Nothing in the API says which
     * buffer was lost, so the accounting has to say it.
     *
     * Three numbers, not one, because there are three fates for an offered
     * buffer and only one of them is an error:
     *
     *   offered   (enc_cb_out)      onOutputAvailable fired
     *   acquired  (enc_out_acquired) dequeued by encode_begin, ours to release
     *   discarded (enc_out_discarded) overwritten in the queue, unreleasable
     *
     * offered - acquired - discarded is what is still queued, which is normal
     * and bounded by BUFFER_QUEUE_CAPACITY. acquired - released is what this
     * codec is holding right now: bounded and small if the paths are whole, and
     * monotonically climbing if one of them returns without releasing.
     * discarded is a leak with no upper bound and no other symptom.
     */
    unsigned                     enc_out_acquired;   /* dequeued by encode_begin */
    unsigned                     enc_out_released;   /* given back, any path      */
    unsigned                     enc_out_discarded;  /* overwritten while queued  */
    unsigned                     enc_out_held_max;   /* max acquired-released     */

    /* Which path gave a buffer back. These three sum to enc_out_released, so a
     * mismatch is itself a finding: it means a release site exists that this
     * ledger does not know about. */
    unsigned                     enc_rel_normal;     /* frame packetised in full  */
    unsigned                     enc_rel_error;      /* an error path, buffer kept */
    unsigned                     enc_rel_early;      /* encode_begin's on_return  */

    unsigned                     enc_in_ok;          /* input index taken         */
    unsigned                     enc_in_empty;       /* input queue had none      */

    unsigned                     enc_begin_calls;    /* encode_begin entered      */
    unsigned                     enc_begin_frames;   /* ...that produced a payload */
    unsigned                     enc_begin_empty;    /* ...that produced size 0   */

    unsigned                     enc_more_calls;     /* encode_more invocations   */
    unsigned                     enc_more_err;       /* ...that returned an error */
    unsigned                     enc_pktz_ok;        /* vpx_packetize succeeded   */
    unsigned                     enc_pktz_err;       /* ...refused                */

    /* Fragments of the frame currently in flight, and the high-water mark. A
     * frame is fragmented across as many encode_more calls as it has packets,
     * and the output buffer is held for all of them -- so this is how long the
     * hold lasts, in the only unit that matters. */
    unsigned                     enc_frags;
    unsigned                     enc_frags_max;
    unsigned                     enc_pkt_bytes;      /* packetised bytes, frame   */

    /* The +3-byte question, answered with numbers once per stream rather than
     * assumed: the budget encode_more is given, the MTU the packetizer enforces,
     * and the descriptor size this file writes. */
    pj_bool_t                    enc_budget_logged;
    pj_bool_t                    enc_leak_reported;  /* said once, at the moment */

    /* TRUE between acquiring an output buffer and releasing it.
     *
     * This is the invariant itself, held as one bit. A control-flow audit of
     * this file shows every path from the acquisition in encode_begin to a
     * release -- but the hold spans a RETURN to pjmedia when a frame needs more
     * than one RTP packet: encode_begin hands the buffer on to encode_more, and
     * vid_stream's put_frame loop is what calls encode_more again. If put_frame
     * ever leaves that loop while has_more is TRUE -- it has two such returns,
     * `RTP encode_rtp() error` and `Cannot allocate send entry` -- encode_more
     * is never called again for the frame and the buffer is lost with nothing in
     * this file executing. Finding a frame still open when the NEXT one is
     * acquired is how that becomes visible, and it names the abandoning caller
     * rather than the starved component that reports it 40 seconds later. */
    pj_bool_t                    enc_frame_open;
    unsigned                     enc_abandoned;      /* frames left unreleased  */
    pj_bool_t                    enc_last_has_more;  /* the hold's last answer  */

    /* VP8 bitstream validation at the decoder's input boundary.
     *
     * The question these answer is whether the bytes handed to MediaCodec are a
     * structurally valid VP8 frame, so that "the vendor decoder is broken" and
     * "we fed it rubbish" can be told apart without trusting either. */
    unsigned                     dec_vp8_ok;       /* tag + start code valid  */
    unsigned                     dec_vp8_bad;      /* structurally invalid    */

    /* Reassembly verdicts, one per picture offered by the stream.
     *
     * complete + drop_missing_head + drop_incomplete is every picture seen;
     * invalid_before_decode (dec_vp8_bad) must reach zero once the drops are
     * in, because a picture that keeps its head and has no holes cannot start
     * mid-partition. */
    /* Shape of the picture currently being assembled, so a frame that fails
     * validation AFTER passing the head/hole pre-pass can say what it was made
     * of rather than only what it became. */
    unsigned                     dec_pkt_count;
    unsigned                     dec_desc0;      /* packets[0].buf[0]        */
    unsigned                     dec_desc_len0;  /* descriptor bytes stripped */
    unsigned                     dec_first_size; /* packets[0].size          */
    unsigned                     dec_total_pay;  /* payload bytes accumulated */

    /* Set when a packet's bytes could not be taken into the input buffer, so
     * the picture is no longer whole. decode_vpx abandons it rather than
     * carrying on, because carrying on restarts the frame at the NEXT packet --
     * which is a mid-frame byte offset presented to the decoder as a frame. */
    pj_bool_t                    dec_pic_broken;

    /* Set once AMediaCodec_start() has returned AMEDIA_OK for that half.
     *
     * The async callbacks are armed in and_media_codec_open BEFORE
     * configure_encoder/configure_decoder run configure() and start(), so an
     * onInputAvailable can arrive for a component that is not started yet. The
     * index it carries is not usable: getInputBuffer() answers NULL, and the
     * component still counts the buffer as outstanding. With a pool of four
     * that is fatal -- measured on an SM-M146B where a decoder recreated by a
     * video off/on took 4 input callbacks, 3 of them NULL, and then never
     * received another for the rest of the call (2026-09-26).
     *
     * Buffers offered before start are therefore not enqueued at all. The
     * component re-offers them once it is running, which is the only state in
     * which they mean anything. */
    pj_bool_t                    enc_started;
    pj_bool_t                    dec_started;
    unsigned                     cb_before_start;
    unsigned                     trace_in_ev;    /* bounded event counters  */
    unsigned                     trace_out_ev;

    /* Whether the decoder holds the reference picture the SENDER believes it
     * holds. Cleared whenever the decoder is started, set by a complete
     * keyframe, and -- since 2026-09-30 -- cleared again by any picture this
     * file refuses to hand over.
     *
     * ## The start rule, which came first
     *
     * A VP8 decoder that has just been created holds no reference frame, so an
     * inter-frame is not decodable by it -- and feeding one is not merely
     * useless, it kills the component: c2.android.vp8.decoder accepted a
     * 7126-byte inter-frame as the first input of a decoder recreated by a
     * video off/on and then stopped responding entirely, returning no buffer
     * for any further index and reporting C2_CORRUPTED (2026-09-26). A fresh
     * call happens to open on a keyframe, which is why this only showed up on
     * recreation and, intermittently, on a call joining mid-GOP.
     *
     * ## Why a start rule was not enough
     *
     * The checks below are careful never to hand the decoder a partial picture:
     * a missing frame start, an interior RTP hole, a truncated tail and an
     * input buffer that could not be taken all drop the picture entire. That is
     * correct, and on its own it is exactly half of the problem, because the
     * decoder's reference is a chain: the picture that was dropped was the one
     * the NEXT inter-frame is coded against. Dropping one picture and then
     * feeding the next is handing the decoder a difference against a frame it
     * does not have.
     *
     * The component does not refuse that. It decodes it, returns a picture, and
     * the error propagates into every inter-frame after it -- so the stream
     * reads healthy at every counter a black tile would trip (RTP arriving,
     * jitter buffer draining, decode fps non-zero, a frame reaching the
     * renderer) while the picture is macroblock rubble. Measured in a
     * three-party mesh on 2026-09-30: one tile persistently mosaic for minutes,
     * its neighbour on the same handset perfectly clean, audio unaffected.
     *
     * Nothing recovered it because nothing required a keyframe: this flag was
     * set once, at the first keyframe of the stream, and never cleared again
     * for the life of the decoder. So the start rule -- "no inter-frames until
     * a keyframe" -- was enforced exactly once, when it was needed after every
     * loss.
     *
     * Clearing it on a drop makes the same rule the recovery rule. While it is
     * false the checks below refuse dependent inter-frames and the picture-less
     * decode publishes PJMEDIA_EVENT_KEYFRAME_MISSING, which is what
     * `vid_stream` turns into a bounded RTCP-FB PLI; it goes true again only for
     * a keyframe that has already passed every completeness check, so a keyframe
     * that itself lost a packet leaves the stream in recovery rather than
     * ending it. No new request path, no new timer, and no PLI this file sends
     * itself.
     *
     * It does not depend on the PLI arriving. The encoder is configured with
     * `i-frame-interval` = KEYFRAME_INTERVAL = 1 second, so a keyframe is coming
     * regardless; the PLI only makes it sooner. A tile therefore holds its last
     * good picture for up to a second instead of decoding rubble for minutes.
     */
    pj_bool_t                    dec_seen_keyframe;
    unsigned                     vp8_frames_drop_no_keyframe;

    /* Recovery bookkeeping, per stream because this struct is per codec.
     *
     * `dec_ref_losses` counts chain breaks, `dec_ref_recoveries` the keyframes
     * that ended them; the two are equal on a healthy stream that has finished
     * recovering, and a gap between them is a stream still waiting. `_at` is
     * stamped at the loss so the recovery can report how long it took, which is
     * the number that says whether the bound is a second or a minute. */
    unsigned                     dec_ref_losses;
    unsigned                     dec_ref_recoveries;
    pj_timestamp                 dec_ref_lost_at;

    unsigned                     vp8_frames_complete;
    unsigned                     vp8_frames_drop_missing_head;
    unsigned                     vp8_frames_drop_incomplete;
    unsigned                     dec_empty_queued; /* 0-byte frames handed in */
    pj_bool_t                    dec_vp8_said;     /* first bad one reported  */

    /* The decoder's output queue has the same overwrite hazard. */
    unsigned                     dec_out_acquired;
    unsigned                     dec_out_released;
    unsigned                     dec_out_discarded;
    unsigned                     dec_stride_len;
    unsigned                     dec_buf_size;
    AMediaCodecBufferInfo        dec_buf_info;

    pj_atomic_queue_t           *enc_avail_input_buf;
    pj_atomic_queue_t           *enc_avail_output_buf;
    pj_atomic_queue_t           *dec_avail_input_buf;
    pj_atomic_queue_t           *dec_avail_output_buf;

    pj_bool_t                    format_changed;
    pjmedia_rect_size            new_size;
    int                          new_stride;
} and_media_codec_data;

/* Custom callbacks. */

/* This callback is useful when specific method is needed when opening
 * the codec (e.g: applying fmtp or setting up the packetizer)
 */
typedef pj_status_t (*open_cb)(and_media_codec_data *and_media_data);

/* This callback is useful for handling configure frame produced by encoder.
 * Output frame might want to be stored the configuration frame and append it
 * to a keyframe for sending later (e.g: on H264 codec). The default behavior
 * is to send the configuration frame regardless.
 */
typedef pj_status_t (*process_encode_cb)(and_media_codec_data *and_media_data);

/* This callback is to process more encoded packets/payloads from the codec.*/
typedef pj_status_t(*encode_more_cb)(and_media_codec_data *and_media_data,
                                     unsigned out_size,
                                     pjmedia_frame *output,
                                     pj_bool_t *has_more);

/* This callback is to decode packets. */
typedef pj_status_t(*decode_cb)(pjmedia_vid_codec *codec,
                                pj_size_t count,
                                pjmedia_frame packets[],
                                unsigned out_size,
                                pjmedia_frame *output);

/**
 * Called when an input buffer becomes available.
 * The specified index is the index of the available input buffer.
 */
static void and_med_on_input_avail(AMediaCodec *codec,
                                   void *userdata,
                                   int32_t index)
{
    and_media_codec_data *and_media_data = (and_media_codec_data *) userdata;
    and_med_buf_info buf_info;
    pj_atomic_queue_t *buf_queue;

    pj_bzero(&buf_info, sizeof(buf_info));
    if (codec == and_media_data->enc) {
        ++and_media_data->enc_cb_in;
        buf_queue = and_media_data->enc_avail_input_buf;
    } else {
        ++and_media_data->dec_cb_in;
        buf_queue = and_media_data->dec_avail_input_buf;
    }
    buf_info.index = index;
    pj_atomic_queue_put(buf_queue, &buf_info);
}
/**
 * Called when an output buffer becomes available.
 * The specified index is the index of the available output buffer.
 */
static void and_med_on_output_avail(AMediaCodec *codec,
                                    void *userdata,
                                    int32_t index,
                                    AMediaCodecBufferInfo *bufferInfo)
{
    and_media_codec_data *and_media_data = (and_media_codec_data *) userdata;
    and_med_buf_info buf_info;
    pj_atomic_queue_t *buf_queue;

    pj_bzero(&buf_info, sizeof(buf_info));
    if (codec == and_media_data->enc) {
        /* Counted before the put, because the put is where an index can be
         * destroyed. depth is what the queue holds right now -- offered, less
         * what the media thread has taken, less what earlier puts already
         * overwrote -- and a put at capacity overwrites the head. The reads of
         * enc_out_acquired cross threads and are deliberately unsynchronised:
         * this is a ledger, and a count that is off by one frame still answers
         * the question a count that is off by a pool does not. */
        unsigned depth = and_media_data->enc_cb_out -
                         and_media_data->enc_out_acquired -
                         and_media_data->enc_out_discarded;

        ++and_media_data->enc_cb_out;
        if (depth >= BUFFER_QUEUE_CAPACITY)
            ++and_media_data->enc_out_discarded;
        buf_queue = and_media_data->enc_avail_output_buf;
    } else {
        unsigned depth = and_media_data->dec_cb_out -
                         and_media_data->dec_out_acquired -
                         and_media_data->dec_out_discarded;

        ++and_media_data->dec_cb_out;
        if (depth >= BUFFER_QUEUE_CAPACITY)
            ++and_media_data->dec_out_discarded;
        buf_queue = and_media_data->dec_avail_output_buf;
    }
    buf_info.index = index;
    buf_info.size = bufferInfo->size;
    buf_info.flags = bufferInfo->flags;
    pj_atomic_queue_put(buf_queue, &buf_info);
}

/**
 * Called when the output format has changed.
 * The specified format contains the new output format.
 */
static void and_med_on_format_changed(AMediaCodec *codec,
                                      void *userdata,
                                      AMediaFormat *format)
{
    /* Zeroed, and each getter's answer believed only when it says it has one.
     *
     * AMediaFormat_getInt32 leaves the destination untouched when the key is
     * absent, and "stride" frequently is: this same callback reported
     * stride:-1275068304 for the encoder on an M14, which is stack garbage read
     * back. On the decoder side that number becomes dec_stride_len, and
     * write_yuv walks the component's output buffer in steps of it -- so an
     * absent key turns a working decoder into either a silent no-output (the
     * size guard rejects every frame) or a read off the end of the buffer.
     * Keeping the previous stride is the safe answer: a format change that does
     * not mention stride has not changed it.
     */
    int width = 0, height = 0, stride = 0;
    and_media_codec_data *and_media_data = (and_media_codec_data *) userdata;
    pj_bool_t got_w, got_h, got_stride;

    got_w = AMediaFormat_getInt32(format, AND_MEDIA_KEY_WIDTH, &width);
    got_h = AMediaFormat_getInt32(format, AND_MEDIA_KEY_HEIGHT, &height);
    got_stride = AMediaFormat_getInt32(format, AND_MEDIA_KEY_STRIDE, &stride);

    if (codec == and_media_data->dec && got_w && got_h &&
        width > 0 && height > 0)
    {
        and_media_data->format_changed = PJ_TRUE;
        and_media_data->new_size.w = width;
        and_media_data->new_size.h = height;
        /* No stride key means "unchanged", not "zero". A decoder that reports
         * only a size is reporting a tightly packed buffer of that width. */
        and_media_data->new_stride = (got_stride && stride > 0)?
                                     stride : (int)width;
    }
    __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
                        "[%s] On format changed w:%d h:%d stride:%d\r\n",
                        (codec==and_media_data->enc)?"encoder":"decoder",
                        width, height, stride);
}

/**
 * Called when the MediaCodec encountered an error.
 */
static void and_med_on_error(AMediaCodec *codec,
                         void *userdata,
                         media_status_t error,
                         int32_t actionCode,
                         const char *detail)
{
    and_media_codec_data *and_media_data = (and_media_codec_data *) userdata;
     __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
                        "[%s] On Media error : err[%d] code[%d] msg[%s]\r\n",
                        (codec==and_media_data->enc)?"encoder":"decoder", error,
                        actionCode, detail);
}

/* Custom callback implementation. */
#if PJMEDIA_HAS_AND_MEDIA_H264
static pj_status_t open_h264(and_media_codec_data *and_media_data);
static pj_status_t process_encode_h264(and_media_codec_data *and_media_data);
static pj_status_t encode_more_h264(and_media_codec_data *and_media_data,
                                    unsigned out_size,
                                    pjmedia_frame *output,
                                    pj_bool_t *has_more);
static pj_status_t decode_h264(pjmedia_vid_codec *codec,
                               pj_size_t count,
                               pjmedia_frame packets[],
                               unsigned out_size,
                               pjmedia_frame *output);
#endif

#if PJMEDIA_HAS_AND_MEDIA_VP8 || PJMEDIA_HAS_AND_MEDIA_VP9
static pj_status_t open_vpx(and_media_codec_data *and_media_data);
static pj_status_t encode_more_vpx(and_media_codec_data *and_media_data,
                                   unsigned out_size,
                                   pjmedia_frame *output,
                                   pj_bool_t *has_more);
static pj_status_t decode_vpx(pjmedia_vid_codec *codec,
                              pj_size_t count,
                              pjmedia_frame packets[],
                              unsigned out_size,
                              pjmedia_frame *output);
#endif


#if PJMEDIA_HAS_AND_MEDIA_H264
/* Codec2 names first in each list.
 *
 * Android 12 retired the OMX component names and Android 15 ships none of them: a
 * Galaxy M14 (SM-M146B, Android 15) advertises only c2.android.avc.encoder and
 * c2.exynos.h264.encoder. `AMediaCodec_createCodecByName("OMX.google.h264.encoder")`
 * there still hands back a handle, so codec_exists() accepts it and this file picks
 * it -- and every dequeueInputBuffer on it then fails with "Encoder failed to get
 * input Buffer[0]" for the life of the call. The camera runs and the preview runs,
 * while the stream carries 0 packets/s and the far end holds its first frame
 * (measured handset-to-handset, 2026-09-23).
 *
 * codec_exists() skips a name the device does not have, so listing the Codec2 names
 * in front costs a device that only has the OMX ones nothing.
 */
static pj_str_t H264_sw_encoder[] =
                                 {{(char *)"c2.android.avc.encoder\0", 22},
                                  {(char *)"OMX.google.h264.encoder\0", 23}};
static pj_str_t H264_hw_encoder[] =
                                 {{(char *)"c2.exynos.h264.encoder\0", 22},
                                  {(char *)"c2.qti.avc.encoder\0", 18},
                                  {(char *)"OMX.qcom.video.encoder.avc\0", 26},
                                  {(char *)"OMX.Exynos.avc.Encoder\0", 22}};
static pj_str_t H264_sw_decoder[] = {{(char *)"OMX.google.h264.decoder\0",
                                      23}};
static pj_str_t H264_hw_decoder[] =
                                  {{(char *)"OMX.qcom.video.decoder.avc\0", 26},
                                  {(char *)"OMX.Exynos.avc.dec\0", 18}};
#endif

#if PJMEDIA_HAS_AND_MEDIA_VP8
static pj_str_t VP8_sw_encoder[] = {{(char *)"OMX.google.vp8.encoder\0", 23}};
static pj_str_t VP8_hw_encoder[] =
                                 {{(char *)"OMX.qcom.video.encoder.vp8\0", 26},
                                 {(char *)"OMX.Exynos.vp8.Encoder\0", 22}};
static pj_str_t VP8_sw_decoder[] = {{(char *)"OMX.google.vp8.decoder\0", 23}};
static pj_str_t VP8_hw_decoder[] =
                                 {{(char *)"OMX.qcom.video.decoder.vp8\0", 26},
                                 {(char *)"OMX.Exynos.vp8.dec\0", 18}};
#endif

#if PJMEDIA_HAS_AND_MEDIA_VP9
static pj_str_t VP9_sw_encoder[] = {{(char *)"OMX.google.vp9.encoder\0", 23}};
static pj_str_t VP9_hw_encoder[] =
                                 {{(char *)"OMX.qcom.video.encoder.vp9\0", 26},
                                 {(char *)"OMX.Exynos.vp9.Encoder\0", 22}};
static pj_str_t VP9_sw_decoder[] = {{(char *)"OMX.google.vp9.decoder\0", 23}};
static pj_str_t VP9_hw_decoder[] =
                                 {{(char *)"OMX.qcom.video.decoder.vp9\0", 26},
                                 {(char *)"OMX.Exynos.vp9.dec\0", 18}};
#endif

static struct and_media_codec {
    int                enabled;           /* Is this codec enabled?          */
    const char        *name;              /* Codec name.                     */
    const char        *description;       /* Codec description.              */
    const char        *mime_type;         /* Mime type.                      */
    pj_str_t          *encoder_name;      /* Encoder name.                   */
    pj_str_t          *decoder_name;      /* Decoder name.                   */
    pj_uint8_t         pt;                /* Payload type.                   */
    pjmedia_format_id  fmt_id;            /* Format id.                      */
    pj_uint8_t         keyframe_interval; /* Keyframe interval.              */

    open_cb            open_codec;
    process_encode_cb  process_encode;
    encode_more_cb     encode_more;
    decode_cb          decode;

    pjmedia_codec_fmtp dec_fmtp;          /* Decoder's fmtp params.          */
}
and_media_codec[] = {
#if PJMEDIA_HAS_AND_MEDIA_H264
    {0, "H264", "Android MediaCodec H264 codec", "video/avc",
        NULL, NULL,
        AND_MED_H264_PT, PJMEDIA_FORMAT_H264, KEYFRAME_INTERVAL,
        &open_h264, &process_encode_h264, &encode_more_h264, &decode_h264,
        {2, {{{(char *)"profile-level-id", 16}, {(char *)"42e01e", 6}},
             {{(char *)" packetization-mode", 19}, {(char *)"1", 1}}}
        }
    },
#endif
#if PJMEDIA_HAS_AND_MEDIA_VP8
    {0, "VP8",  "Android MediaCodec VP8 codec", "video/x-vnd.on2.vp8",
        NULL, NULL,
        AND_MED_VP8_PT, PJMEDIA_FORMAT_VP8, KEYFRAME_INTERVAL,
        &open_vpx, NULL, &encode_more_vpx, &decode_vpx,
        {2, {{{(char *)"max-fr", 6}, {(char *)"30", 2}},
             {{(char *)" max-fs", 7}, {(char *)"580", 3}}}
        }
    },
#endif
#if PJMEDIA_HAS_AND_MEDIA_VP9
    {0, "VP9",  "Android MediaCodec VP9 codec", "video/x-vnd.on2.vp9",
        NULL, NULL,
        AND_MED_VP9_PT, PJMEDIA_FORMAT_VP9, KEYFRAME_INTERVAL,
        &open_vpx, NULL, &encode_more_vpx, &decode_vpx,
        {2, {{{(char *)"max-fr", 6}, {(char *)"30", 2}},
             {{(char *)" max-fs", 7}, {(char *)"580", 3}}}
        }
    }
#endif
};

static pj_status_t configure_encoder(and_media_codec_data *and_media_data)
{
    media_status_t am_status;
    AMediaFormat *vid_fmt;
    pjmedia_vid_codec_param *param = and_media_data->prm;

    vid_fmt = AMediaFormat_new();
    if (!vid_fmt) {
        PJ_LOG(4, (THIS_FILE, "Encoder failed creating media format"));
        return PJ_ENOMEM;
    }

    AMediaFormat_setString(vid_fmt, AND_MEDIA_KEY_MIME,
                          and_media_codec[and_media_data->codec_idx].mime_type);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_COLOR_FMT,
                          AND_MEDIA_I420_PLANAR_FMT);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_HEIGHT,
                          param->enc_fmt.det.vid.size.h);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_WIDTH,
                          param->enc_fmt.det.vid.size.w);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_BIT_RATE,
                          param->enc_fmt.det.vid.avg_bps);
    //AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_PROFILE, 1);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_IFR_INTERVAL,
                          KEYFRAME_INTERVAL);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_FRAME_RATE,
                          (param->enc_fmt.det.vid.fps.num /
                           param->enc_fmt.det.vid.fps.denum));
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_PRIORITY, 0);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_LATENCY, 1);

    /* Configure and start encoder. */
    am_status = AMediaCodec_configure(and_media_data->enc, vid_fmt, NULL, NULL,
                                      AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(vid_fmt);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Encoder configure failed, status=%d",
                   am_status));
        return PJMEDIA_CODEC_EFAILED;
    }
    and_media_data->enc_started = PJ_FALSE;
    am_status = AMediaCodec_start(and_media_data->enc);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Encoder start failed, status=%d",
                am_status));
        return PJMEDIA_CODEC_EFAILED;
    }
    and_media_data->enc_started = PJ_TRUE;
    return PJ_SUCCESS;
}

static pj_status_t configure_decoder(and_media_codec_data *and_media_data) {
    media_status_t am_status;
    AMediaFormat *vid_fmt;

    vid_fmt = AMediaFormat_new();
    if (!vid_fmt) {
        PJ_LOG(4, (THIS_FILE, "Decoder failed creating media format"));
        return PJ_ENOMEM;
    }
    AMediaFormat_setString(vid_fmt, AND_MEDIA_KEY_MIME,
                          and_media_codec[and_media_data->codec_idx].mime_type);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_COLOR_FMT,
                          AND_MEDIA_I420_PLANAR_FMT);
    /* A geometry that can actually contain the stream, not the negotiated
     * square.
     *
     * dec_fmt's size for VP8 is pjmedia's max-fs placeholder: max-fs is an AREA
     * in macroblocks with no aspect ratio, so vid_codec_util turns it into the
     * largest SQUARE that fits -- 1088x1088 here. A real 1280x720 stream is
     * legal under that area bound yet 192 pixels WIDER than the square, and a
     * decoder configured 1088 wide is then asked to decode 1280.
     *
     * The hardware component tolerated it. c2.android.vp8.decoder does not: on
     * an SM-M146B it answered its first four input callbacks with three NULL
     * buffers and never produced a picture, while the same component on the far
     * handset -- receiving 1088x612, which fits inside the square -- ran at
     * 25 fps all call (2026-09-26).
     *
     * So the configured geometry is widened to whatever this file is already
     * prepared to receive. MAX_RX_WIDTH/HEIGHT is the bound dec_buf_size is
     * sized from a few lines away, so this uses one number for "the largest
     * picture we accept" instead of two that disagree. The negotiated value is
     * still honoured when it is the larger of the two. */
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_HEIGHT,
                          PJ_MAX((int)and_media_data->prm->dec_fmt.det.vid.size.h,
                                 MAX_RX_HEIGHT));
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_WIDTH,
                          PJ_MAX((int)and_media_data->prm->dec_fmt.det.vid.size.w,
                                 MAX_RX_WIDTH));
    /* The real bound, not zero.
     *
     * Zero leaves the component to pick a default, and on an M14 that default
     * is about 4 KB. and_media_decode then splits any larger picture across
     * SEVERAL input buffers and queues each piece as its own frame -- so every
     * piece after the first is a mid-frame fragment, which is calling MediaCodec
     * with partial VP8 by a different route than packet loss. Observed at ~12%
     * loss (2026-09-25) on a picture whose head and packets were all intact:
     * 4 packets, S=1 PID=0, 4233 bytes assembled, 333 of them queued, and the
     * remainder read as a frame tag declaring version 3 and a 519672-byte first
     * partition.
     *
     * dec_buf_size is the buffer this codec already sizes for a whole encoded
     * picture, so it is the same bound the reassembly above enforces -- one
     * number, in one place, for what a frame may be. */
    /* Left at 0, which is upstream's value, after measuring the alternative.
     *
     * Declaring a real bound here was tried because a picture appeared to be
     * split across input buffers. It was not: that picture lost a packet to a
     * failed input-buffer acquisition, which the dec_pic_broken check in
     * decode_vpx now catches. The component ignores the hint in any case --
     * asked for 128 KB, 0.5 MB or 1.37 MB it reported max_size 7340032 every
     * time on an M14 (2026-09-26) -- so the setting buys nothing and the
     * measurement that motivated it had another cause. */
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_MAX_INPUT_SZ, 0);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_ENCODER, 0);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_PRIORITY, 0);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_LOW_LATENCY, 1);

    if (and_media_codec[and_media_data->codec_idx].fmt_id ==
        PJMEDIA_FORMAT_H264)
    {
        h264_codec_data *h264_data = (h264_codec_data *)and_media_data->ex_data;

        if (h264_data->dec_sps_len) {
            AMediaFormat_setBuffer(vid_fmt, AND_MEDIA_KEY_CSD0,
                                   h264_data->dec_sps_buf,
                                   h264_data->dec_sps_len);
        }
        if (h264_data->dec_pps_len) {
            AMediaFormat_setBuffer(vid_fmt, AND_MEDIA_KEY_CSD1,
                                   h264_data->dec_pps_buf,
                                   h264_data->dec_pps_len);
        }
    }
    am_status = AMediaCodec_configure(and_media_data->dec, vid_fmt, NULL,
                                      NULL, 0);

    AMediaFormat_delete(vid_fmt);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Decoder configure failed, status=%d, fmt_id=%d",
                   am_status, and_media_data->prm->dec_fmt.id));
        return PJMEDIA_CODEC_EFAILED;
    }

    and_media_data->dec_started = PJ_FALSE;
    am_status = AMediaCodec_start(and_media_data->dec);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Decoder start failed, status=%d",
                   am_status));
        return PJMEDIA_CODEC_EFAILED;
    }
    and_media_data->dec_started = PJ_TRUE;
    /* A new decoder has no reference frame; it must be given a keyframe first.
     * The recovery counters start with it, because they describe THIS decoder:
     * a recreation is a new chain, not a continuation of the old one, and
     * carrying the old numbers across would make the first keyframe after a
     * recreation look like a recovery from a loss that belonged to a component
     * that no longer exists. */
    and_media_data->dec_seen_keyframe = PJ_FALSE;
    and_media_data->dec_ref_losses = 0;
    and_media_data->dec_ref_recoveries = 0;
    and_media_data->dec_ref_lost_at.u64 = 0;

    /* The configuration as actually applied, named rather than assumed.
     * "the component was created" and "the component was configured to decode
     * this stream" are different claims, and only the second one matters. */
    PJ_LOG(4, (THIS_FILE, "Decoder %s configured and started: %s %dx%d",
               and_media_codec[and_media_data->codec_idx].decoder_name?
                   and_media_codec[and_media_data->codec_idx].decoder_name->ptr
                   : "(unnamed)",
               and_media_codec[and_media_data->codec_idx].mime_type,
               and_media_data->prm->dec_fmt.det.vid.size.w,
               and_media_data->prm->dec_fmt.det.vid.size.h));
    return PJ_SUCCESS;
}

PJ_DEF(pj_status_t) pjmedia_codec_and_media_vid_init(
                                                pjmedia_vid_codec_mgr *mgr,
                                                pj_pool_factory *pf)
{
    const pj_str_t h264_name = { (char*)"H264", 4};
    pj_status_t status;
    int api_level = android_get_device_api_level();

    if (api_level < 28) {
        PJ_LOG(4,(THIS_FILE, "Minimum API level 28,"
                  "Android MediaCodec cannot work with API level %d",
                  api_level));

        return PJ_SUCCESS;
    }

    if (and_media_factory.pool != NULL) {
        /* Already initialized. */
        return PJ_SUCCESS;
    }

    if (!mgr) mgr = pjmedia_vid_codec_mgr_instance();
    PJ_ASSERT_RETURN(mgr, PJ_EINVAL);

    /* Create Android AMediaCodec codec factory. */
    and_media_factory.base.op = &and_media_factory_op;
    and_media_factory.base.factory_data = NULL;
    and_media_factory.mgr = mgr;
    and_media_factory.pf = pf;
    and_media_factory.pool = pj_pool_create(pf, "and_media_vid_factory",
                                            256, 256, NULL);
    if (!and_media_factory.pool)
        return PJ_ENOMEM;

#if PJMEDIA_HAS_AND_MEDIA_H264
    /* Registering format match for SDP negotiation */
    status = pjmedia_sdp_neg_register_fmt_match_cb(
                                        &h264_name,
                                        &pjmedia_vid_codec_h264_match_sdp);
    if (status != PJ_SUCCESS)
        goto on_error;
#endif

    /* Register codec factory to codec manager. */
    status = pjmedia_vid_codec_mgr_register_factory(mgr,
                                                    &and_media_factory.base);
    if (status != PJ_SUCCESS)
        goto on_error;

    PJ_LOG(4,(THIS_FILE, "Android AMediaCodec initialized"));

    /* Done. */
    return PJ_SUCCESS;

on_error:
    pj_pool_release(and_media_factory.pool);
    and_media_factory.pool = NULL;
    return status;
}

/*
 * Unregister Android AMediaCodec factory from pjmedia endpoint.
 */
PJ_DEF(pj_status_t) pjmedia_codec_and_media_vid_deinit(void)
{
    pj_status_t status = PJ_SUCCESS;

    if (and_media_factory.pool == NULL) {
        /* Already deinitialized */
        return PJ_SUCCESS;
    }

    /* Unregister Android AMediaCodec factory. */
    status = pjmedia_vid_codec_mgr_unregister_factory(and_media_factory.mgr,
                                                      &and_media_factory.base);

    /* Destroy pool. */
    pj_pool_release(and_media_factory.pool);
    and_media_factory.pool = NULL;

    return status;
}

static pj_status_t and_media_test_alloc(pjmedia_vid_codec_factory *factory,
                                    const pjmedia_vid_codec_info *info )
{
    unsigned i;

    PJ_ASSERT_RETURN(factory == &and_media_factory.base, PJ_EINVAL);

    for (i = 0; i < PJ_ARRAY_SIZE(and_media_codec); ++i) {
        if (and_media_codec[i].enabled && info->pt == and_media_codec[i].pt &&
            (info->fmt_id == and_media_codec[i].fmt_id))
        {
            return PJ_SUCCESS;
        }
    }

    return PJMEDIA_CODEC_EUNSUP;
}

static pj_status_t and_media_default_attr(pjmedia_vid_codec_factory *factory,
                                      const pjmedia_vid_codec_info *info,
                                      pjmedia_vid_codec_param *attr )
{
    unsigned i;

    PJ_ASSERT_RETURN(factory == &and_media_factory.base, PJ_EINVAL);
    PJ_ASSERT_RETURN(info && attr, PJ_EINVAL);

    for (i = 0; i < PJ_ARRAY_SIZE(and_media_codec); ++i) {
        if (and_media_codec[i].enabled && info->pt != 0 &&
            (info->fmt_id == and_media_codec[i].fmt_id))
        {
            break;
        }
    }

    if (i == PJ_ARRAY_SIZE(and_media_codec))
        return PJ_EINVAL;

    pj_bzero(attr, sizeof(pjmedia_vid_codec_param));

    attr->dir = PJMEDIA_DIR_ENCODING_DECODING;
    attr->packing = PJMEDIA_VID_PACKING_PACKETS;

    /* Encoded format */
    pjmedia_format_init_video(&attr->enc_fmt, info->fmt_id,
                              DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FPS, 1);

    /* Decoded format */
    pjmedia_format_init_video(&attr->dec_fmt, PJMEDIA_FORMAT_I420,
                              DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FPS, 1);

    attr->dec_fmtp = and_media_codec[i].dec_fmtp;

    /* Bitrate */
    attr->enc_fmt.det.vid.avg_bps = DEFAULT_AVG_BITRATE;
    attr->enc_fmt.det.vid.max_bps = DEFAULT_MAX_BITRATE;

    /* Encoding MTU */
    attr->enc_mtu = PJMEDIA_MAX_VID_PAYLOAD_SIZE;

    return PJ_SUCCESS;
}

static pj_bool_t codec_exists(const pj_str_t *codec_name)
{
    AMediaCodec *codec;
    char *codec_txt;

    codec_txt = codec_name->ptr;

    codec = AMediaCodec_createCodecByName(codec_txt);
    if (!codec) {
        PJ_LOG(4, (THIS_FILE, "Failed creating codec : %.*s",
                   (int)codec_name->slen, codec_name->ptr));
        return PJ_FALSE;
    }
    AMediaCodec_delete(codec);

    return PJ_TRUE;
}

void add_codec(struct and_media_codec *codec,
               unsigned *count, pjmedia_vid_codec_info *info)
{
    info[*count].fmt_id = codec->fmt_id;
    info[*count].pt = codec->pt;
    info[*count].encoding_name = pj_str((char *)codec->name);
    info[*count].encoding_desc = pj_str((char *)codec->description);

    info[*count].clock_rate = 90000;
    info[*count].dir = PJMEDIA_DIR_ENCODING_DECODING;
    info[*count].dec_fmt_id_cnt = 1;
    info[*count].dec_fmt_id[0] = PJMEDIA_FORMAT_I420;
    info[*count].packings = PJMEDIA_VID_PACKING_PACKETS;
    info[*count].fps_cnt = 3;
    info[*count].fps[0].num = 15;
    info[*count].fps[0].denum = 1;
    info[*count].fps[1].num = 25;
    info[*count].fps[1].denum = 1;
    info[*count].fps[2].num = 30;
    info[*count].fps[2].denum = 1;
    ++*count;
}

/* ------------------------------------------------------------------------ *
 * Finding an encoder that actually works
 *
 * The lists above are a guess at what a device has, and a guess is no longer
 * good enough. `AMediaCodec_createCodecByName("OMX.google.h264.encoder")`
 * succeeds on Android 15 handsets that ship no OMX component at all, so
 * codec_exists() accepts a name the platform cannot honour and every
 * dequeueInputBuffer on it then fails for the life of the call: the camera
 * captures, the preview draws, and the RTP stream carries nothing (measured on
 * a Galaxy M14 / SM-M146B, 2026-09-23).
 *
 * So the encoder is discovered rather than assumed. Every component the
 * platform advertises for the MIME is enumerated, ranked with hardware Codec2
 * first, and then *proved* -- created, configured, started, and made to hand
 * over one input buffer. The first that survives is the one used, and a
 * candidate that fails any step is skipped for the next. A device whose codecs
 * cannot be enumerated falls back to the static lists below, which is what
 * every pre-existing platform already did.
 * ------------------------------------------------------------------------ */

#define AND_MEDIA_MAX_CANDIDATES    16
#define AND_MEDIA_MAX_NAME          128

/* The probe format, which has to be the format a call will really ask for.
 *
 * 640x480 was not: c2.android.avc.encoder configures, starts and delivers async
 * input buffers quite happily at that size on a Galaxy M23 (SM-E236B, Android
 * 14) and delivers none at all at 720p, which is what the call negotiates. The
 * probe passed it and the call then sent 0 packets/s -- the same silence this
 * whole mechanism exists to prevent, reached by asking an easier question than
 * the one that matters (measured 2026-09-23).
 *
 * So these mirror what a video call actually configures: 720p30 at 1.5 Mbit,
 * and every key below is one configure_encoder() sets too. A component that
 * survives this is being rehearsed, not sampled. */
#define AND_MEDIA_PROBE_W           1280
#define AND_MEDIA_PROBE_H           720
#define AND_MEDIA_PROBE_BPS         1500000
#define AND_MEDIA_PROBE_FPS         30
/* Long enough for a hardware component to allocate its buffers, short enough
 * that probing several of them is not felt at startup. */
#define AND_MEDIA_PROBE_TIMEOUT     200000
/* Async mode answers through a callback, so the probe waits rather than blocks.
 * Long enough for a software encoder at 720p to turn a few frames around. */
#define AND_MEDIA_PROBE_WAIT_MS     1500
#define AND_MEDIA_PROBE_POLL_MS     10
/* Enough frames for an encoder that will not emit until it has a few. */
#define AND_MEDIA_PROBE_FRAMES      8
/* 33 ms apart, which is the 30 fps the probe claims in its format. */
#define AND_MEDIA_PROBE_PTS_STEP    33333
/* Feed/drain rounds for the synchronous path. */
#define AND_MEDIA_PROBE_ROUNDS      16
/* Input indices in flight; a handful is plenty. */
#define AND_MEDIA_PROBE_RING        8
/* Consecutive starved encode attempts before an encoder is condemned. */
#define AND_MEDIA_STARVED_LIMIT     150
/* Encoder swaps allowed while opening one codec. */
#define AND_MEDIA_OPEN_RETRIES      3

typedef struct and_media_candidate {
    char      name[AND_MEDIA_MAX_NAME];
    pj_bool_t hardware;     /* MediaCodecInfo.isHardwareAccelerated()        */
    pj_bool_t codec2;       /* a "c2." component rather than a legacy OMX one */
} and_media_candidate;

/* True for a name that is a software component by convention, used only where
 * isHardwareAccelerated() is unavailable (below API 29). */
static pj_bool_t name_looks_software(const char *name)
{
    return (pj_ansi_strstr(name, "google") != NULL ||
            pj_ansi_strstr(name, "c2.android") != NULL ||
            pj_ansi_strstr(name, ".sw.") != NULL);
}

/* Every encoder the platform advertises for `mime`, via Java's MediaCodecList. */
static unsigned and_media_enum_components(const char *mime,
                                          pj_bool_t want_encoder,
                                          and_media_candidate cand[],
                                          unsigned max_cand)
{
    JNIEnv *env = NULL;
    pj_bool_t attached;
    unsigned cnt = 0;
    jclass cls_list = NULL, cls_info = NULL;
    jmethodID m_ctor, m_infos, m_is_enc, m_name, m_types, m_is_hw;
    jobject list = NULL;
    jobjectArray infos = NULL;
    jsize n, i;

    attached = pj_jni_attach_jvm((void **)&env);
    if (!env)
        return 0;

    cls_list = env->FindClass("android/media/MediaCodecList");
    cls_info = env->FindClass("android/media/MediaCodecInfo");
    if (!cls_list || !cls_info)
        goto on_return;

    m_ctor  = env->GetMethodID(cls_list, "<init>", "(I)V");
    m_infos = env->GetMethodID(cls_list, "getCodecInfos",
                               "()[Landroid/media/MediaCodecInfo;");
    m_is_enc = env->GetMethodID(cls_info, "isEncoder", "()Z");
    m_name   = env->GetMethodID(cls_info, "getName", "()Ljava/lang/String;");
    m_types  = env->GetMethodID(cls_info, "getSupportedTypes",
                                "()[Ljava/lang/String;");
    if (!m_ctor || !m_infos || !m_is_enc || !m_name || !m_types)
        goto on_return;

    /* API 29. Absent below that, and an absent method leaves a pending
     * exception that every later JNI call would inherit. */
    m_is_hw = env->GetMethodID(cls_info, "isHardwareAccelerated", "()Z");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        m_is_hw = NULL;
    }

    /* 0 == MediaCodecList.REGULAR_CODECS */
    list = env->NewObject(cls_list, m_ctor, 0);
    if (!list)
        goto on_return;
    infos = (jobjectArray)env->CallObjectMethod(list, m_infos);
    if (!infos)
        goto on_return;

    n = env->GetArrayLength(infos);
    for (i = 0; i < n && cnt < max_cand; ++i) {
        jobject info = env->GetObjectArrayElement(infos, i);
        jobjectArray types;
        jsize tn, t;
        pj_bool_t match = PJ_FALSE;

        if (!info)
            continue;
        /* Normalised before comparing: jboolean is an unsigned char and
         * pj_bool_t an int, and a raw != between them is a trap waiting for a
         * JVM that returns something other than 1 for true.
         */
        pj_bool_t is_enc = env->CallBooleanMethod(info, m_is_enc)?
                           PJ_TRUE : PJ_FALSE;
        if (is_enc != want_encoder) {
            env->DeleteLocalRef(info);
            continue;
        }

        types = (jobjectArray)env->CallObjectMethod(info, m_types);
        if (types) {
            tn = env->GetArrayLength(types);
            for (t = 0; t < tn && !match; ++t) {
                jstring ts = (jstring)env->GetObjectArrayElement(types, t);
                const char *tc = ts ? env->GetStringUTFChars(ts, NULL) : NULL;
                if (tc && pj_ansi_stricmp(tc, mime) == 0)
                    match = PJ_TRUE;
                if (tc)
                    env->ReleaseStringUTFChars(ts, tc);
                if (ts)
                    env->DeleteLocalRef(ts);
            }
            env->DeleteLocalRef(types);
        }

        if (match) {
            jstring ns = (jstring)env->CallObjectMethod(info, m_name);
            const char *nc = ns ? env->GetStringUTFChars(ns, NULL) : NULL;
            if (nc) {
                pj_ansi_snprintf(cand[cnt].name, AND_MEDIA_MAX_NAME, "%s", nc);
                cand[cnt].codec2 = (pj_ansi_strncmp(nc, "c2.", 3) == 0);
                cand[cnt].hardware = m_is_hw
                        ? (pj_bool_t)env->CallBooleanMethod(info, m_is_hw)
                        : (pj_bool_t)!name_looks_software(nc);
                env->ReleaseStringUTFChars(ns, nc);
                ++cnt;
            }
            if (ns)
                env->DeleteLocalRef(ns);
        }
        env->DeleteLocalRef(info);
    }

on_return:
    if (env->ExceptionCheck())
        env->ExceptionClear();
    if (infos)    env->DeleteLocalRef(infos);
    if (list)     env->DeleteLocalRef(list);
    if (cls_info) env->DeleteLocalRef(cls_info);
    if (cls_list) env->DeleteLocalRef(cls_list);
    pj_jni_detach_jvm(attached);

    return cnt;
}

/* Hardware Codec2 first, then any other hardware, then Codec2 software, then
 * whatever is left. A stable insertion sort, so the platform's own order is
 * kept inside each band -- it lists its preferred component first. */
static void and_media_rank_candidates(and_media_candidate cand[], unsigned cnt)
{
    unsigned i, j;

    for (i = 1; i < cnt; ++i) {
        and_media_candidate key = cand[i];
        int key_rank = (key.hardware && key.codec2) ? 0 :
                       (key.hardware)               ? 1 :
                       (key.codec2)                 ? 2 : 3;
        j = i;
        while (j > 0) {
            and_media_candidate *prev = &cand[j - 1];
            int prev_rank = (prev->hardware && prev->codec2) ? 0 :
                            (prev->hardware)                 ? 1 :
                            (prev->codec2)                   ? 2 : 3;
            if (prev_rank <= key_rank)
                break;
            cand[j] = cand[j - 1];
            --j;
        }
        cand[j] = key;
    }
}

/* Encoders that failed in a real call, however well they probed.
 *
 * No start-up probe can predict every component: c2.android.avc.encoder
 * configures, starts and offers input buffers at the call's own 720p30, and
 * then encodes nothing at all once a call actually feeds it (SM-E236B, Android
 * 14, 2026-09-23). The probe below is what picks a good candidate; this is what
 * corrects it when the picking was wrong, so a handset self-heals on its next
 * call instead of being mute until somebody ships a new build.
 *
 * Written from the codec's own encode path and read from discovery, both on
 * the PJSIP thread; a torn read would cost one extra bad call, not correctness.
 */
static char      and_media_bad_enc[AND_MEDIA_MAX_CANDIDATES][AND_MEDIA_MAX_NAME];
static unsigned  and_media_bad_enc_cnt;

static pj_bool_t and_media_enc_is_bad(const char *name)
{
    unsigned i;
    for (i = 0; i < and_media_bad_enc_cnt; ++i) {
        if (pj_ansi_strcmp(and_media_bad_enc[i], name) == 0)
            return PJ_TRUE;
    }
    return PJ_FALSE;
}

static void and_media_enc_mark_bad(const char *name)
{
    if (and_media_enc_is_bad(name) ||
        and_media_bad_enc_cnt >= AND_MEDIA_MAX_CANDIDATES)
    {
        return;
    }
    pj_ansi_snprintf(and_media_bad_enc[and_media_bad_enc_cnt],
                     AND_MEDIA_MAX_NAME, "%s", name);
    ++and_media_bad_enc_cnt;
    PJ_LOG(3, (THIS_FILE, "Encoder %s encoded nothing in a live call; it will "
               "not be chosen again", name));
}

/* Whether the platform can actually give us this component.
 *
 * Creation and nothing else, deliberately. Configuring and starting a candidate
 * here -- the only way to learn anything more about it -- wedges the PJSIP
 * thread on some devices: an SM-E236B (Android 14) never returned from tearing
 * down an OMX.qcom encoder whose configure had just failed, so discovery never
 * reached the next candidate and the account never registered (2026-09-23). A
 * call that picks a poor encoder is a bad call; a stack that cannot register is
 * a dead phone, and the second is not worth risking for the first.
 *
 * What this cannot catch -- a component that creates, configures and starts and
 * then encodes nothing -- is caught where it shows: in the call, by
 * [and_media_enc_mark_bad], which condemns it so the next call picks another.
 */
static pj_bool_t and_media_encoder_works(const char *name, const char *mime)
{
    AMediaCodec *codec;

    PJ_UNUSED_ARG(mime);

    codec = AMediaCodec_createCodecByName(name);
    if (!codec) {
        PJ_LOG(4, (THIS_FILE, "  %s could not be created; skipping it", name));
        return PJ_FALSE;
    }
    AMediaCodec_delete(codec);
    return PJ_TRUE;
}

/* There is deliberately no decoder bad-list, and that is a finding rather than
 * an omission.
 *
 * One was written here: count consecutive decodes that produced no picture and,
 * past a threshold, never choose that component again. It condemned
 * c2.exynos.vp8.decoder on an M14 three seconds into every call -- and the
 * decoder was blameless. It was being fed frames whose payload descriptor this
 * file had corrupted on the way out (see payload_desc_size in encode_more_vpx),
 * so no VP8 decoder could have produced anything from them.
 *
 * Two things follow. The obvious one is that "produced no picture" is not
 * evidence about a component: a decoder legitimately produces nothing while it
 * waits for a keyframe, and it produces nothing for ever if what reaches it is
 * not the codec it was opened for -- which is a fault anywhere in the sender,
 * the network or this file. Condemning on that signal blames the last component
 * in the chain for everything upstream of it.
 *
 * The less obvious one is that the list never worked anyway. Discovery runs
 * once per process behind dyn_done[], so a decoder condemned during a call was
 * still the one the next call opened; and the list lived in process memory, so
 * a restart cleared it. It could neither take effect nor persist. Removing it
 * loses nothing that was running.
 *
 * What remains is the fallback that does work and is checked at selection time:
 * every advertised decoder is ranked and probed for creatability below, and a
 * MIME with no creatable decoder registers no codec at all -- which leaves
 * libvpx to answer for VP8, as it did before this file was given a decoder.
 */

/* Whether the platform can actually give us this decoder.
 *
 * Creation only, for exactly the reason [and_media_encoder_works] gives: a
 * configure-and-start probe here wedged the PJSIP thread on an SM-E236B and the
 * account never registered. What this cannot answer -- does the component
 * actually produce pictures -- is deliberately left unanswered rather than
 * guessed at from decode counts; the note above says why.
 */
static pj_bool_t and_media_decoder_can_be_created(const char *name,
                                                  const char *mime)
{
    AMediaCodec *codec;

    PJ_UNUSED_ARG(mime);

    codec = AMediaCodec_createCodecByName(name);
    if (!codec) {
        PJ_LOG(4, (THIS_FILE, "  %s could not be created; skipping it", name));
        return PJ_FALSE;
    }
    AMediaCodec_delete(codec);
    return PJ_TRUE;
}

/* The best advertised decoder for `mime` that the platform can actually create.
 *
 * The decoder half of what the encoder half already did. It matters because the
 * two were not symmetric: encoders became capability-driven and decoders stayed
 * on a hardcoded list of `OMX.*` names that Android has retired, which
 * `AMediaCodec_createCodecByName()` still hands back a usable-looking handle
 * for. On an Android 15 handset that produced a codec entry whose encoder was a
 * verified Codec2 component and whose decoder was a name the platform no longer
 * implements -- which is a decoder that opens and renders nothing.
 */
static pj_bool_t and_media_find_decoder(const char *mime, char *out,
                                        unsigned out_sz,
                                        pj_bool_t avoid_hardware)
{
    and_media_candidate cand[AND_MEDIA_MAX_CANDIDATES];
    unsigned cnt, i, pass;

    cnt = and_media_enum_components(mime, PJ_FALSE, cand,
                                    PJ_ARRAY_SIZE(cand));
    if (cnt == 0) {
        PJ_LOG(4, (THIS_FILE, "No decoder advertised for %s; falling back to "
                   "the static names", mime));
        return PJ_FALSE;
    }

    and_media_rank_candidates(cand, cnt);

    for (i = 0; i < cnt; ++i) {
        PJ_LOG(4, (THIS_FILE, "  %s decoder candidate %d: %s (%s%s)", mime, i,
                   cand[i].name, cand[i].hardware ? "hardware" : "software",
                   cand[i].codec2 ? ", Codec2" : ""));
    }

    /* Two passes when the encoder for this format already holds the hardware
     * engine.
     *
     * A hardware encoder and a hardware decoder of the SAME format can be two
     * clients of one video engine in one vendor Codec2 service, and it
     * serialises them. Measured on an SM-M146B with c2.exynos.vp8.encoder plus
     * c2.exynos.vp8.decoder, both hardware, both hosted by
     * samsung.hardware.media.c2@1.2-service (2026-09-26):
     *
     *     encode alone                  30.0 fps
     *     decode alone                  26.6 fps
     *     both together   encode 4.4 fps, decode 0.5 fps
     *
     * and the encoder returned to 30.0 fps within three seconds of the decoder
     * going idle, in the same call, with four and a half CPU cores unused. The
     * platform expressed it as availability: both components were offered input
     * buffers at an identical 4.4/s while this codec's own ownership ledger
     * stayed balanced, so it is the component withholding work and not a buffer
     * we failed to return.
     *
     * So when the encoder is hardware, the decoder takes the best NON-hardware
     * candidate first. The expensive half keeps the hardware engine; the cheap
     * half runs on the software component every device also advertises -- the
     * far handset in that same call ran software VP8 in both directions on a
     * weaker SoC, which is what makes this a measured trade rather than a guess.
     *
     * Capability-driven: the only input is isHardwareAccelerated() as the
     * platform reports it. No device, SoC, Android version or component name
     * appears here, nothing is swapped mid-call, and if no software decoder can
     * be created the hardware one is still taken -- imperfect video beats none.
     */
    for (pass = 0; pass < 2; ++pass) {
        pj_bool_t want_sw = (avoid_hardware && pass == 0);

        if (pass == 1 && !avoid_hardware)
            break;

        for (i = 0; i < cnt; ++i) {
            if (want_sw && cand[i].hardware)
                continue;
            if (!and_media_decoder_can_be_created(cand[i].name, mime))
                continue;

            pj_ansi_snprintf(out, out_sz, "%s", cand[i].name);
            PJ_LOG(4, (THIS_FILE, "Selected decoder for %s: %s (%s%s), "
                       "creatable%s", mime, cand[i].name,
                       cand[i].hardware ? "hardware" : "software",
                       cand[i].codec2 ? ", Codec2" : "",
                       !avoid_hardware? "" :
                       (cand[i].hardware?
                          " -- no software decoder available, sharing the "
                          "hardware engine with the encoder"
                        : " -- software, so the encoder keeps the hardware "
                          "engine to itself")));
            return PJ_TRUE;
        }
    }

    return PJ_FALSE;
}


/* The first advertised encoder for `mime` that survives the probe. */
static pj_bool_t and_media_find_encoder(const char *mime, char *out,
                                        unsigned out_sz,
                                        pj_bool_t *is_hardware)
{
    and_media_candidate cand[AND_MEDIA_MAX_CANDIDATES];
    unsigned cnt, i, pass;
    pj_bool_t prefer_sw = AND_MEDIA_BIDIR_PREFER_SOFTWARE? PJ_TRUE : PJ_FALSE;

    cnt = and_media_enum_components(mime, PJ_TRUE, cand,
                                    PJ_ARRAY_SIZE(cand));
    if (cnt == 0) {
        PJ_LOG(4, (THIS_FILE, "No encoder advertised for %s; falling back to "
                   "the static names", mime));
        return PJ_FALSE;
    }

    and_media_rank_candidates(cand, cnt);

    for (i = 0; i < cnt; ++i) {
        PJ_LOG(4, (THIS_FILE, "  %s candidate %d: %s (%s%s)", mime, i,
                   cand[i].name, cand[i].hardware ? "hardware" : "software",
                   cand[i].codec2 ? ", Codec2" : ""));
    }

    /* Software first when the format is used in both directions at once --
     * see AND_MEDIA_BIDIR_PREFER_SOFTWARE. Pass 1 drops the restriction so a
     * device advertising no usable software encoder still gets one. */
    for (pass = 0; pass < 2; ++pass) {
        pj_bool_t want_sw = (prefer_sw && pass == 0);

        if (pass == 1 && !prefer_sw)
            break;

        for (i = 0; i < cnt; ++i) {
            if (want_sw && cand[i].hardware)
                continue;
            if (and_media_enc_is_bad(cand[i].name)) {
                PJ_LOG(4, (THIS_FILE, "  %s skipped: it failed a live call "
                           "before", cand[i].name));
                continue;
            }
            if (and_media_encoder_works(cand[i].name, mime)) {
                pj_ansi_snprintf(out, out_sz, "%s", cand[i].name);
                if (is_hardware) *is_hardware = cand[i].hardware;
                PJ_LOG(4, (THIS_FILE, "Verified encoder for %s: %s (%s%s)%s",
                           mime, cand[i].name,
                           cand[i].hardware ? "hardware" : "software",
                           cand[i].codec2 ? ", Codec2" : "",
                           prefer_sw? (cand[i].hardware?
                             " -- no software encoder available"
                           : " -- software, for a format encoded and decoded"
                             " at once") : ""));
                return PJ_TRUE;
            }
        }
    }

    /* Everything advertised has been condemned at some point. Forget that and
     * take the best of them anyway: a handset whose encoders have all misbehaved
     * once should send imperfect video, not none, and being left with no
     * candidate at all is the one outcome with no way back. */
    for (i = 0; i < cnt; ++i) {
        if (and_media_encoder_works(cand[i].name, mime)) {
            pj_ansi_snprintf(out, out_sz, "%s", cand[i].name);
            PJ_LOG(3, (THIS_FILE, "Every %s encoder has failed before; using %s "
                       "regardless", mime, cand[i].name));
            return PJ_TRUE;
        }
    }

    PJ_LOG(3, (THIS_FILE, "None of the %d advertised %s encoders could be "
               "started; falling back to the static names", cnt, mime));
    return PJ_FALSE;
}

static void get_codec_name(pj_bool_t is_enc,
                           pj_bool_t prio,
                           pjmedia_format_id fmt_id,
                           pj_str_t **codec_name,
                           unsigned *codec_num)
{
    pj_bool_t use_sw_enc = PJMEDIA_AND_MEDIA_PRIO_SW_VID_ENC;
    pj_bool_t use_sw_dec = PJMEDIA_AND_MEDIA_PRIO_SW_VID_DEC;

    *codec_num = 0;

    switch (fmt_id) {

#if PJMEDIA_HAS_AND_MEDIA_H264
    case PJMEDIA_FORMAT_H264:
        if (is_enc) {
            if ((prio && use_sw_enc) || (!prio && !use_sw_enc)) {
                *codec_name = &H264_sw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(H264_sw_encoder);
            } else {
                *codec_name = &H264_hw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(H264_hw_encoder);
            }
        } else {
            if ((prio && use_sw_dec) || (!prio && !use_sw_dec)) {
                *codec_name = &H264_sw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(H264_sw_decoder);
            } else {
                *codec_name = &H264_hw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(H264_hw_decoder);
            }
        }
        break;
#endif
#if PJMEDIA_HAS_AND_MEDIA_VP8
    case PJMEDIA_FORMAT_VP8:
        if (is_enc) {
            if ((prio && use_sw_enc) || (!prio && !use_sw_enc)) {
                *codec_name = &VP8_sw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP8_sw_encoder);
            } else {
                *codec_name = &VP8_hw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP8_hw_encoder);
            }
        } else {
            if ((prio && use_sw_dec) || (!prio && !use_sw_dec)) {
                *codec_name = &VP8_sw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP8_sw_decoder);
            } else {
                *codec_name = &VP8_hw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP8_hw_decoder);
            }
        }
        break;
#endif
#if PJMEDIA_HAS_AND_MEDIA_VP9
    case PJMEDIA_FORMAT_VP9:
        if (is_enc) {
            if ((prio && use_sw_enc) || (!prio && !use_sw_enc)) {
                *codec_name = &VP9_sw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP9_sw_encoder);
            } else {
                *codec_name = &VP9_hw_encoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP9_hw_encoder);
            }
        } else {
            if ((prio && use_sw_dec) || (!prio && !use_sw_dec)) {
                *codec_name = &VP9_sw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP9_sw_decoder);
            } else {
                *codec_name = &VP9_hw_decoder[0];
                *codec_num = PJ_ARRAY_SIZE(VP9_hw_decoder);
            }
        }
        break;
#endif
    default:
        break;
    }
}

/* What discovery chose, per codec.
 *
 * File scope because three places need it: [and_media_enum_info] fills it once,
 * and both [create_codec] and [and_media_codec_open] re-choose into it when the
 * encoder they were handed turns out not to work. */
static char      dyn_name[PJ_ARRAY_SIZE(and_media_codec)][AND_MEDIA_MAX_NAME];
static pj_str_t  dyn_str[PJ_ARRAY_SIZE(and_media_codec)];
static pj_bool_t dyn_done[PJ_ARRAY_SIZE(and_media_codec)];
static pj_bool_t dyn_ok[PJ_ARRAY_SIZE(and_media_codec)];

/* The same three, for the decoder half. Kept separately because the two sides
 * are discovered and condemned independently: a handset can advertise a sound
 * hardware encoder and a decoder that opens and produces nothing. */
static char      dyn_dec_name[PJ_ARRAY_SIZE(and_media_codec)][AND_MEDIA_MAX_NAME];
static pj_str_t  dyn_dec_str[PJ_ARRAY_SIZE(and_media_codec)];
static pj_bool_t dyn_dec_ok[PJ_ARRAY_SIZE(and_media_codec)];

static pj_status_t and_media_enum_info(pjmedia_vid_codec_factory *factory,
                                   unsigned *count,
                                   pjmedia_vid_codec_info info[])
{
    unsigned i, max;

    /* What discovery found, kept because enum_info can be called more than
     * once and probing a component costs a create/configure/start each time. */

    PJ_ASSERT_RETURN(info && *count > 0, PJ_EINVAL);
    PJ_ASSERT_RETURN(factory == &and_media_factory.base, PJ_EINVAL);

    max = *count;

    for (i = 0, *count = 0; i < PJ_ARRAY_SIZE(and_media_codec) && *count < max;
         ++i)
    {
        unsigned enc_idx = 0;
        unsigned dec_idx = 0;
        pj_str_t *enc_name = NULL;
        unsigned num_enc;
        pj_str_t *dec_name = NULL;
        unsigned num_dec;

        /* Discovery first. The static lists cannot tell a component that
         * exists from one that merely answers to the name, and on Android 15
         * the difference is a call that sends no video at all -- see
         * and_media_find_encoder. */
        if (!dyn_done[i]) {
            /* The encoder is chosen first and its class decides the decoder's:
             * a hardware encoder means the decoder should not also take the
             * hardware engine for this format. See and_media_find_decoder. */
            pj_bool_t enc_is_hw = PJ_FALSE;

            dyn_done[i] = PJ_TRUE;
            dyn_ok[i] = and_media_find_encoder(and_media_codec[i].mime_type,
                                               dyn_name[i],
                                               AND_MEDIA_MAX_NAME,
                                               &enc_is_hw);
            if (dyn_ok[i])
                dyn_str[i] = pj_str(dyn_name[i]);

            dyn_dec_ok[i] = and_media_find_decoder(
                                        and_media_codec[i].mime_type,
                                        dyn_dec_name[i],
                                        AND_MEDIA_MAX_NAME,
                                        AND_MEDIA_BIDIR_PREFER_SOFTWARE?
                                            PJ_TRUE
                                          : (dyn_ok[i] && enc_is_hw));
            if (dyn_dec_ok[i])
                dyn_dec_str[i] = pj_str(dyn_dec_name[i]);
        }

        if (dyn_ok[i]) {
            enc_name = &dyn_str[i];
        } else {
            get_codec_name(PJ_TRUE, PJ_TRUE, and_media_codec[i].fmt_id,
                           &enc_name, &num_enc);

            for (enc_idx = 0; enc_idx < num_enc ;++enc_idx, ++enc_name) {
                if (codec_exists(enc_name)) {
                    break;
                }
            }
            if (enc_idx == num_enc) {
                get_codec_name(PJ_TRUE, PJ_FALSE, and_media_codec[i].fmt_id,
                               &enc_name, &num_enc);

                for (enc_idx = 0; enc_idx < num_enc ;++enc_idx, ++enc_name) {
                    if (codec_exists(enc_name)) {
                        break;
                    }
                }
                if (enc_idx == num_enc)
                    continue;
            }
        }

        if (dyn_dec_ok[i]) {
            dec_name = &dyn_dec_str[i];
        } else {
            get_codec_name(PJ_FALSE, PJ_TRUE, and_media_codec[i].fmt_id,
                           &dec_name, &num_dec);
            for (dec_idx = 0; dec_idx < num_dec ;++dec_idx, ++dec_name) {
                if (codec_exists(dec_name)) {
                    break;
                }
            }
            if (dec_idx == num_dec) {
                /* The software list, walked with the DECODER's own index and
                 * name. It used to walk the encoder's -- enc_idx, enc_name,
                 * num_enc -- and then test `dec_idx == num_dec`, which the
                 * loop above had already made true, so this fallback could
                 * only ever `continue`. A handset whose hardware decoder list
                 * missed therefore lost the whole codec instead of falling
                 * back to software. */
                get_codec_name(PJ_FALSE, PJ_FALSE, and_media_codec[i].fmt_id,
                               &dec_name, &num_dec);
                for (dec_idx = 0; dec_idx < num_dec ;++dec_idx, ++dec_name) {
                    if (codec_exists(dec_name)) {
                        break;
                    }
                }
                if (dec_idx == num_dec)
                    continue;
            }
        }

        and_media_codec[i].encoder_name = enc_name;
        and_media_codec[i].decoder_name = dec_name;
        PJ_LOG(4, (THIS_FILE, "Found encoder [%d]: %.*s and decoder: %.*s ",
                   *count, (int)enc_name->slen, enc_name->ptr,
                   (int)dec_name->slen, dec_name->ptr));
        add_codec(&and_media_codec[*count], count, info);
        and_media_codec[i].enabled = PJ_TRUE;
    }

    return PJ_SUCCESS;
}

static void create_codec(struct and_media_codec_data *and_media_data)
{
    char *enc_name;
    char *dec_name;

    if (!and_media_codec[and_media_data->codec_idx].encoder_name ||
        !and_media_codec[and_media_data->codec_idx].decoder_name)
    {
        return;
    }

    enc_name = and_media_codec[and_media_data->codec_idx].encoder_name->ptr;
    dec_name = and_media_codec[and_media_data->codec_idx].decoder_name->ptr;

    if (!and_media_data->enc) {
        and_media_data->enc = AMediaCodec_createCodecByName(enc_name);
        if (!and_media_data->enc) {
            PJ_LOG(4, (THIS_FILE, "Failed creating encoder: %s", enc_name));
        }
        pj_atomic_queue_create(and_media_data->pool,
                               BUFFER_MAX_ITEM,
                               sizeof(and_med_buf_info),
                               "enc_input_buf",
                               &and_media_data->enc_avail_input_buf);
        pj_atomic_queue_create(and_media_data->pool,
                               BUFFER_MAX_ITEM,
                               sizeof(and_med_buf_info),
                               "enc_output_buf",
                               &and_media_data->enc_avail_output_buf);
    }

    if (!and_media_data->dec) {
        and_media_data->dec = AMediaCodec_createCodecByName(dec_name);
        if (!and_media_data->dec) {
            PJ_LOG(4, (THIS_FILE, "Failed creating decoder: %s", dec_name));
        }
        pj_atomic_queue_create(and_media_data->pool,
                               BUFFER_MAX_ITEM,
                               sizeof(and_med_buf_info),
                               "dec_input_buf",
                               &and_media_data->dec_avail_input_buf);
        pj_atomic_queue_create(and_media_data->pool,
                               BUFFER_MAX_ITEM,
                               sizeof(and_med_buf_info),
                               "dec_output_buf",
                               &and_media_data->dec_avail_output_buf);
    }

    PJ_LOG(4, (THIS_FILE, "Created encoder: %s, decoder: %s", enc_name,
               dec_name));
}

static pj_status_t and_media_alloc_codec(pjmedia_vid_codec_factory *factory,
                                     const pjmedia_vid_codec_info *info,
                                     pjmedia_vid_codec **p_codec)
{
    pj_pool_t *pool;
    pjmedia_vid_codec *codec;
    and_media_codec_data *and_media_data;
    int i, idx;

    PJ_ASSERT_RETURN(factory == &and_media_factory.base && info && p_codec,
                     PJ_EINVAL);

    idx = -1;
    for (i = 0; i < PJ_ARRAY_SIZE(and_media_codec); ++i) {
        if ((info->fmt_id == and_media_codec[i].fmt_id) &&
            (and_media_codec[i].enabled))
        {
            idx = i;
            break;
        }
    }
    if (idx == -1) {
        *p_codec = NULL;
        return PJMEDIA_CODEC_EFAILED;
    }

    *p_codec = NULL;
    pool = pj_pool_create(and_media_factory.pf, "anmedvid%p", 512, 512, NULL);
    if (!pool)
        return PJ_ENOMEM;

    /* codec instance */
    codec = PJ_POOL_ZALLOC_T(pool, pjmedia_vid_codec);
    codec->factory = factory;
    codec->op = &and_media_codec_op;

    /* codec data */
    and_media_data = PJ_POOL_ZALLOC_T(pool, and_media_codec_data);
    and_media_data->pool = pool;
    and_media_data->codec_idx = idx;
    codec->codec_data = and_media_data;

    create_codec(and_media_data);
    if (!and_media_data->enc || !and_media_data->dec) {
        goto on_error;
    }

    *p_codec = codec;
    return PJ_SUCCESS;

on_error:
    and_media_dealloc_codec(factory, codec);
    return PJMEDIA_CODEC_EFAILED;
}

static pj_status_t and_media_dealloc_codec(pjmedia_vid_codec_factory *factory,
                                       pjmedia_vid_codec *codec )
{
    and_media_codec_data *and_media_data;

    PJ_ASSERT_RETURN(codec, PJ_EINVAL);

    PJ_UNUSED_ARG(factory);

    and_media_data = (and_media_codec_data*) codec->codec_data;

    /* The ledger, closed out, before the pool that holds it is released.
     *
     * This is the only place the whole-call numbers can be stated, and the exit
     * condition for this work is read off this line: acquired must equal
     * released on both halves and discarded must be zero. It prints even on a
     * clean call, because "the call was fine" and "the accounting balanced" are
     * different claims and only the second one is checkable. */
    PJ_LOG(3, (THIS_FILE, "Codec teardown ledger -- encoder: in-cb=%u in-ok=%u "
               "in-empty=%u out-cb=%u acq=%u rel=%u held=%d held-max=%u "
               "discarded=%u abandoned=%u rel n/e/x=%u/%u/%u begin=%u "
               "frames=%u empty=%u "
               "more=%u more-err=%u pktz=%u/%u frags-max=%u | decoder: "
               "in-cb=%u in-ok=%u in-empty=%u null=%u queued=%u out-cb=%u "
               "acq=%u rel=%u held=%d discarded=%u out-empty=%u",
               and_media_data->enc_cb_in, and_media_data->enc_in_ok,
               and_media_data->enc_in_empty, and_media_data->enc_cb_out,
               and_media_data->enc_out_acquired,
               and_media_data->enc_out_released,
               (int)and_media_data->enc_out_acquired -
                   (int)and_media_data->enc_out_released,
               and_media_data->enc_out_held_max,
               and_media_data->enc_out_discarded,
               and_media_data->enc_abandoned,
               and_media_data->enc_rel_normal, and_media_data->enc_rel_error,
               and_media_data->enc_rel_early, and_media_data->enc_begin_calls,
               and_media_data->enc_begin_frames,
               and_media_data->enc_begin_empty,
               and_media_data->enc_more_calls, and_media_data->enc_more_err,
               and_media_data->enc_pktz_ok, and_media_data->enc_pktz_err,
               and_media_data->enc_frags_max,
               and_media_data->dec_cb_in, and_media_data->dec_in_ok,
               and_media_data->dec_in_empty, and_media_data->dec_in_null,
               and_media_data->dec_queued, and_media_data->dec_cb_out,
               and_media_data->dec_out_acquired,
               and_media_data->dec_out_released,
               (int)and_media_data->dec_out_acquired -
                   (int)and_media_data->dec_out_released,
               and_media_data->dec_out_discarded,
               and_media_data->dec_out_empty));
    PJ_LOG(3, (THIS_FILE, "Codec teardown VP8 reassembly: complete=%u "
               "drop-missing-head=%u drop-incomplete=%u drop-no-keyframe=%u",
               and_media_data->vp8_frames_complete,
               and_media_data->vp8_frames_drop_missing_head,
               and_media_data->vp8_frames_drop_incomplete,
               and_media_data->vp8_frames_drop_no_keyframe));
    PJ_LOG(3, (THIS_FILE, "Codec teardown VP8 input validity: ok=%u bad=%u "
               "empty-queued=%u (a zero-length frame is not valid VP8; if the "
               "component errored, this says whether we sent it anything "
               "malformed)",
               and_media_data->dec_vp8_ok, and_media_data->dec_vp8_bad,
               and_media_data->dec_empty_queued));

    and_media_data->enc_started = PJ_FALSE;
    and_media_data->dec_started = PJ_FALSE;

    if (and_media_data->enc) {
        AMediaCodec_stop(and_media_data->enc);
        AMediaCodec_delete(and_media_data->enc);
        and_media_data->enc = NULL;
        pj_atomic_queue_destroy(and_media_data->enc_avail_input_buf);
        and_media_data->enc_avail_input_buf = NULL;
        pj_atomic_queue_destroy(and_media_data->enc_avail_output_buf);
        and_media_data->enc_avail_output_buf = NULL;
    }

    if (and_media_data->dec) {
        AMediaCodec_stop(and_media_data->dec);
        AMediaCodec_delete(and_media_data->dec);
        and_media_data->dec = NULL;
        pj_atomic_queue_destroy(and_media_data->dec_avail_input_buf);
        and_media_data->dec_avail_input_buf = NULL;
        pj_atomic_queue_destroy(and_media_data->dec_avail_output_buf);
        and_media_data->dec_avail_output_buf = NULL;
    }
    pj_pool_release(and_media_data->pool);
    return PJ_SUCCESS;
}

static pj_status_t and_media_codec_init(pjmedia_vid_codec *codec,
                                    pj_pool_t *pool )
{
    PJ_ASSERT_RETURN(codec && pool, PJ_EINVAL);
    PJ_UNUSED_ARG(codec);
    PJ_UNUSED_ARG(pool);
    return PJ_SUCCESS;
}

/* Condemns the encoder in use and brings up the next candidate in its place.
 *
 * The component is swapped underneath a live stream: the decoder, every queue
 * and the call itself are left exactly as they are, because only the encoder
 * was wrong. Used both while opening a codec, where a configure can fail
 * outright, and from the encode path, where a component that configured and
 * started quite happily stops yielding input buffers some minutes in. The
 * second is why this is not confined to open: a call that dies at minute four
 * used to stay dead for its whole length, because re-choosing only happened
 * the next time a codec was opened.
 *
 * `stop()` is deliberately not called first. Tearing a wedged component down
 * that way never returned on an SM-E236B (Android 14) and took the thread with
 * it; `delete()` alone releases it and ends its callbacks.
 */
static pj_bool_t and_media_swap_encoder(and_media_codec_data *and_media_data)
{
    unsigned idx = and_media_data->codec_idx;
    pj_str_t *current = and_media_codec[idx].encoder_name;
    and_med_buf_info stale;

    if (and_media_data->enc_retries++ >= AND_MEDIA_OPEN_RETRIES)
        return PJ_FALSE;
    if (current && current->ptr)
        and_media_enc_mark_bad(current->ptr);
    if (!and_media_find_encoder(and_media_codec[idx].mime_type,
                                dyn_name[idx], AND_MEDIA_MAX_NAME, NULL))
    {
        return PJ_FALSE;
    }
    dyn_str[idx] = pj_str(dyn_name[idx]);
    and_media_codec[idx].encoder_name = &dyn_str[idx];
    PJ_LOG(3, (THIS_FILE, "Re-opening the %s encoder as %s",
               and_media_codec[idx].name, dyn_name[idx]));

    if (and_media_data->enc) {
        AMediaCodec_delete(and_media_data->enc);
        and_media_data->enc = NULL;
    }

    /* The queues still hold buffer indices the old component handed out. Fed to
     * the new one they address buffers that are not its own. */
    while (and_media_data->enc_avail_input_buf &&
           pj_atomic_queue_get(and_media_data->enc_avail_input_buf, &stale) == PJ_SUCCESS)
    {
        /* drained */
    }
    while (and_media_data->enc_avail_output_buf &&
           pj_atomic_queue_get(and_media_data->enc_avail_output_buf, &stale) == PJ_SUCCESS)
    {
        /* drained */
    }

    and_media_data->enc = AMediaCodec_createCodecByName(dyn_name[idx]);
    if (!and_media_data->enc)
        return PJ_FALSE;
    if (API_AT_LEAST(28)) {
        AMediaCodecOnAsyncNotifyCallback cb = {&and_med_on_input_avail,
                                               &and_med_on_output_avail,
                                               &and_med_on_format_changed,
                                               &and_med_on_error};
        AMediaCodec_setAsyncNotifyCallback(and_media_data->enc, cb,
                                           and_media_data);
    }
    and_media_data->enc_starved = 0;
    return configure_encoder(and_media_data) == PJ_SUCCESS;
}

static pj_status_t and_media_codec_open(pjmedia_vid_codec *codec,
                                    pjmedia_vid_codec_param *codec_param)
{
    and_media_codec_data *and_media_data;
    pjmedia_vid_codec_param *param;
    pj_status_t status = PJ_SUCCESS;

    and_media_data = (and_media_codec_data*) codec->codec_data;
    and_media_data->prm = pjmedia_vid_codec_param_clone( and_media_data->pool,
                                                     codec_param);
    param = and_media_data->prm;
    if (and_media_codec[and_media_data->codec_idx].open_codec) {
        status = and_media_codec[and_media_data->codec_idx].open_codec(
                                                                and_media_data);
        if (status != PJ_SUCCESS)
            return status;
    }

    if (API_AT_LEAST(28)) {
        AMediaCodecOnAsyncNotifyCallback async_cb = {&and_med_on_input_avail,
                                                     &and_med_on_output_avail,
                                                     &and_med_on_format_changed,
                                                     &and_med_on_error};

        AMediaCodec_setAsyncNotifyCallback(and_media_data->enc, async_cb,
                                           and_media_data);
        AMediaCodec_setAsyncNotifyCallback(and_media_data->dec, async_cb,
                                           and_media_data);
    }
    and_media_data->whole = (param->packing == PJMEDIA_VID_PACKING_WHOLE);
    /* An encoder that will not configure is the wrong encoder, and this is the
     * one place that can say so with certainty: the format here is the call's
     * own, not a rehearsal of it. OMX.qcom.video.encoder.avc creates perfectly
     * well on an SM-E236B (Android 14) and then refuses every configure with
     * -10000, which used to leave the call with no video stream at all.
     *
     * The retry is here rather than in a start-up probe because configuring a
     * candidate at start-up is what wedged the PJSIP thread on that same
     * handset -- see and_media_encoder_works. Here the codec is being opened
     * on the ordinary path, which already unwinds a failure safely. */
    status = configure_encoder(and_media_data);
    while (status != PJ_SUCCESS) {
        if (!and_media_swap_encoder(and_media_data))
            break;
        status = PJ_SUCCESS;
    }
    if (status != PJ_SUCCESS) {
        return PJMEDIA_CODEC_EFAILED;
    }
    status = configure_decoder(and_media_data);
    if (status != PJ_SUCCESS) {
        return PJMEDIA_CODEC_EFAILED;
    }
    if (and_media_data->dec_buf_size == 0) {
        and_media_data->dec_buf_size = (MAX_RX_WIDTH * MAX_RX_HEIGHT * 3 >> 1) +
                                       (MAX_RX_WIDTH);
    }
    and_media_data->dec_buf = (pj_uint8_t*)pj_pool_alloc(and_media_data->pool,
                                                  and_media_data->dec_buf_size);
    /* Need to update param back after values are negotiated */
    pj_memcpy(codec_param, param, sizeof(*codec_param));

    return PJ_SUCCESS;
}

static pj_status_t and_media_codec_close(pjmedia_vid_codec *codec)
{
    PJ_ASSERT_RETURN(codec, PJ_EINVAL);
    PJ_UNUSED_ARG(codec);
    return PJ_SUCCESS;
}

static pj_status_t and_media_codec_modify(pjmedia_vid_codec *codec,
                                      const pjmedia_vid_codec_param *param)
{
    PJ_ASSERT_RETURN(codec && param, PJ_EINVAL);
    PJ_UNUSED_ARG(codec);
    PJ_UNUSED_ARG(param);
    return PJ_EINVALIDOP;
}

static pj_status_t and_media_codec_get_param(pjmedia_vid_codec *codec,
                                         pjmedia_vid_codec_param *param)
{
    struct and_media_codec_data *and_media_data;

    PJ_ASSERT_RETURN(codec && param, PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;
    pj_memcpy(param, and_media_data->prm, sizeof(*param));

    return PJ_SUCCESS;
}

static pj_status_t and_media_codec_encode_begin(pjmedia_vid_codec *codec,
                                            const pjmedia_vid_encode_opt *opt,
                                            const pjmedia_frame *input,
                                            unsigned out_size,
                                            pjmedia_frame *output,
                                            pj_bool_t *has_more)
{
    struct and_media_codec_data *and_media_data;
    and_med_buf_info buf_info;
    media_status_t am_status;
    pj_size_t output_size;
    pj_uint8_t *input_buf = NULL;
    pj_uint8_t *output_buf;
    pj_bool_t holds_output = PJ_FALSE;
    pj_atomic_queue_t *queue;

    PJ_ASSERT_RETURN(codec && input && out_size && output && has_more,
                     PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;
    pj_bzero(&buf_info, sizeof(buf_info));
    ++and_media_data->enc_begin_calls;

    /* The RTP payload budget, once, with the three numbers that decide whether
     * the four-byte VP8 descriptor can overflow anything.
     *
     * pjmedia_vpx_packetize caps a fragment at mtu - desc and then refuses if
     * fragment + desc exceeds the caller's out_size, so the descriptor can only
     * matter when out_size < mtu. out_size here is the stream's whole-frame
     * buffer (vid_stream.c: sizeof(rtp_hdr) + frame_size, less the header
     * again), which is tens of kilobytes; mtu is enc_mtu. Printing both means
     * the margin is a measurement rather than a reading of the source. */
    if (!and_media_data->enc_budget_logged) {
        and_media_data->enc_budget_logged = PJ_TRUE;
        PJ_LOG(4, (THIS_FILE, "Encoder payload budget: out_size=%u enc_mtu=%u "
                   "desc=%u fragment_cap=%u margin=%d",
                   out_size, and_media_data->prm->enc_mtu,
                   (and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_VP8)?
                       4u : 1u,
                   (and_media_data->prm->enc_mtu > 4)?
                       and_media_data->prm->enc_mtu - 4 : 0,
                   (int)out_size - (int)and_media_data->prm->enc_mtu));
    }

    if (opt && opt->force_keyframe) {
#if __ANDROID_API__ >=26
        AMediaFormat *vid_fmt = NULL;
        media_status_t am_status;

        vid_fmt = AMediaFormat_new();
        if (!vid_fmt) {
            return PJMEDIA_CODEC_EFAILED;
        }
        AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_REQUEST_SYNCF, 0);
        am_status = AMediaCodec_setParameters(and_media_data->enc, vid_fmt);

        if (am_status != AMEDIA_OK)
            PJ_LOG(4,(THIS_FILE, "Encoder setParameters failed %d", am_status));

        AMediaFormat_delete(vid_fmt);
#else
        PJ_LOG(5, (THIS_FILE, "Encoder cannot be forced to send keyframe"));
#endif
    }

    /* Feeding an input frame and collecting an encoded one are two INDEPENDENT
     * halves of this call, and coupling them is what deadlocks the component.
     *
     * They used to be coupled: the input index was dequeued first and, when the
     * queue was empty, this function returned without ever touching the output
     * queue. Every such return left one output index that MediaCodec had
     * already offered sitting in enc_avail_output_buf -- never acquired, so
     * never released, so never returned to a pool that holds four. Measured on
     * two handsets and two different components (c2.exynos.vp8.encoder on an
     * SM-M146B, c2.android.vp8.encoder on an SM-E236B, 2026-09-25): the ledger
     * ran out-cb == acq for eleven minutes, then reached out-cb - acq == 4 and
     * both callbacks stopped in the same instant --
     *
     *     15:26:49  in-cb=849  out-cb=845  acq=845   diff 0
     *     15:27:00  in-cb=873  out-cb=873  acq=869   diff 4   <- frozen here
     *     15:31:20  in-cb=873  out-cb=873  acq=869   camera still at 30fps
     *
     * -- because a component with no free output buffer cannot encode, and a
     * component that cannot encode stops offering INPUT buffers. That is what
     * "Encoder failed to get input Buffer" has been reporting all along: not a
     * starved input, an undrained output, one function earlier.
     *
     * The starvation itself is ordinary backpressure -- the camera offers 30fps
     * and the encoder accepts what it can -- so it is not an error and must not
     * end the call's video. What must not happen is returning while the
     * component is still holding out an encoded frame for us. So the input
     * attempt below can fail harmlessly, and the output half runs either way.
     */
    queue = and_media_data->enc_avail_input_buf;
    if (pj_atomic_queue_get(queue, &buf_info) == PJ_SUCCESS &&
        buf_info.index >= 0)
    {
        ++and_media_data->enc_in_ok;
        input_buf = AMediaCodec_getInputBuffer(and_media_data->enc,
                                               buf_info.index, &output_size);
        if (input_buf && output_size >= input->size) {
            and_media_data->enc_starved = 0;
            pj_memcpy(input_buf, input->buf, input->size);
            am_status = AMediaCodec_queueInputBuffer(and_media_data->enc,
                                         buf_info.index, 0, input->size, 0, 0);
            if (am_status != AMEDIA_OK) {
                /* Not handed over, so still ours -- and an index nobody gives
                 * back is an index the component never offers again. */
                PJ_LOG(4, (THIS_FILE, "Encoder queueInputBuffer return %d",
                           am_status));
                AMediaCodec_queueInputBuffer(and_media_data->enc,
                                             buf_info.index, 0, 0, 0, 0);
            }
        } else {
            /* The index was taken and the frame was not written into it, for
             * either reason below. Handed back empty rather than dropped: the
             * encoder's input pool is as finite as its output pool, and this is
             * the same defect as the decoder's null-buffer path. */
            if (!input_buf) {
                PJ_LOG(4,(THIS_FILE, "Encoder getInputBuffer "
                                     "returns no input buff"));
            } else {
                PJ_LOG(4,(THIS_FILE, "Encoder getInputBuffer "
                                     "size: %lu, expecting %lu.",
                                     (unsigned long)output_size,
                                     (unsigned long)input->size));
            }
            AMediaCodec_queueInputBuffer(and_media_data->enc,
                                         buf_info.index, 0, 0, 0, 0);
        }
    } else {
        /* Counted and rate limited, and reported WITH the output ledger.
         *
         * This line is the symptom the whole investigation starts from, and on
         * its own it is uninformative: it says the input queue was empty, which
         * is what an exhausted OUTPUT pool looks like from here. So it now
         * carries the numbers that separate the two -- acquired/released/held
         * for output, offered/taken for input -- and the one number that has no
         * other symptom, discarded. held climbing means a control-flow path
         * returns without releasing; discarded climbing means the queue
         * overwrote an index nobody can release; both flat means the component
         * is starved for a reason outside this file.
         */
        ++and_media_data->enc_in_empty;
        if (and_media_data->enc_in_empty % AND_MEDIA_IN_REPORT_EVERY == 1) {
            PJ_LOG(3,(THIS_FILE, "Encoder input starved: in-cb=%u in-ok=%u "
                      "in-empty=%u | out-cb=%u acq=%u rel=%u held=%d "
                      "held-max=%u discarded=%u abandoned=%u | "
                      "rel n/e/x=%u/%u/%u "
                      "begin=%u frames=%u empty=%u more=%u more-err=%u "
                      "pktz=%u/%u frags-max=%u",
                      and_media_data->enc_cb_in, and_media_data->enc_in_ok,
                      and_media_data->enc_in_empty,
                      and_media_data->enc_cb_out,
                      and_media_data->enc_out_acquired,
                      and_media_data->enc_out_released,
                      (int)and_media_data->enc_out_acquired -
                          (int)and_media_data->enc_out_released,
                      and_media_data->enc_out_held_max,
                      and_media_data->enc_out_discarded,
                      and_media_data->enc_abandoned,
                      and_media_data->enc_rel_normal,
                      and_media_data->enc_rel_error,
                      and_media_data->enc_rel_early,
                      and_media_data->enc_begin_calls,
                      and_media_data->enc_begin_frames,
                      and_media_data->enc_begin_empty,
                      and_media_data->enc_more_calls,
                      and_media_data->enc_more_err,
                      and_media_data->enc_pktz_ok,
                      and_media_data->enc_pktz_err,
                      and_media_data->enc_frags_max));
        }
        /* Remembered for the next call, never repaired on this one.
         *
         * Swapping the component under a live stream was tried and made matters
         * worse: on an SM-E236B each replacement lasted a shorter time than the
         * last -- c2.android thirteen minutes, then OMX.google forty-four
         * seconds -- until every candidate was condemned and the handset could
         * encode nothing at all (2026-09-23). That reads now as exactly what it
         * was: the deadlock above draining each new encoder's pool the same way,
         * faster each time because the starvation was already dense when the
         * replacement started. Healthy components were being condemned for a
         * defect in this function, which is why the count is kept and the
         * threshold left high rather than made more eager. */
        if (++and_media_data->enc_starved == AND_MEDIA_STARVED_LIMIT) {
            pj_str_t *nm = and_media_codec[and_media_data->codec_idx].encoder_name;
            if (nm && nm->ptr)
                and_media_enc_mark_bad(nm->ptr);
        }
    }

    /* The output half, reached whether or not a frame was just fed in. An
     * encoded frame waiting here was produced from an EARLIER input and has
     * nothing to do with this one, so a missed input is no reason to leave it
     * with the component -- and leaving it is precisely the defect. */
    pj_bzero(&buf_info, sizeof(buf_info));
    queue = and_media_data->enc_avail_output_buf;
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
        buf_info.index < 0)
    {
        PJ_LOG(4, (THIS_FILE, "Encoder failed to get output Buffer[%d]",
                   buf_info.index));
        goto on_return;
    }
    /* Acquired: the ledger's left-hand side, incremented at the one place an
     * index leaves the queue and becomes this codec's responsibility. held is
     * derived rather than stored so it cannot drift from the two counts. */
    if (and_media_data->enc_frame_open) {
        /* The previous frame's output buffer was never released. Said here
         * because this is the first instruction in this file to run after the
         * loss, and it can still say what the abandoned frame looked like --
         * how many fragments it had produced, how many bytes, and that has_more
         * was still TRUE when the caller stopped asking. */
        ++and_media_data->enc_abandoned;
        PJ_LOG(3, (THIS_FILE, "Encoder output buffer ABANDONED: idx=%d was held "
                   "across %u fragment(s), %u byte(s) of a %u byte frame, "
                   "has_more=%d, and the caller did not return. acq=%u rel=%u "
                   "abandoned=%u",
                   and_media_data->enc_output_buf_idx,
                   and_media_data->enc_frags,
                   and_media_data->enc_pkt_bytes,
                   and_media_data->enc_frame_size,
                   (int)and_media_data->enc_last_has_more,
                   and_media_data->enc_out_acquired,
                   and_media_data->enc_out_released,
                   and_media_data->enc_abandoned));
        /* Given back here rather than leaked, which is the whole fix if this is
         * the defect: the index is still valid and this codec still owns it.
         * Counted as an error release so the ledger keeps balancing. */
        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        and_media_data->enc_output_buf_idx, 0);
        ++and_media_data->enc_out_released;
        ++and_media_data->enc_rel_error;
        and_media_data->enc_frame_open = PJ_FALSE;
    }
    ++and_media_data->enc_out_acquired;
    and_media_data->enc_frame_open = PJ_TRUE;
    {
        unsigned held = and_media_data->enc_out_acquired -
                        and_media_data->enc_out_released;
        if (held > and_media_data->enc_out_held_max)
            and_media_data->enc_out_held_max = held;
    }
    and_media_data->enc_frags = 0;
    and_media_data->enc_pkt_bytes = 0;

    and_media_data->enc_output_buf_idx = buf_info.index;
    and_media_data->enc_buf_info.size = buf_info.size;
    and_media_data->enc_buf_info.flags = buf_info.flags;
    /* From here the buffer is ours and MUST be given back on every path out.
     * MediaCodec's output pool is finite: a buffer dequeued and never released
     * is gone for the life of the component, and once the pool is exhausted the
     * codec cannot progress -- which it reports, confusingly, by never offering
     * another *input* buffer. That is the "Encoder failed to get input Buffer"
     * an SM-E236B produced for ever after some minutes of a call, and it is why
     * each replacement encoder died sooner than the last: they were all being
     * drained the same way (2026-09-23). */
    holds_output = PJ_TRUE;
    output_buf = AMediaCodec_getOutputBuffer(and_media_data->enc,
                                             buf_info.index,
                                             &output_size);
    if (!output_buf) {
        PJ_LOG(4, (THIS_FILE, "Encoder failed getting output buffer, "
                   "buffer size %d, offset %d, flags %d",
                   and_media_data->enc_buf_info.size,
                   and_media_data->enc_buf_info.offset,
                   and_media_data->enc_buf_info.flags));
        goto on_return;
    }
    and_media_data->enc_processed = 0;
    and_media_data->enc_frame_whole = output_buf;
    and_media_data->enc_output_buf_idx = buf_info.index;
    and_media_data->enc_frame_size = and_media_data->enc_buf_info.size;

    /* A keyframe that is not a keyframe, said once per stream.
     *
     * Three bytes decide it -- a VP8 keyframe carries the start code 9d 01 2a
     * at bytes 3..5 -- and the cost is that compare on keyframes only, so this
     * can stay in. It is here because the failure it catches is otherwise
     * invisible from either end: a decoder handed a malformed frame consumes it,
     * reports nothing and draws nothing, so a whole call of black video comes
     * with healthy RTP, no error and no clue. If this line ever appears, the
     * bytes reaching the far end are not VP8 and no amount of decoder work will
     * help. It is the regression guard for the descriptor-size defect this file
     * carried: see the payload_desc_size comment in encode_more_vpx.
     */
    if ((and_media_data->enc_buf_info.flags & AND_MEDIA_FRM_TYPE_KEYFRAME) &&
        !and_media_data->enc_keyframe_checked &&
        and_media_data->enc_frame_size >= 10 &&
        and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_VP8)
    {
        pj_uint8_t *f = and_media_data->enc_frame_whole;

        and_media_data->enc_keyframe_checked = PJ_TRUE;
        if (f[3] != 0x9d || f[4] != 0x01 || f[5] != 0x2a) {
            PJ_LOG(3, (THIS_FILE, "Encoder %s emitted a keyframe with no VP8 "
                       "start code: %u bytes, hdr=%02x %02x %02x %02x %02x "
                       "%02x. The far end cannot decode this stream.",
                       and_media_codec[and_media_data->codec_idx].encoder_name?
                         and_media_codec[and_media_data->codec_idx]
                             .encoder_name->ptr : "(unnamed)",
                       and_media_data->enc_frame_size,
                       f[0], f[1], f[2], f[3], f[4], f[5]));
        }
    }

    if (and_media_codec[and_media_data->codec_idx].process_encode) {
        pj_status_t status;

        status = and_media_codec[and_media_data->codec_idx].process_encode(
                                                            and_media_data);

        if (status != PJ_SUCCESS)
            goto on_return;
    }

    if(and_media_data->enc_buf_info.flags & AND_MEDIA_FRM_TYPE_KEYFRAME) {
        output->bit_info |= PJMEDIA_VID_FRM_KEYFRAME;
    }

    if (and_media_data->whole) {
        unsigned payload_size = 0;
        unsigned start_data = 0;

        *has_more = PJ_FALSE;

        if ((and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_H264) &&
            (and_media_data->enc_buf_info.flags &
                                               AND_MEDIA_FRM_TYPE_KEYFRAME))
        {
            h264_codec_data *h264_data =
                                 (h264_codec_data *)and_media_data->ex_data;
            start_data = h264_data->enc_sps_pps_len;
            pj_memcpy(output->buf, h264_data->enc_sps_pps_buf,
                      h264_data->enc_sps_pps_len);
        }

        payload_size = and_media_data->enc_buf_info.size + start_data;

        if (payload_size > out_size) {
            AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                            buf_info.index, 0);
            ++and_media_data->enc_out_released;
            ++and_media_data->enc_rel_error;
            and_media_data->enc_frame_open = PJ_FALSE;
            holds_output = PJ_FALSE;
            return PJMEDIA_CODEC_EFRMTOOSHORT;
        }

        output->type = PJMEDIA_FRAME_TYPE_VIDEO;
        output->size = payload_size;
        output->timestamp = input->timestamp;
        pj_memcpy((pj_uint8_t*)output->buf+start_data,
                  and_media_data->enc_frame_whole,
                  and_media_data->enc_buf_info.size);

        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        buf_info.index,
                                        0);
        ++and_media_data->enc_out_released;
        ++and_media_data->enc_rel_normal;
        ++and_media_data->enc_begin_frames;
        and_media_data->enc_frame_open = PJ_FALSE;
        holds_output = PJ_FALSE;

        return PJ_SUCCESS;
    }

    /* Handed on: [and_media_codec_encode_more] releases it when the frame has
     * been packetised in full. */
    holds_output = PJ_FALSE;
    {
        pj_status_t more_status = and_media_codec_encode_more(codec, out_size,
                                                             output, has_more);
        if (output->size > 0)
            ++and_media_data->enc_begin_frames;
        else
            ++and_media_data->enc_begin_empty;
        return more_status;
    }

on_return:
    if (holds_output) {
        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        and_media_data->enc_output_buf_idx, 0);
        ++and_media_data->enc_out_released;
        ++and_media_data->enc_rel_early;
        and_media_data->enc_frame_open = PJ_FALSE;
    }
    /* The frame_out.size == 0 the investigation asks about, counted here.
     * vid_stream sends nothing for a zero-size frame and does not call
     * encode_more, so every TX gap is one of these -- and this is the only
     * place in this file that produces one. */
    ++and_media_data->enc_begin_empty;
    output->size = 0;
    output->type = PJMEDIA_FRAME_TYPE_NONE;
    *has_more = PJ_FALSE;
    return PJ_SUCCESS;
}

static pj_status_t and_media_codec_encode_more(pjmedia_vid_codec *codec,
                                           unsigned out_size,
                                           pjmedia_frame *output,
                                           pj_bool_t *has_more)
{
    struct and_media_codec_data *and_media_data;
    pj_status_t status = PJ_SUCCESS;

    PJ_ASSERT_RETURN(codec && out_size && output && has_more, PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;

    /* Poisoned before the call so a callee that returns without writing it is
     * visible rather than inherited. The release below is driven entirely by
     * this flag, and encode_more_vpx has return paths that never set it. */
    *has_more = PJ_FALSE;
    and_media_data->enc_more_calls++;

    status = and_media_codec[and_media_data->codec_idx].encode_more(
                                                            and_media_data,
                                                            out_size, output,
                                                            has_more);
    if (status != PJ_SUCCESS)
        ++and_media_data->enc_more_err;

    and_media_data->enc_last_has_more = *has_more;
    ++and_media_data->enc_frags;
    and_media_data->enc_pkt_bytes += (unsigned)output->size;
    if (and_media_data->enc_frags > and_media_data->enc_frags_max)
        and_media_data->enc_frags_max = and_media_data->enc_frags;

    if (!(*has_more)) {
        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        and_media_data->enc_output_buf_idx,
                                        0);
        ++and_media_data->enc_out_released;
        and_media_data->enc_frame_open = PJ_FALSE;
        if (status == PJ_SUCCESS)
            ++and_media_data->enc_rel_normal;
        else
            ++and_media_data->enc_rel_error;
    }

    /* The encoder's output-buffer ledger, rate limited, keyed on the invariant.
     *
     * acquired is every index encode_begin took out of the queue; released is
     * every index given back on any path. The two must differ by at most one --
     * the frame being fragmented right now -- because there is exactly one
     * output buffer in flight at a time. held larger than that, or growing, is
     * a control-flow path that returns while holding, and it is fatal a few
     * frames later: the pool empties and MediaCodec answers by refusing to
     * offer INPUT buffers, which is the symptom this call ends with.
     *
     * discarded is the other way an index is lost and has no symptom of its
     * own: put() overwrites the head of a full queue, so an index offered while
     * the media thread was behind is gone without ever being acquired. It
     * cannot be released because nobody knows its number.
     *
     * The per-frame correlation is on the same line -- index, size, PTS,
     * fragments, packetised bytes -- so a run that does show a gap says which
     * frame shape was in flight when it opened.
     */
    if (and_media_data->enc_more_calls % AND_MEDIA_IN_REPORT_EVERY == 1) {
        PJ_LOG(4, (THIS_FILE, "Encoder ledger: out-cb=%u acq=%u rel=%u held=%d "
                   "held-max=%u discarded=%u | rel n/e/x=%u/%u/%u | "
                   "begin=%u frames=%u empty=%u more=%u more-err=%u "
                   "pktz=%u/%u | frame idx=%d size=%u done=%u frags=%u/%u "
                   "bytes=%u has_more=%d out=%u/%u",
                   and_media_data->enc_cb_out,
                   and_media_data->enc_out_acquired,
                   and_media_data->enc_out_released,
                   (int)and_media_data->enc_out_acquired -
                       (int)and_media_data->enc_out_released,
                   and_media_data->enc_out_held_max,
                   and_media_data->enc_out_discarded,
                   and_media_data->enc_rel_normal,
                   and_media_data->enc_rel_error,
                   and_media_data->enc_rel_early,
                   and_media_data->enc_begin_calls,
                   and_media_data->enc_begin_frames,
                   and_media_data->enc_begin_empty,
                   and_media_data->enc_more_calls,
                   and_media_data->enc_more_err,
                   and_media_data->enc_pktz_ok,
                   and_media_data->enc_pktz_err,
                   and_media_data->enc_output_buf_idx,
                   and_media_data->enc_frame_size,
                   /* Bytes of this frame packetised so far. The PTS would be
                    * the natural correlator and is deliberately not here: the
                    * encoder is fed pts 0 for every frame (queueInputBuffer
                    * below), and and_med_buf_info carries only index, size and
                    * flags through the queue, so there is no per-buffer
                    * timestamp in this path to print. */
                   and_media_data->enc_processed,
                   and_media_data->enc_frags,
                   and_media_data->enc_frags_max,
                   and_media_data->enc_pkt_bytes,
                   (int)*has_more,
                   (unsigned)output->size, out_size));
    }

    /* The one thing worth a line of its own, the first time it happens.
     *
     * Everything above is periodic and easy to miss in a call's worth of log.
     * This fires once, at the moment the ledger stops balancing -- more than one
     * buffer held, or any buffer discarded -- which is the moment the defect
     * occurs rather than the moment its symptom appears. The two are seconds and
     * hundreds of frames apart, and every previous attempt at this bug was an
     * argument about what happened in that gap.
     */
    if (!and_media_data->enc_leak_reported &&
        (and_media_data->enc_out_discarded > 0 ||
         and_media_data->enc_abandoned > 0 ||
         (int)and_media_data->enc_out_acquired -
             (int)and_media_data->enc_out_released > 1))
    {
        and_media_data->enc_leak_reported = PJ_TRUE;
        PJ_LOG(3, (THIS_FILE, "Encoder output buffer LOST: acq=%u rel=%u "
                   "held=%d discarded=%u abandoned=%u after %u frames "
                   "(rel n/e/x=%u/%u/%u, "
                   "more=%u more-err=%u, pktz=%u/%u, frags=%u/%u). The pool has "
                   "%u slots; input starvation follows.",
                   and_media_data->enc_out_acquired,
                   and_media_data->enc_out_released,
                   (int)and_media_data->enc_out_acquired -
                       (int)and_media_data->enc_out_released,
                   and_media_data->enc_out_discarded,
                   and_media_data->enc_abandoned,
                   and_media_data->enc_begin_calls,
                   and_media_data->enc_rel_normal,
                   and_media_data->enc_rel_error,
                   and_media_data->enc_rel_early,
                   and_media_data->enc_more_calls,
                   and_media_data->enc_more_err,
                   and_media_data->enc_pktz_ok,
                   and_media_data->enc_pktz_err,
                   and_media_data->enc_frags,
                   and_media_data->enc_frags_max,
                   BUFFER_QUEUE_CAPACITY));
    }

    return status;
}

static int write_yuv(pj_uint8_t *buf,
                     pj_int32_t buf_len, // from "and_med_buf_info.size", it is "pj_int32_t"
                     pj_uint8_t *input,
                     pj_size_t input_len,
                     unsigned stride_len,
                     unsigned input_width,
                     unsigned input_height)
{
    pj_uint8_t *dst = buf;
    pj_uint8_t *input_ptr = input;
    pj_size_t req_buf_size = input_width * input_height * 3 / 2;
    pj_size_t req_input_size = stride_len * input_height * 3 / 2;
    unsigned half_stride = stride_len / 2;
    unsigned half_width = input_width / 2;
    unsigned half_height = input_height / 2;
    unsigned i;

    if (buf_len < req_buf_size || input_len < req_input_size)
        return -1;

    for (i = 0; i < input_height; i++) {
        pj_memcpy(dst, input_ptr, input_width);
        input_ptr += stride_len;
        dst += input_width;
    }

    for (i = 0; i < half_height; i++) {
        pj_memcpy(dst, input_ptr, half_width);
        input_ptr += half_stride;
        dst += half_width;
    }

    for (i = 0; i < half_height; i++) {
        pj_memcpy(dst, input_ptr, half_width);
        input_ptr += half_stride;
        dst += half_width;
    }

    return dst - buf;
}

static pj_bool_t and_media_get_input_buffer(
                                    struct and_media_codec_data *and_media_data)
{
    and_med_buf_info buf_info;
    pj_atomic_queue_t *queue = and_media_data->dec_avail_input_buf;

    pj_bzero(&buf_info, sizeof(buf_info));
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
         buf_info.index < 0)
    {
        /* Counted, and reported once every AND_MEDIA_IN_REPORT_EVERY rather than
         * per frame: the old line wrote itself 2825 times in one call and still
         * did not say the one thing that matters, which is whether MediaCodec
         * ever offered this decoder an index. `in-cb` is that number.
         */
        ++and_media_data->dec_in_empty;
        if (and_media_data->dec_in_empty % AND_MEDIA_IN_REPORT_EVERY == 1) {
            PJ_LOG(3,(THIS_FILE, "Decoder input starved: buf_max=%u | "
                      "in-cb=%u in-ok=%u "
                      "in-empty=%u null=%u | out-cb=%u acq=%u rel=%u held=%d "
                      "discarded=%u queued=%u (encoder in-cb=%u in-empty=%u)",
                      (unsigned)and_media_data->dec_input_buf_max_size,
                      and_media_data->dec_cb_in, and_media_data->dec_in_ok,
                      and_media_data->dec_in_empty,
                      and_media_data->dec_in_null,
                      and_media_data->dec_cb_out,
                      and_media_data->dec_out_acquired,
                      and_media_data->dec_out_released,
                      (int)and_media_data->dec_out_acquired -
                          (int)and_media_data->dec_out_released,
                      and_media_data->dec_out_discarded,
                      and_media_data->dec_queued,
                      and_media_data->enc_cb_in,
                      and_media_data->enc_in_empty));
        }
        return PJ_FALSE;
    }

    ++and_media_data->dec_in_ok;
    and_media_data->dec_input_buf_len = 0;
    and_media_data->dec_input_buf_idx = buf_info.index;
    and_media_data->dec_input_buf = AMediaCodec_getInputBuffer(
                                       and_media_data->dec,
                                       buf_info.index,
                                       &and_media_data->dec_input_buf_max_size);
    if (!and_media_data->dec_input_buf) {
        /* An index MediaCodec handed us whose buffer it will not produce.
         *
         * It does NOT go back on our queue -- re-offering an index we never
         * filled is how a decoder is made to decode stale bytes -- but it must
         * go back to the COMPONENT, which is a different thing and was the part
         * missing. Dropped, the index is one this codec owns for ever, and the
         * input pool is four: `in-cb - queued == null` exactly, measured at 3
         * on an SM-E236B while the decoder's callbacks sat frozen with the
         * output ledger perfectly balanced (2026-09-25). Handed back empty, the
         * way the recovery at the top of and_media_codec_decode already does.
         */
        /* Dropped, NOT handed back with a zero-length queue.
         *
         * getInputBuffer returning NULL means the component will not produce a
         * buffer for this index, so the index was never validly ours to return
         * -- and a zero-length buffer is not a valid VP8 frame. Queueing one
         * killed c2.android.vp8.decoder outright: on an SM-M146B it took 4 input
         * callbacks, 3 of them gave a NULL buffer, and the component went
         * ALLOCATED -> RELEASING -> RELEASED with a C2 error before a single
         * picture was decoded (2026-09-26). The hardware component tolerated the
         * same empty frames; the software one does not, and it is right not to.
         */
        /* Dropped, and NOT handed back with a zero-length queue.
         *
         * getInputBuffer() answering NULL means the component will not produce
         * a buffer for this index. Two alternatives were measured and both are
         * worse: queueing it with zero length is not a valid VP8 frame and
         * killed c2.android.vp8.decoder outright (ALLOCATED -> RELEASING ->
         * RELEASED), and putting the index back on our own queue span the same
         * index 1201 times in one call without the component ever mapping it.
         * Dropping loses a buffer from the pool, which is the least damaging of
         * the three and the only one that is not a retry. */
        ++and_media_data->dec_in_null;
        return PJ_FALSE;
    }
    return PJ_TRUE;
}

/*
 * Declare the decoder's reference chain broken, so the next inter-frames are
 * withheld until a keyframe restores it.
 *
 * Called from every path that refuses a picture. A picture this file drops is a
 * picture the sender COUNTED ON the decoder having: the next inter-frame is a
 * difference against it. Feeding that difference to a decoder that never saw
 * the base is what turns one lost packet into minutes of macroblock rubble --
 * see [dec_seen_keyframe] for the measurement.
 *
 * Idempotent, and deliberately so: a burst of loss drops several pictures in a
 * row and only the first of them breaks anything. The counter therefore counts
 * BREAKS rather than dropped pictures (the drop counters already do that), and
 * the timestamp is the moment the chain broke rather than the last picture that
 * found it broken -- which is what makes the recovery duration below the real
 * outage rather than the last frame of it.
 *
 * It sends nothing. The picture-less decode that follows is what publishes
 * PJMEDIA_EVENT_KEYFRAME_MISSING, and `vid_stream` is what turns that into an
 * RTCP-FB PLI, already bounded to two packets per event and already rate
 * limited at the sender by PJMEDIA_VID_STREAM_MIN_KEYFRAME_INTERVAL_MSEC. A
 * request path here would be a second one.
 */
static void vp8_reference_lost(struct and_media_codec_data *and_media_data,
                               const char *why)
{
    if (!and_media_data->dec_seen_keyframe)
        return;

    and_media_data->dec_seen_keyframe = PJ_FALSE;
    ++and_media_data->dec_ref_losses;
    pj_get_timestamp(&and_media_data->dec_ref_lost_at);

    PJ_LOG(4, (THIS_FILE, "Reference lost: %s. Inter-frames are withheld until "
               "a keyframe | losses=%u recoveries=%u",
               why, and_media_data->dec_ref_losses,
               and_media_data->dec_ref_recoveries));
}

/*
 * The keyframe that ends a recovery, and how long the stream was without one.
 *
 * Reports the duration rather than a bare "recovered", because the bound is the
 * thing under test: a keyframe that arrives in 60 ms came from the PLI, one that
 * arrives in ~1000 ms came from the encoder's own i-frame interval, and one that
 * takes longer than that means keyframes are being lost too and the stream is
 * still the defect. Nothing here enforces a bound; it measures one.
 *
 * Silent for the FIRST keyframe of a decoder, which is a start rather than a
 * recovery -- that case has its own line and no duration to report.
 */
static void vp8_reference_regained(struct and_media_codec_data *and_media_data,
                                   unsigned bytes)
{
    pj_timestamp now;
    unsigned msec;

    and_media_data->dec_seen_keyframe = PJ_TRUE;

    if (and_media_data->dec_ref_lost_at.u64 == 0) {
        PJ_LOG(4, (THIS_FILE, "Decoder reference acquired: first keyframe, "
                   "%u bytes", bytes));
        return;
    }

    pj_get_timestamp(&now);
    msec = pj_elapsed_msec(&and_media_data->dec_ref_lost_at, &now);
    and_media_data->dec_ref_lost_at.u64 = 0;
    ++and_media_data->dec_ref_recoveries;

    PJ_LOG(4, (THIS_FILE, "Decoder reference regained: keyframe of %u bytes "
               "after %u ms without one | losses=%u recoveries=%u "
               "inter-frames withheld=%u",
               bytes, msec, and_media_data->dec_ref_losses,
               and_media_data->dec_ref_recoveries,
               and_media_data->vp8_frames_drop_no_keyframe));
}


static pj_status_t and_media_decode(pjmedia_vid_codec *codec,
                                struct and_media_codec_data *and_media_data,
                                pj_uint8_t *input_buf, unsigned buf_size,
                                int buf_flag, pj_timestamp *input_ts,
                                pj_bool_t write_output, pjmedia_frame *output)
{
    pj_status_t status = PJ_SUCCESS;
    pj_size_t output_size;
    int len = 0;
    media_status_t am_status;
    and_med_buf_info buf_info;
    pj_uint8_t *output_buf;
    pj_atomic_queue_t *queue;

    pj_bzero(&buf_info, sizeof(buf_info));
    if (and_media_data->format_changed) {
        unsigned new_width = and_media_data->new_size.w;
        unsigned new_height = and_media_data->new_size.h;

        and_media_data->dec_stride_len = and_media_data->new_stride;
        if (new_width != and_media_data->prm->dec_fmt.det.vid.size.w ||
            new_height != and_media_data->prm->dec_fmt.det.vid.size.h)
        {
            pjmedia_event event;

            and_media_data->prm->dec_fmt.det.vid.size.w = new_width;
            and_media_data->prm->dec_fmt.det.vid.size.h = new_height;

            PJ_LOG(4,(THIS_FILE, "Frame size changed to %dx%d",
                      and_media_data->prm->dec_fmt.det.vid.size.w,
                      and_media_data->prm->dec_fmt.det.vid.size.h));

            /* Broadcast format changed event */
            pjmedia_event_init(&event, PJMEDIA_EVENT_FMT_CHANGED,
                               NULL, codec);
            event.data.fmt_changed.dir = PJMEDIA_DIR_DECODING;
            pjmedia_format_copy(&event.data.fmt_changed.new_fmt,
                                &and_media_data->prm->dec_fmt);
            pjmedia_event_publish(NULL, codec, &event,
                                  PJMEDIA_EVENT_PUBLISH_DEFAULT);
        }

        and_media_data->format_changed = PJ_FALSE;
    }

    if ((and_media_data->dec_input_buf_max_size > 0) &&
        (and_media_data->dec_input_buf_len + buf_size >
         and_media_data->dec_input_buf_max_size))
    {
        /* The picture does not fit, and for VP8 that is not a reason to send
         * half of it. Splitting here queues the bytes so far as a complete
         * frame and starts the rest as another, which is exactly the partial
         * submission the reassembly checks exist to prevent -- so the picture
         * is abandoned instead, and the buffer reset for the next one. With
         * max-input-size now declared as dec_buf_size this should not be
         * reachable; it stays because "should not" is not "cannot", and a
         * component is free to give a smaller buffer than asked for. */
        if (and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_VP8) {
            ++and_media_data->vp8_frames_drop_incomplete;
            if (and_media_data->vp8_frames_drop_incomplete % 100 == 1) {
                PJ_LOG(3,(THIS_FILE, "Dropped picture: %u+%u bytes exceed the "
                          "decoder input buffer (%u); not splitting a VP8 "
                          "frame",
                          and_media_data->dec_input_buf_len,
                          buf_size,
                          (unsigned)and_media_data->dec_input_buf_max_size));
            }
            and_media_data->dec_input_buf_len = 0;
            vp8_reference_lost(and_media_data,
                               "picture exceeded the component's input buffer");
            return status;
        }

        am_status = AMediaCodec_queueInputBuffer(and_media_data->dec,
                                            and_media_data->dec_input_buf_idx,
                                            0,
                                            and_media_data->dec_input_buf_len,
                                            input_ts->u32.lo,
                                            buf_flag);
        if (am_status != AMEDIA_OK) {
            PJ_LOG(4,(THIS_FILE, "Decoder queueInputBuffer idx[%ld] return %d",
                    and_media_data->dec_input_buf_idx, am_status));
            return status;
        }
        and_media_data->dec_input_buf = NULL;
    }

    if (and_media_data->dec_input_buf == NULL) {
        and_media_get_input_buffer(and_media_data);

        if (and_media_data->dec_input_buf == NULL) {
            /* These bytes are lost, so the picture is. Marked rather than
             * returned as an error, because the drain below must still run. */
            and_media_data->dec_pic_broken = PJ_TRUE;
            PJ_LOG(4,(THIS_FILE, "Decoder failed getting input buffer"));
            /* Not a return. The decoder half has exactly the encoder's defect
             * and it was measured the same way: on an SM-E236B the decoder's
             * callbacks froze together at in-cb=2382 out-cb=2378 acq=rel=2375
             * -- three output buffers offered, never collected, never released
             * -- while in-empty climbed past 18000 (2026-09-25). Returning here
             * on a write_output packet strands whatever the component is
             * holding out, and a decoder with no free output buffer stops
             * offering input, which makes the starvation permanent.
             *
             * So the picture is dropped (there is nowhere to put it) and the
             * drain below still runs. See the same note in encode_begin. */
            goto drain_output;
        }
    }
    pj_memcpy(and_media_data->dec_input_buf + and_media_data->dec_input_buf_len,
              input_buf, buf_size);

    and_media_data->dec_input_buf_len += buf_size;

    /* Mid-picture: nothing to drain yet, because nothing was queued. The
     * component produces one output per picture and this call is not the end of
     * one, so returning here cannot strand anything. */
    if (!write_output)
        return status;

    /* Validate the VP8 frame BEFORE it is handed over (RFC 6386 s9.1).
     *
     * The 3-byte frame tag is a little-endian 24-bit word: bit 0 is the frame
     * type, bits 1..3 the version, bit 4 show_frame, bits 5..23 the size of the
     * first partition. A key frame then carries the start code 9d 01 2a and a
     * 2+2-byte size. Three things are checkable here without a decoder:
     * the start code on key frames, the first partition fitting inside the
     * frame, and the frame being long enough to hold its own header.
     *
     * This is the boundary that decides the C2_CORRUPTED question. If these
     * pass and the component still fails, the fault is past this point; if they
     * fail, the frame was already broken when we assembled it -- which is what
     * a picture with a mid-stream packet hole produces, since vid_stream hands
     * missing packets through as zero-size entries and decode_vpx concatenates
     * what is left.
     */
    /* enc_fmt, not dec_fmt: dec_fmt is the DECODED (raw I420) format in both
     * directions, so testing it here matched nothing and the validator below
     * silently never ran. enc_fmt is the encoded format for encoder and decoder
     * alike -- it is what the encoder path a few hundred lines up already
     * tests. */
    if (and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_VP8 &&
        and_media_data->dec_input_buf_len > 0)
    {
        pj_uint8_t *f = and_media_data->dec_input_buf;
        unsigned len = and_media_data->dec_input_buf_len;
        pj_uint32_t tag = f[0] | (f[1] << 8) | (f[2] << 16);
        unsigned is_key = !(tag & 1);
        unsigned part1 = (tag >> 5) & 0x7FFFF;
        unsigned hdr = is_key? 10u : 3u;
        pj_bool_t bad = PJ_FALSE;
        const char *why = "";

        if (len < hdr) {
            bad = PJ_TRUE; why = "shorter than its own header";
        } else if (is_key &&
                   (f[3] != 0x9d || f[4] != 0x01 || f[5] != 0x2a)) {
            bad = PJ_TRUE; why = "key frame without the 9d 01 2a start code";
        } else if (part1 + hdr > len) {
            bad = PJ_TRUE; why = "first partition runs past the end";
        }

        if (bad) {
            ++and_media_data->dec_vp8_bad;
            if (!and_media_data->dec_vp8_said ||
                and_media_data->dec_vp8_bad % 100 == 0)
            {
                unsigned w = 0, h = 0;
                if (is_key && len >= 10) {
                    w = (f[6] | (f[7] << 8)) & 0x3FFF;
                    h = (f[8] | (f[9] << 8)) & 0x3FFF;
                }
                PJ_LOG(3, (THIS_FILE, "VP8 frame INVALID before decoder: %s | "
                           "len=%u key=%u ver=%u show=%u part1=%u hdr=%u "
                           "%ux%u | hdr bytes=%02x %02x %02x %02x %02x %02x | "
                           "ok=%u bad=%u empty=%u",
                           why, len, is_key, (tag >> 1) & 7, (tag >> 4) & 1,
                           part1, hdr, w, h,
                           f[0], f[1], f[2],
                           len > 3? f[3] : 0, len > 4? f[4] : 0,
                           len > 5? f[5] : 0,
                           and_media_data->dec_vp8_ok,
                           and_media_data->dec_vp8_bad,
                           and_media_data->dec_empty_queued));
                PJ_LOG(3, (THIS_FILE, "  ...picture shape: packets=%u "
                           "desc0=%02x (S=%d PID=%d) desc_len0=%u "
                           "first_pkt=%u payload_total=%u buffered=%u",
                           and_media_data->dec_pkt_count,
                           and_media_data->dec_desc0,
                           (and_media_data->dec_desc0 & 0x10)? 1 : 0,
                           and_media_data->dec_desc0 & 0x07,
                           and_media_data->dec_desc_len0,
                           and_media_data->dec_first_size,
                           and_media_data->dec_total_pay,
                           and_media_data->dec_input_buf_len));
                and_media_data->dec_vp8_said = PJ_TRUE;
            }
        } else {
            ++and_media_data->dec_vp8_ok;
        }
    }

    am_status = AMediaCodec_queueInputBuffer(and_media_data->dec,
                                             and_media_data->dec_input_buf_idx,
                                             0,
                                             and_media_data->dec_input_buf_len,
                                             input_ts->u32.lo,
                                             buf_flag);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4,(THIS_FILE, "Decoder queueInputBuffer failed return %d",
                  am_status));
        /* The index is still ours: a queue that failed did not hand the buffer
         * over. Left set deliberately, so the recovery at the top of the next
         * and_media_codec_decode returns it instead of dropping it -- which is
         * what clearing it here used to do. The output is still drained. */
        goto drain_output;
    }
    ++and_media_data->dec_queued;
    /* Handed over. Cleared so the recovery above does not queue it a second
     * time, and so a later packet in this picture takes a fresh buffer rather
     * than writing into one the component is already decoding. */
    and_media_data->dec_input_buf = NULL;
    and_media_data->dec_input_buf_len = 0;

drain_output:

    pj_bzero(&buf_info, sizeof(buf_info));
    queue = and_media_data->dec_avail_output_buf;
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
        buf_info.index < 0)
    {
        /* Rate limited, and reporting the counters rather than the index.
         * "failed to get output Buffer[0]" wrote itself 1391 times in one call
         * and never said the one thing that decides the case, which is whether
         * the component ever offered an output buffer at all. out-cb is that
         * number; the encoder's is beside it as the control, since that half of
         * the same component demonstrably works.
         */
        ++and_media_data->dec_out_empty;
        if (and_media_data->dec_out_empty % AND_MEDIA_IN_REPORT_EVERY == 1) {
            PJ_LOG(3,(THIS_FILE, "Decoder output starved: out-cb=%u acq=%u "
                      "rel=%u held=%d discarded=%u | queued=%u in-cb=%u "
                      "in-ok=%u in-empty=%u out-empty=%u | encoder out-cb=%u "
                      "acq=%u rel=%u held=%d discarded=%u in-empty=%u",
                      and_media_data->dec_cb_out,
                      and_media_data->dec_out_acquired,
                      and_media_data->dec_out_released,
                      (int)and_media_data->dec_out_acquired -
                          (int)and_media_data->dec_out_released,
                      and_media_data->dec_out_discarded,
                      and_media_data->dec_queued,
                      and_media_data->dec_cb_in,
                      and_media_data->dec_in_ok,
                      and_media_data->dec_in_empty,
                      and_media_data->dec_out_empty,
                      and_media_data->enc_cb_out,
                      and_media_data->enc_out_acquired,
                      and_media_data->enc_out_released,
                      (int)and_media_data->enc_out_acquired -
                          (int)and_media_data->enc_out_released,
                      and_media_data->enc_out_discarded,
                      and_media_data->enc_in_empty));
        }
        return status;
    }

    /* The decoder's half of the same ledger. Its pool drains the same way and
     * reports it the same way -- by ceasing to offer input -- so the two halves
     * are counted identically and can be read side by side. */
    ++and_media_data->dec_out_acquired;

    output_buf = AMediaCodec_getOutputBuffer(and_media_data->dec,
                                             buf_info.index,
                                             &output_size);
    if (output_buf == NULL) {
        am_status = AMediaCodec_releaseOutputBuffer(and_media_data->dec,
                                        buf_info.index, 0);
        ++and_media_data->dec_out_released;
        PJ_LOG(4,(THIS_FILE, "Decoder getOutputBuffer failed"));
        return status;
    }

    len = write_yuv((pj_uint8_t *)output->buf,
                    output->size,
                    output_buf, // android media output buffer, here it is used as input for our "output" buffer
                    output_size, // size of android media output buffer
                    and_media_data->dec_stride_len,
                    and_media_data->prm->dec_fmt.det.vid.size.w,
                    and_media_data->prm->dec_fmt.det.vid.size.h);

    am_status = AMediaCodec_releaseOutputBuffer(and_media_data->dec,
                                                buf_info.index, 0);
    ++and_media_data->dec_out_released;

    if (len > 0) {
        if (!and_media_data->dec_has_output_frame) {
            output->type = PJMEDIA_FRAME_TYPE_VIDEO;
            output->size = len;
            output->timestamp = *input_ts;

            and_media_data->dec_has_output_frame = PJ_TRUE;
        }
    } else {
        status = PJMEDIA_CODEC_EFRMTOOSHORT;
    }
    return status;
}

/* Consecutive empty decodes before a component is judged dead rather than
 * merely waiting for a keyframe. Generous on purpose: a decoder legitimately
 * produces nothing until its first keyframe arrives, and at 30fps this is about
 * three seconds of it. */
#define AND_MEDIA_DEC_DEAD_RUN  90

static pj_status_t and_media_codec_decode(pjmedia_vid_codec *codec,
                                          pj_size_t count,
                                          pjmedia_frame packets[],
                                          unsigned out_size,
                                          pjmedia_frame *output)
{
    struct and_media_codec_data *and_media_data;
    pj_status_t status = PJ_EINVAL;

    PJ_ASSERT_RETURN(codec && count && packets && out_size && output,
                     PJ_EINVAL);
    PJ_ASSERT_RETURN(output->buf, PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;
    and_media_data->dec_has_output_frame = PJ_FALSE;

    /* Give back an input buffer the previous decode acquired and never queued.
     *
     * decode_vpx and decode_h264 assemble a picture across several packets and
     * only queue on the last one, so every path that leaves the loop early --
     * an unpacketize error, a picture larger than dec_buf_size, a queue that
     * failed -- returns while this codec still owns an index. Clearing the
     * pointer, which is all that used to happen here, drops that index: nothing
     * returns it and MediaCodec will not offer it again.
     *
     * The pool is four buffers on an M14, so four such frames are enough to
     * starve the decoder for the rest of the call, and the symptom is the one
     * this file is already too good at producing -- a stream that consumes RTP
     * and shows nothing. Queueing it empty costs the component a no-op frame
     * and keeps the accounting whole; the partial bytes are deliberately not
     * sent, because half a picture decodes to nothing good.
     */
    if (and_media_data->dec_input_buf && and_media_data->dec) {
        /* Counted, because a zero-length buffer is NOT a valid VP8 frame and
         * this is one of the two places we hand one to the component. It exists
         * to return an index the previous decode acquired and never queued --
         * which decode_vpx produces on every picture that has a packet hole,
         * since it abandons the picture at the first zero-size entry. If the
         * C2_CORRUPTED count tracks this counter rather than dec_vp8_bad, the
         * recovery is the thing feeding the decoder rubbish. */
        ++and_media_data->dec_empty_queued;
        AMediaCodec_queueInputBuffer(and_media_data->dec,
                                     and_media_data->dec_input_buf_idx,
                                     0, 0, 0, 0);
    }
    and_media_data->dec_input_buf = NULL;
    and_media_data->dec_input_buf_len = 0;

    if (and_media_codec[and_media_data->codec_idx].decode) {
        status = and_media_codec[and_media_data->codec_idx].decode(codec, count,
                                                           packets, out_size,
                                                           output);
    }
    if (status != PJ_SUCCESS) {
        return status;
    }
    /* Said once, and nothing is condemned for it.
     *
     * A run this long means the pictures are not arriving, and the cause is as
     * likely to be the sender or this file as the component -- it was this file
     * the one time it happened. So this names the decoder and the run length and
     * stops there, leaving the choice of component alone. See the note above
     * and_media_decoder_can_be_created for why there is no bad-list.
     */
    if (and_media_data->dec_has_output_frame) {
        and_media_data->dec_no_output_run = 0;
    } else if (++and_media_data->dec_no_output_run == AND_MEDIA_DEC_DEAD_RUN) {
        pj_str_t *nm = and_media_codec[and_media_data->codec_idx].decoder_name;

        PJ_LOG(3, (THIS_FILE, "Decoder %s has produced no picture in %d "
                   "consecutive frames (in-cb=%u queued=%u out-cb=%u). If this "
                   "persists the frames reaching it are not decodable.",
                   (nm && nm->ptr)? nm->ptr : "(unnamed)",
                   AND_MEDIA_DEC_DEAD_RUN,
                   and_media_data->dec_cb_in, and_media_data->dec_queued,
                   and_media_data->dec_cb_out));
    }

    if (!and_media_data->dec_has_output_frame) {
        pjmedia_event event;

        /* Broadcast missing keyframe event */
        pjmedia_event_init(&event, PJMEDIA_EVENT_KEYFRAME_MISSING,
                           &packets[0].timestamp, codec);
        pjmedia_event_publish(NULL, codec, &event,
                              PJMEDIA_EVENT_PUBLISH_DEFAULT);

        PJ_LOG(4,(THIS_FILE, "Decoder couldn't produce output frame"));

        output->type = PJMEDIA_FRAME_TYPE_NONE;
        output->size = 0;
        output->timestamp = packets[0].timestamp;
    }
    return PJ_SUCCESS;
}

#if PJMEDIA_HAS_AND_MEDIA_H264

static pj_status_t open_h264(and_media_codec_data *and_media_data)
{
    pj_status_t status;
    pjmedia_vid_codec_param *param = and_media_data->prm;
    pjmedia_h264_packetizer_cfg  pktz_cfg;
    pjmedia_vid_codec_h264_fmtp  h264_fmtp;
    h264_codec_data *h264_data;

    /* Parse remote fmtp */
    pj_bzero(&h264_fmtp, sizeof(h264_fmtp));
    status = pjmedia_vid_codec_h264_parse_fmtp(&param->enc_fmtp,
                                               &h264_fmtp);
    if (status != PJ_SUCCESS)
        return status;

    /* Apply SDP fmtp to format in codec param */
    if (!param->ignore_fmtp) {
        status = pjmedia_vid_codec_h264_apply_fmtp(param);
        if (status != PJ_SUCCESS)
            return status;
    }
    h264_data = PJ_POOL_ZALLOC_T(and_media_data->pool, h264_codec_data);
    if (!h264_data)
        return PJ_ENOMEM;

    pj_bzero(&pktz_cfg, sizeof(pktz_cfg));
    pktz_cfg.mtu = param->enc_mtu;
    pktz_cfg.unpack_nal_start = 4;
    /* Packetization mode */
    if (h264_fmtp.packetization_mode == 0)
        pktz_cfg.mode = PJMEDIA_H264_PACKETIZER_MODE_SINGLE_NAL;
    else if (h264_fmtp.packetization_mode == 1)
        pktz_cfg.mode = PJMEDIA_H264_PACKETIZER_MODE_NON_INTERLEAVED;
    else
        return PJ_ENOTSUP;

    /* Android H264 only supports Non Interleaved mode. */
    pktz_cfg.mode = PJMEDIA_H264_PACKETIZER_MODE_NON_INTERLEAVED;
    status = pjmedia_h264_packetizer_create(and_media_data->pool, &pktz_cfg,
                                            &h264_data->pktz);
    if (status != PJ_SUCCESS)
        return status;

    and_media_data->ex_data = h264_data;
    and_media_data->dec_buf_size = (MAX_RX_WIDTH * MAX_RX_HEIGHT * 3 >> 1) +
                                   (MAX_RX_WIDTH);

    /* If available, use the "sprop-parameter-sets" fmtp from remote SDP
     * to create the decoder.
     */
    if (h264_fmtp.sprop_param_sets_len) {
        const pj_uint8_t start_code[3] = { 0, 0, 1 };
        const int code_size = PJ_ARRAY_SIZE(start_code);
        const pj_uint8_t med_start_code[4] = { 0, 0, 0, 1 };
        const int med_code_size = PJ_ARRAY_SIZE(med_start_code);
        unsigned i, j;

        for (i = h264_fmtp.sprop_param_sets_len - code_size;
             i >= code_size; i--)
        {
            for (j = 0; j < code_size; j++) {
                if (h264_fmtp.sprop_param_sets[i + j] != start_code[j]) {
                    break;
                }
            }
        }

        if (i >= code_size) {
            h264_data->dec_sps_len = i + med_code_size - code_size;
            h264_data->dec_pps_len = h264_fmtp.sprop_param_sets_len +
                med_code_size - code_size - i;

            h264_data->dec_sps_buf = (pj_uint8_t *)pj_pool_alloc(
                                and_media_data->pool, h264_data->dec_sps_len);
            h264_data->dec_pps_buf = (pj_uint8_t *)pj_pool_alloc(
                                and_media_data->pool, h264_data->dec_pps_len);

            pj_memcpy(h264_data->dec_sps_buf, med_start_code,
                      med_code_size);
            pj_memcpy(h264_data->dec_sps_buf + med_code_size,
                      &h264_fmtp.sprop_param_sets[code_size],
                      h264_data->dec_sps_len - med_code_size);
            pj_memcpy(h264_data->dec_pps_buf, med_start_code,
                      med_code_size);
            pj_memcpy(h264_data->dec_pps_buf + med_code_size,
                      &h264_fmtp.sprop_param_sets[i + code_size],
                      h264_data->dec_pps_len - med_code_size);
        }
    }
    return status;
}

static pj_status_t process_encode_h264(and_media_codec_data *and_media_data)
{
    pj_status_t status = PJ_SUCCESS;
    h264_codec_data *h264_data;

    h264_data = (h264_codec_data *)and_media_data->ex_data;
    if (and_media_data->enc_buf_info.flags & AND_MEDIA_FRM_TYPE_CONFIG) {

        /*
        * Config data or SPS+PPS. Update the SPS and PPS buffer,
        * this will be sent later when sending Keyframe.
        */
        h264_data->enc_sps_pps_len = PJ_MIN(and_media_data->enc_buf_info.size,
                                        sizeof(h264_data->enc_sps_pps_buf));
        pj_memcpy(h264_data->enc_sps_pps_buf, and_media_data->enc_frame_whole,
                  h264_data->enc_sps_pps_len);

        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        and_media_data->enc_output_buf_idx,
                                        0);

        return PJ_EIGNORED;
    }
    if (and_media_data->enc_buf_info.flags & AND_MEDIA_FRM_TYPE_KEYFRAME) {
        h264_data->enc_sps_pps_ex = PJ_TRUE;
        and_media_data->enc_frame_size = h264_data->enc_sps_pps_len;
    } else {
        h264_data->enc_sps_pps_ex = PJ_FALSE;
    }

    return status;
}

static pj_status_t encode_more_h264(and_media_codec_data *and_media_data,
                                    unsigned out_size,
                                    pjmedia_frame *output,
                                    pj_bool_t *has_more)
{
    const pj_uint8_t *payload;
    pj_size_t payload_len;
    pj_status_t status;
    pj_uint8_t *data_buf = NULL;
    h264_codec_data *h264_data;

    h264_data = (h264_codec_data *)and_media_data->ex_data;
    if (h264_data->enc_sps_pps_ex) {
        data_buf = h264_data->enc_sps_pps_buf;
    } else {
        data_buf = and_media_data->enc_frame_whole;
    }
    /* We have outstanding frame in packetizer */
    status = pjmedia_h264_packetize(h264_data->pktz,
                                    data_buf,
                                    and_media_data->enc_frame_size,
                                    &and_media_data->enc_processed,
                                    &payload, &payload_len);
    if (status != PJ_SUCCESS) {
        /* Reset */
        and_media_data->enc_frame_size = and_media_data->enc_processed = 0;
        *has_more = (and_media_data->enc_processed <
                     and_media_data->enc_frame_size);

        PJ_PERROR(3,(THIS_FILE, status, "pjmedia_h264_packetize() error"));
        return status;
    }

    PJ_ASSERT_RETURN(payload_len <= out_size, PJMEDIA_CODEC_EFRMTOOSHORT);

    output->type = PJMEDIA_FRAME_TYPE_VIDEO;
    pj_memcpy(output->buf, payload, payload_len);
    output->size = payload_len;

    if (and_media_data->enc_processed >= and_media_data->enc_frame_size) {
        h264_codec_data *h264_data = (h264_codec_data *)and_media_data->ex_data;

        if (h264_data->enc_sps_pps_ex) {
            *has_more = PJ_TRUE;
            h264_data->enc_sps_pps_ex = PJ_FALSE;
            and_media_data->enc_processed = 0;
            and_media_data->enc_frame_size = and_media_data->enc_buf_info.size;
        } else {
            *has_more = PJ_FALSE;
        }
    } else {
        *has_more = PJ_TRUE;
    }

    return PJ_SUCCESS;
}

static pj_status_t decode_h264(pjmedia_vid_codec *codec,
                               pj_size_t count,
                               pjmedia_frame packets[],
                               unsigned out_size,
                               pjmedia_frame *output)
{
    struct and_media_codec_data *and_media_data;
    const pj_uint8_t start_code[] = { 0, 0, 0, 1 };
    const int code_size = PJ_ARRAY_SIZE(start_code);
    unsigned buf_pos, whole_len = 0;
    unsigned i, frm_cnt;
    pj_status_t status;

    PJ_ASSERT_RETURN(codec && count && packets && out_size && output,
                     PJ_EINVAL);
    PJ_ASSERT_RETURN(output->buf, PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;

    /*
     * Step 1: unpacketize the packets/frames
     */
    whole_len = 0;
    if (and_media_data->whole) {
        for (i=0; i<count; ++i) {
            if (whole_len + packets[i].size > and_media_data->dec_buf_size) {
                PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow [1]"));
                return PJMEDIA_CODEC_EFRMTOOSHORT;
            }

            pj_memcpy( and_media_data->dec_buf + whole_len,
                       (pj_uint8_t*)packets[i].buf,
                       packets[i].size);
            whole_len += packets[i].size;
        }

    } else {
        h264_codec_data *h264_data = (h264_codec_data *)and_media_data->ex_data;

        for (i=0; i<count; ++i) {

            if (whole_len + packets[i].size + code_size >
                and_media_data->dec_buf_size)
            {
                PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow [2]"));
                return PJMEDIA_CODEC_EFRMTOOSHORT;
            }

            status = pjmedia_h264_unpacketize( h264_data->pktz,
                                               (pj_uint8_t*)packets[i].buf,
                                               packets[i].size,
                                               and_media_data->dec_buf,
                                               and_media_data->dec_buf_size,
                                               &whole_len);
            if (status != PJ_SUCCESS) {
                PJ_PERROR(4,(THIS_FILE, status, "Unpacketize error"));
                continue;
            }
        }
    }

    if (whole_len + code_size > and_media_data->dec_buf_size ||
        whole_len <= code_size + 1)
    {
        PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow or unpacketize error "
                             "size: %d, buffer: %d", whole_len,
                             and_media_data->dec_buf_size));
        return PJMEDIA_CODEC_EFRMTOOSHORT;
    }

    /* Dummy NAL sentinel */
    pj_memcpy(and_media_data->dec_buf + whole_len, start_code, code_size);

    /*
     * Step 2: parse the individual NAL and give to decoder
     */
    buf_pos = 0;
    for ( frm_cnt=0; ; ++frm_cnt) {
        pj_uint32_t frm_size;
        pj_bool_t write_output = PJ_FALSE;
        unsigned char *start;

        for (i = code_size - 1; buf_pos + i < whole_len; i++) {
            if (and_media_data->dec_buf[buf_pos + i] == 0 &&
                and_media_data->dec_buf[buf_pos + i + 1] == 0 &&
                and_media_data->dec_buf[buf_pos + i + 2] == 0 &&
                and_media_data->dec_buf[buf_pos + i + 3] == 1)
            {
                break;
            }
        }

        frm_size = i;
        start = and_media_data->dec_buf + buf_pos;
        write_output = (buf_pos + frm_size >= whole_len);

        status = and_media_decode(codec, and_media_data, start, frm_size, 0,
                              &packets[0].timestamp, write_output, output);
        if (status != PJ_SUCCESS)
            return status;

        if (write_output)
            break;

        buf_pos += frm_size;
    }

    PJ_UNUSED_ARG(frm_cnt);

    return PJ_SUCCESS;
}

#endif

#if PJMEDIA_HAS_AND_MEDIA_VP8 || PJMEDIA_HAS_AND_MEDIA_VP9

static pj_status_t open_vpx(and_media_codec_data *and_media_data)
{
    vpx_codec_data *vpx_data;
    pjmedia_vid_codec_vpx_fmtp vpx_fmtp;
    pjmedia_vpx_packetizer_cfg pktz_cfg;
    pj_status_t status = PJ_SUCCESS;
    unsigned max_res = MAX_RX_WIDTH;

    if (!and_media_data->prm->ignore_fmtp) {
        status = pjmedia_vid_codec_vpx_apply_fmtp(and_media_data->prm);
        if (status != PJ_SUCCESS)
            return status;
    }

    vpx_data = PJ_POOL_ZALLOC_T(and_media_data->pool, vpx_codec_data);
    if (!vpx_data)
        return PJ_ENOMEM;

    /* Parse local fmtp */
    status = pjmedia_vid_codec_vpx_parse_fmtp(&and_media_data->prm->dec_fmtp,
                                              &vpx_fmtp);
    if (status != PJ_SUCCESS)
        return status;

    if (vpx_fmtp.max_fs > 0) {
        max_res = ((int)pj_isqrt(vpx_fmtp.max_fs * 8)) * 16;
    }
    and_media_data->dec_buf_size = (max_res * max_res * 3 >> 1) + (max_res);

    pj_bzero(&pktz_cfg, sizeof(pktz_cfg));
    pktz_cfg.mtu = and_media_data->prm->enc_mtu;
    pktz_cfg.fmt_id = and_media_data->prm->enc_fmt.id;

    status = pjmedia_vpx_packetizer_create(and_media_data->pool, &pktz_cfg,
                                            &vpx_data->pktz);
    if (status != PJ_SUCCESS)
        return status;

    and_media_data->ex_data = vpx_data;

    return status;
}

static pj_status_t encode_more_vpx(and_media_codec_data *and_media_data,
                                   unsigned out_size,
                                   pjmedia_frame *output,
                                   pj_bool_t *has_more)
{
    pj_status_t status = PJ_SUCCESS;
    struct vpx_codec_data *vpx_data = (vpx_codec_data *)and_media_data->ex_data;

    PJ_ASSERT_RETURN(and_media_data && out_size && output && has_more,
                     PJ_EINVAL);

    if ((and_media_data->prm->enc_fmt.id != PJMEDIA_FORMAT_VP8) &&
        (and_media_data->prm->enc_fmt.id != PJMEDIA_FORMAT_VP9))
    {
        *has_more = PJ_FALSE;
        output->size = 0;
        output->type = PJMEDIA_FRAME_TYPE_NONE;

        return PJ_SUCCESS;
    }

    if (and_media_data->enc_processed < and_media_data->enc_frame_size) {
        /* Four bytes for VP8, which is what pjmedia_vpx_packetize() writes.
         *
         * It writes a four-byte descriptor for VP8 -- X=1, I=1 and M=1, so a
         * 15-bit PictureID follows -- and one byte for VP9
         * (`vpx_packetizer.c:91`). This said 1 for both, so for every VP8
         * packet the payload was copied to `p + 1` and overwrote three of the
         * four descriptor bytes it had just written, and `output->size` then
         * described the packet as one byte of descriptor rather than four.
         *
         * What went out was a packet whose first byte still advertised the
         * extended descriptor while bytes 1..3 were video. The receiver
         * believes that byte: `pjmedia_vpx_unpacketize` reads X, then reads I,
         * L, T and K out of what is now payload data, so it strips a length it
         * computed from video content -- measured at 4, 5 and 6 bytes on
         * consecutive packets of one call -- and hands the decoder a frame that
         * starts partway into the first partition. No VP8 decoder can read
         * that, and none complains either: MediaCodec consumed every buffer,
         * returned no error, and produced no picture, which is what a black
         * remote tile with healthy RTP looks like.
         *
         * `vpx.c:664`, the libvpx codec, has always had this right, which is
         * why VP8 worked whenever libvpx was the implementation in use and
         * failed whenever this file was -- a difference that reads as "the
         * hardware decoder is broken" and is not.
         */
        unsigned payload_desc_size =
            (and_media_data->prm->enc_fmt.id == PJMEDIA_FORMAT_VP8)? 4 : 1;
        pj_size_t payload_len = out_size;
        pj_uint8_t *p = (pj_uint8_t *)output->buf;
        pj_bool_t is_keyframe = and_media_data->enc_buf_info.flags &
                                AND_MEDIA_FRM_TYPE_KEYFRAME;

        status = pjmedia_vpx_packetize(vpx_data->pktz,
                                       and_media_data->enc_frame_size,
                                       &and_media_data->enc_processed,
                                       is_keyframe,
                                       &p,
                                       &payload_len);
        if (status != PJ_SUCCESS) {
            /* The suspected trigger, named with the numbers that decide it.
             * pjmedia_vpx_packetize refuses when payload_len + desc exceeds the
             * caller's buffer, and this return leaves *has_more unwritten --
             * which is what the caller uses to decide whether to give the
             * output buffer back. */
            ++and_media_data->enc_pktz_err;
            PJ_LOG(3, (THIS_FILE, "vpx_packetize failed st=%d: out_size=%u "
                       "mtu=%u desc=%u frame=%u processed=%u",
                       status, (unsigned)out_size,
                       and_media_data->prm->enc_mtu, payload_desc_size,
                       and_media_data->enc_frame_size,
                       and_media_data->enc_processed));
            return status;
        }
        ++and_media_data->enc_pktz_ok;
        pj_memcpy(p + payload_desc_size,
              (and_media_data->enc_frame_whole + and_media_data->enc_processed),
              payload_len);
        output->size = payload_len + payload_desc_size;
        if (is_keyframe) {
            output->bit_info |= PJMEDIA_VID_FRM_KEYFRAME;
        }
        and_media_data->enc_processed += payload_len;
        *has_more = (and_media_data->enc_processed <
                     and_media_data->enc_frame_size);
    }

    return status;
}

static pj_status_t decode_vpx(pjmedia_vid_codec *codec,
                              pj_size_t count,
                              pjmedia_frame packets[],
                              unsigned out_size,
                              pjmedia_frame *output)
{
    unsigned i, whole_len = 0;
    pj_status_t status;
    and_media_codec_data *and_media_data =
                                      (and_media_codec_data*) codec->codec_data;
    struct vpx_codec_data *vpx_data = (vpx_codec_data *)and_media_data->ex_data;

    PJ_ASSERT_RETURN(codec && count && packets && out_size && output,
                     PJ_EINVAL);
    PJ_ASSERT_RETURN(output->buf, PJ_EINVAL);

    whole_len = 0;
    if (and_media_data->whole) {
        for (i = 0; i < count; ++i) {
            if (whole_len + packets[i].size > and_media_data->dec_buf_size) {
                PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow [1]"));
                return PJMEDIA_CODEC_EFRMTOOSHORT;
            }

            pj_memcpy( and_media_data->dec_buf + whole_len,
                       (pj_uint8_t*)packets[i].buf,
                       packets[i].size);
            whole_len += packets[i].size;
        }
        status = and_media_decode(codec, and_media_data,
                                  and_media_data->dec_buf, whole_len, 0,
                                  &packets[0].timestamp, PJ_TRUE, output);

        if (status != PJ_SUCCESS)
            return status;

    } else {
        /* Decide the whole picture BEFORE any of it reaches MediaCodec.
         *
         * A VP8 frame must be handed to the decoder from its first byte. The
         * stream assembles a picture from whatever the jitter buffer holds and
         * passes packets it never received through as zero-size entries, so a
         * loss that takes the FRONT of a frame used to be submitted anyway --
         * and the decoder then read three bytes of mid-partition payload as a
         * frame tag. Measured on this network at ~12% loss (2026-09-25): a
         * 5232-byte "frame" declaring version 4 (VP8 defines 0..3) and a first
         * partition of 477516 bytes, and on an SM-E236B the single malformed
         * frame of the call was followed 4 ms later by
         * "c2.android.vp8.decoder: work failed to complete: 14" (C2_CORRUPTED),
         * after which that component produced nothing for the rest of the call.
         *
         * The frame beginning is `S == 1 && PID == 0` in the payload
         * descriptor's first byte:
         *
         *      0 1 2 3 4 5 6 7
         *     +-+-+-+-+-+-+-+-+
         *     |X|R|N|S|R| PID |     S = p[0] & 0x10, PID = p[0] & 0x07
         *
         * S alone is NOT sufficient: it marks the start of a VP8 PARTITION, and
         * a packet carrying S with a non-zero PID begins partition 1..7, which
         * is mid-frame. pjmedia_vpx_packetize sets S only at bits_pos 0 and
         * never writes PID, so a frame we sent starts with S=1 and PID=0
         * exactly once (`vpx_packetizer.c`); requiring both is what keeps a
         * later partition from being mistaken for a new frame.
         *
         * This is a whole-picture verdict, taken before the loop, so a picture
         * that fails is dropped entire: no packet of it is unpacketized, no
         * byte of it enters the input buffer, and no later packet of the same
         * picture can become an apparent frame start. Nothing is fabricated and
         * nothing is prepended -- the picture is simply not offered. The caller
         * already publishes PJMEDIA_EVENT_KEYFRAME_MISSING when a decode
         * produces no output, which is how the stream asks for the keyframe
         * that resumes it.
         */
        {
            const pj_uint8_t *first = (const pj_uint8_t *)packets[0].buf;
            pj_bool_t head_ok = (first != NULL && packets[0].size > 0 &&
                                 (first[0] & 0x10) != 0 &&
                                 (first[0] & 0x07) == 0);

            if (!head_ok) {
                ++and_media_data->vp8_frames_drop_missing_head;
                if (and_media_data->vp8_frames_drop_missing_head % 100 == 1) {
                    PJ_LOG(4, (THIS_FILE, "Dropped picture: no frame start "
                               "(first packet %s, desc=%02x S=%d PID=%d) | "
                               "complete=%u drop-head=%u drop-holes=%u",
                               (first && packets[0].size > 0)? "present"
                                                             : "missing",
                               (first && packets[0].size > 0)? first[0] : 0,
                               (first && packets[0].size > 0)?
                                   ((first[0] & 0x10) != 0) : 0,
                               (first && packets[0].size > 0)?
                                   (first[0] & 0x07) : 0,
                               and_media_data->vp8_frames_complete,
                               and_media_data->vp8_frames_drop_missing_head,
                               and_media_data->vp8_frames_drop_incomplete));
                }
                vp8_reference_lost(and_media_data, "picture had no frame start");
                return PJ_SUCCESS;
            }

            /* A hole anywhere else truncates the bitstream just as badly. */
            for (i = 0; i < count; ++i) {
                if (packets[i].buf == NULL || packets[i].size == 0) {
                    ++and_media_data->vp8_frames_drop_incomplete;
                    if (and_media_data->vp8_frames_drop_incomplete % 100 == 1) {
                        PJ_LOG(4, (THIS_FILE, "Dropped picture: hole at packet "
                                   "%u of %u | complete=%u drop-head=%u "
                                   "drop-holes=%u",
                                   i, (unsigned)count,
                                   and_media_data->vp8_frames_complete,
                                   and_media_data->vp8_frames_drop_missing_head,
                                   and_media_data->vp8_frames_drop_incomplete));
                    }
                    vp8_reference_lost(and_media_data,
                                       "picture had an RTP hole");
                    return PJ_SUCCESS;
                }
            }
            /* A truncated TAIL is the third way to be partial, and the frame
             * declares enough to catch it without any new plumbing.
             *
             * The 3-byte tag carries the first partition's size, so a whole
             * frame is at least hdr + part1 bytes. A keyframe whose packets
             * after the first were lost passes the head and hole checks above
             * -- there is no hole, the jitter buffer simply never had the rest
             * -- and arrives as a single 1300-byte RTP payload declaring a
             * 1469-byte first partition. Measured on this network: start code
             * 9d 01 2a present, 1088x612, version 0, and still unusable.
             *
             * The RTP marker bit would say the same thing, but vid_stream zeroes
             * bit_info on every packet it hands down, so it is not available
             * here; the bitstream's own declaration is, and needs nothing added
             * to the layers above.
             */
            {
                unsigned total = 0, k;
                pj_bool_t sized_ok = PJ_TRUE;

                for (k = 0; k < count; ++k) {
                    unsigned dlen = 0;
                    if (pjmedia_vpx_unpacketize(vpx_data->pktz,
                                                (pj_uint8_t *)packets[k].buf,
                                                packets[k].size,
                                                &dlen) != PJ_SUCCESS)
                    {
                        sized_ok = PJ_FALSE;
                        break;
                    }
                    total += (unsigned)packets[k].size - dlen;
                }

                if (sized_ok) {
                    const pj_uint8_t *b0 = (const pj_uint8_t *)packets[0].buf;
                    unsigned d0 = 0;
                    pjmedia_vpx_unpacketize(vpx_data->pktz, (pj_uint8_t *)b0,
                                            packets[0].size, &d0);
                    if ((unsigned)packets[0].size > d0 + 2) {
                        const pj_uint8_t *v = b0 + d0;
                        pj_uint32_t tag = v[0] | (v[1] << 8) | (v[2] << 16);
                        unsigned is_key = !(tag & 1);
                        unsigned part1 = (tag >> 5) & 0x7FFFF;
                        unsigned need = (is_key? 10u : 3u) + part1;

                        if (total < need)
                            sized_ok = PJ_FALSE;

                        /* And the decoder must be holding the reference this
                         * picture is coded against -- which is true at the
                         * start of a stream only for a keyframe, and true
                         * again after a loss only for a keyframe. See
                         * [dec_seen_keyframe].
                         *
                         * Placed after `sized_ok`, deliberately: a keyframe is
                         * only allowed to end a recovery once it has passed
                         * every completeness check above, so a keyframe that
                         * itself lost packets leaves the stream in recovery
                         * rather than restoring a reference the decoder does
                         * not actually have. That is the one ordering in this
                         * block that matters. */
                        if (sized_ok && !and_media_data->dec_seen_keyframe) {
                            if (!is_key) {
                                ++and_media_data->vp8_frames_drop_no_keyframe;
                                if (and_media_data->vp8_frames_drop_no_keyframe
                                        % 100 == 1)
                                {
                                    PJ_LOG(4, (THIS_FILE, "Dropped picture: "
                                        "decoder has no trusted reference and "
                                        "this is an inter-frame (%u bytes); "
                                        "waiting for a keyframe | "
                                        "no-key-drops=%u losses=%u "
                                        "recoveries=%u",
                                        total, and_media_data
                                            ->vp8_frames_drop_no_keyframe,
                                        and_media_data->dec_ref_losses,
                                        and_media_data->dec_ref_recoveries));
                                }
                                return PJ_SUCCESS;
                            }
                            vp8_reference_regained(and_media_data, total);
                        }
                    }
                }

                if (!sized_ok) {
                    ++and_media_data->vp8_frames_drop_incomplete;
                    if (and_media_data->vp8_frames_drop_incomplete % 100 == 1) {
                        PJ_LOG(4, (THIS_FILE, "Dropped picture: truncated "
                                   "(assembled %u bytes across %u packets, "
                                   "short of the declared first partition) | "
                                   "complete=%u drop-head=%u drop-holes=%u",
                                   total, (unsigned)count,
                                   and_media_data->vp8_frames_complete,
                                   and_media_data->vp8_frames_drop_missing_head,
                                   and_media_data->vp8_frames_drop_incomplete));
                    }
                    vp8_reference_lost(and_media_data,
                                       "picture was truncated short of its "
                                       "declared first partition");
                    return PJ_SUCCESS;
                }
            }

            ++and_media_data->vp8_frames_complete;
            and_media_data->dec_pic_broken = PJ_FALSE;
            and_media_data->dec_pkt_count  = (unsigned)count;
            and_media_data->dec_desc0      = first[0];
            and_media_data->dec_first_size = (unsigned)packets[0].size;
            and_media_data->dec_desc_len0  = 0;
            and_media_data->dec_total_pay  = 0;
        }

        for (i = 0; i < count; ++i) {
            unsigned desc_len;
            unsigned packet_size = packets[i].size;
            pj_status_t status;
            pj_bool_t write_output;

            status = pjmedia_vpx_unpacketize(vpx_data->pktz,
                                             (pj_uint8_t *)packets[i].buf,
                                             packet_size,
                                             &desc_len);
            if (status != PJ_SUCCESS) {
                PJ_LOG(4,(THIS_FILE, "Unpacketize error packet size[%d]",
                          packet_size));
                vp8_reference_lost(and_media_data,
                                   "a packet would not unpacketize");
                return status;
            }

            if (i == 0)
                and_media_data->dec_desc_len0 = desc_len;
            packet_size -= desc_len;
            and_media_data->dec_total_pay += packet_size;
            if (whole_len + packet_size > and_media_data->dec_buf_size) {
                PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow [2]"));
                vp8_reference_lost(and_media_data,
                                   "picture exceeded the decoding buffer");
                return PJMEDIA_CODEC_EFRMTOOSHORT;
            }

            write_output = (i == count - 1);

            status = and_media_decode(codec, and_media_data,
                                  (pj_uint8_t *)packets[i].buf + desc_len,
                                  packet_size, 0, &packets[0].timestamp,
                                  write_output, output);
            if (status != PJ_SUCCESS) {
                /* Part of this picture may already be inside the component, and
                 * the rest never will be. Either way the decoder no longer
                 * holds what the next inter-frame is coded against. */
                vp8_reference_lost(and_media_data,
                                   "the decoder rejected part of a picture");
                return status;
            }

            if (and_media_data->dec_pic_broken) {
                /* A packet's bytes never made it in. Anything already buffered
                 * is a fragment of this picture and must not be presented as a
                 * frame, and the packets still to come must not start one. */
                ++and_media_data->vp8_frames_drop_incomplete;
                if (and_media_data->vp8_frames_drop_incomplete % 100 == 1) {
                    PJ_LOG(4, (THIS_FILE, "Dropped picture: could not take "
                               "packet %u of %u into the decoder input buffer "
                               "| complete=%u drop-head=%u drop-holes=%u",
                               i, (unsigned)count,
                               and_media_data->vp8_frames_complete,
                               and_media_data->vp8_frames_drop_missing_head,
                               and_media_data->vp8_frames_drop_incomplete));
                }
                and_media_data->dec_input_buf_len = 0;
                and_media_data->dec_pic_broken = PJ_FALSE;
                vp8_reference_lost(and_media_data,
                                   "a packet never reached the decoder input "
                                   "buffer");
                return PJ_SUCCESS;
            }

            whole_len += packet_size;
        }
    }
    return PJ_SUCCESS;
}

#endif

#endif  /* PJMEDIA_HAS_ANDROID_MEDIACODEC */
