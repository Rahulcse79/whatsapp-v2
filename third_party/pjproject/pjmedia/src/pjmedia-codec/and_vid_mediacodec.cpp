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
        buf_queue = and_media_data->enc_avail_input_buf;
    } else {
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
        buf_queue = and_media_data->enc_avail_output_buf;
    } else {
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
    int width, height, stride;
    and_media_codec_data *and_media_data = (and_media_codec_data *) userdata;

    AMediaFormat_getInt32(format, AND_MEDIA_KEY_WIDTH, &width);
    AMediaFormat_getInt32(format, AND_MEDIA_KEY_HEIGHT, &height);
    AMediaFormat_getInt32(format, AND_MEDIA_KEY_STRIDE, &stride);
    if(codec==and_media_data->dec){
        and_media_data->format_changed = PJ_TRUE;
        and_media_data->new_size.w = width;
        and_media_data->new_size.h = height;
        and_media_data->new_stride = stride;
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
    am_status = AMediaCodec_start(and_media_data->enc);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Encoder start failed, status=%d",
                am_status));
        return PJMEDIA_CODEC_EFAILED;
    }
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
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_HEIGHT,
                          and_media_data->prm->dec_fmt.det.vid.size.h);
    AMediaFormat_setInt32(vid_fmt, AND_MEDIA_KEY_WIDTH,
                          and_media_data->prm->dec_fmt.det.vid.size.w);
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

    am_status = AMediaCodec_start(and_media_data->dec);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4, (THIS_FILE, "Decoder start failed, status=%d",
                   am_status));
        return PJMEDIA_CODEC_EFAILED;
    }
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
static unsigned and_media_enum_encoders(const char *mime,
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
        if (!env->CallBooleanMethod(info, m_is_enc)) {
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
static void and_media_rank_encoders(and_media_candidate cand[], unsigned cnt)
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

/* The first advertised encoder for `mime` that survives the probe. */
static pj_bool_t and_media_find_encoder(const char *mime, char *out,
                                        unsigned out_sz)
{
    and_media_candidate cand[AND_MEDIA_MAX_CANDIDATES];
    unsigned cnt, i;

    cnt = and_media_enum_encoders(mime, cand, PJ_ARRAY_SIZE(cand));
    if (cnt == 0) {
        PJ_LOG(4, (THIS_FILE, "No encoder advertised for %s; falling back to "
                   "the static names", mime));
        return PJ_FALSE;
    }

    and_media_rank_encoders(cand, cnt);

    for (i = 0; i < cnt; ++i) {
        PJ_LOG(4, (THIS_FILE, "  %s candidate %d: %s (%s%s)", mime, i,
                   cand[i].name, cand[i].hardware ? "hardware" : "software",
                   cand[i].codec2 ? ", Codec2" : ""));
    }

    for (i = 0; i < cnt; ++i) {
        if (and_media_enc_is_bad(cand[i].name)) {
            PJ_LOG(4, (THIS_FILE, "  %s skipped: it failed a live call before",
                       cand[i].name));
            continue;
        }
        if (and_media_encoder_works(cand[i].name, mime)) {
            pj_ansi_snprintf(out, out_sz, "%s", cand[i].name);
            PJ_LOG(4, (THIS_FILE, "Verified encoder for %s: %s (%s%s)", mime,
                       cand[i].name,
                       cand[i].hardware ? "hardware" : "software",
                       cand[i].codec2 ? ", Codec2" : ""));
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
            dyn_done[i] = PJ_TRUE;
            dyn_ok[i] = and_media_find_encoder(and_media_codec[i].mime_type,
                                               dyn_name[i],
                                               AND_MEDIA_MAX_NAME);
            if (dyn_ok[i])
                dyn_str[i] = pj_str(dyn_name[i]);
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

        get_codec_name(PJ_FALSE, PJ_TRUE, and_media_codec[i].fmt_id,
                       &dec_name, &num_dec);
        for (dec_idx = 0; dec_idx < num_dec ;++dec_idx, ++dec_name) {
            if (codec_exists(dec_name)) {
                break;
            }
        }
        if (dec_idx == num_dec) {
            get_codec_name(PJ_FALSE, PJ_FALSE, and_media_codec[i].fmt_id,
                           &dec_name, &num_dec);
            for (enc_idx = 0; enc_idx < num_enc ;++enc_idx, ++enc_name) {
                if (codec_exists(enc_name)) {
                    break;
                }
            }
            if (dec_idx == num_dec)
                continue;
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
    while (status != PJ_SUCCESS && and_media_data->enc_retries++ < AND_MEDIA_OPEN_RETRIES) {
        unsigned idx = and_media_data->codec_idx;
        pj_str_t *nm = and_media_codec[idx].encoder_name;

        if (!nm || !nm->ptr)
            break;
        and_media_enc_mark_bad(nm->ptr);
        if (!and_media_find_encoder(and_media_codec[idx].mime_type,
                                    dyn_name[idx], AND_MEDIA_MAX_NAME))
        {
            break;
        }
        dyn_str[idx] = pj_str(dyn_name[idx]);
        and_media_codec[idx].encoder_name = &dyn_str[idx];
        PJ_LOG(3, (THIS_FILE, "Re-opening the %s encoder as %s",
                   and_media_codec[idx].name, dyn_name[idx]));

        /* Swap the component underneath, leaving every queue and the decoder
         * exactly as they were: only the encoder was wrong. */
        if (and_media_data->enc) {
            AMediaCodec_delete(and_media_data->enc);
            and_media_data->enc = NULL;
        }
        and_media_data->enc = AMediaCodec_createCodecByName(dyn_name[idx]);
        if (!and_media_data->enc)
            break;
        if (API_AT_LEAST(28)) {
            AMediaCodecOnAsyncNotifyCallback cb = {&and_med_on_input_avail,
                                                   &and_med_on_output_avail,
                                                   &and_med_on_format_changed,
                                                   &and_med_on_error};
            AMediaCodec_setAsyncNotifyCallback(and_media_data->enc, cb,
                                               and_media_data);
        }
        status = configure_encoder(and_media_data);
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
    pj_atomic_queue_t *queue;

    PJ_ASSERT_RETURN(codec && input && out_size && output && has_more,
                     PJ_EINVAL);

    and_media_data = (and_media_codec_data*) codec->codec_data;
    pj_bzero(&buf_info, sizeof(buf_info));

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

    queue = and_media_data->enc_avail_input_buf;
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
        buf_info.index < 0)
    {
        PJ_LOG(4,(THIS_FILE, "Encoder failed to get input Buffer[%d]",
                  buf_info.index));
        /* Starvation for this long is not congestion. At 30 fps this is several
         * seconds in which the camera delivered frames and the encoder took
         * none of them, which no working component does. */
        if (++and_media_data->enc_starved == AND_MEDIA_STARVED_LIMIT) {
            pj_str_t *nm = and_media_codec[and_media_data->codec_idx].encoder_name;
            if (nm && nm->ptr)
                and_media_enc_mark_bad(nm->ptr);
        }
        goto on_return;
    }

    input_buf = AMediaCodec_getInputBuffer(and_media_data->enc,
                                           buf_info.index, &output_size);
    if (input_buf && output_size >= input->size) {
        and_media_data->enc_starved = 0;
        pj_memcpy(input_buf, input->buf, input->size);
        am_status = AMediaCodec_queueInputBuffer(and_media_data->enc,
                                     buf_info.index, 0, input->size, 0, 0);
        if (am_status != AMEDIA_OK) {
            PJ_LOG(4, (THIS_FILE, "Encoder queueInputBuffer return %d",
                       am_status));
            goto on_return;
        }
    } else {
        if (!input_buf) {
            PJ_LOG(4,(THIS_FILE, "Encoder getInputBuffer "
                                 "returns no input buff"));
        } else {
            PJ_LOG(4,(THIS_FILE, "Encoder getInputBuffer "
                                 "size: %lu, expecting %lu.",
                                 (unsigned long)output_size,
                                 (unsigned long)input->size));
        }
        goto on_return;
    }

    queue = and_media_data->enc_avail_output_buf;
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
        buf_info.index < 0)
    {
        PJ_LOG(4, (THIS_FILE, "Encoder failed to get output Buffer[%d]",
                   buf_info.index));
        goto on_return;
    }
    and_media_data->enc_output_buf_idx = buf_info.index;
    and_media_data->enc_buf_info.size = buf_info.size;
    and_media_data->enc_buf_info.flags = buf_info.flags;
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

        if (payload_size > out_size)
            return PJMEDIA_CODEC_EFRMTOOSHORT;

        output->type = PJMEDIA_FRAME_TYPE_VIDEO;
        output->size = payload_size;
        output->timestamp = input->timestamp;
        pj_memcpy((pj_uint8_t*)output->buf+start_data,
                  and_media_data->enc_frame_whole,
                  and_media_data->enc_buf_info.size);

        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        buf_info.index,
                                        0);

        return PJ_SUCCESS;
    }

    return and_media_codec_encode_more(codec, out_size, output, has_more);

on_return:
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

    status = and_media_codec[and_media_data->codec_idx].encode_more(
                                                            and_media_data,
                                                            out_size, output,
                                                            has_more);
    if (!(*has_more)) {
        AMediaCodec_releaseOutputBuffer(and_media_data->enc,
                                        and_media_data->enc_output_buf_idx,
                                        0);
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
        PJ_LOG(4,(THIS_FILE, "Decoder failed to get input Buffer [%d]",
                  buf_info.index));
        return PJ_FALSE;
    }

    and_media_data->dec_input_buf_len = 0;
    and_media_data->dec_input_buf_idx = buf_info.index;
    and_media_data->dec_input_buf = AMediaCodec_getInputBuffer(
                                       and_media_data->dec,
                                       buf_info.index,
                                       &and_media_data->dec_input_buf_max_size);
    return PJ_TRUE;
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
            PJ_LOG(4,(THIS_FILE, "Decoder failed getting input buffer"));
            return status;
        }
    }
    pj_memcpy(and_media_data->dec_input_buf + and_media_data->dec_input_buf_len,
              input_buf, buf_size);

    and_media_data->dec_input_buf_len += buf_size;

    if (!write_output)
        return status;

    am_status = AMediaCodec_queueInputBuffer(and_media_data->dec,
                                             and_media_data->dec_input_buf_idx,
                                             0,
                                             and_media_data->dec_input_buf_len,
                                             input_ts->u32.lo,
                                             buf_flag);
    if (am_status != AMEDIA_OK) {
        PJ_LOG(4,(THIS_FILE, "Decoder queueInputBuffer failed return %d",
                  am_status));
        and_media_data->dec_input_buf = NULL;
        return status;
    }
    and_media_data->dec_input_buf_len += buf_size;

    pj_bzero(&buf_info, sizeof(buf_info));
    queue = and_media_data->dec_avail_output_buf;
    if (pj_atomic_queue_get(queue, &buf_info) != PJ_SUCCESS ||
        buf_info.index < 0)
    {
        PJ_LOG(4,(THIS_FILE, "Decoder failed to get output Buffer[%d]",
                  buf_info.index));
        return status;
    }

    output_buf = AMediaCodec_getOutputBuffer(and_media_data->dec,
                                             buf_info.index,
                                             &output_size);
    if (output_buf == NULL) {
        am_status = AMediaCodec_releaseOutputBuffer(and_media_data->dec,
                                        buf_info.index, 0);
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
        unsigned payload_desc_size = 1;
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
            return status;
        }
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
                return status;
            }

            packet_size -= desc_len;
            if (whole_len + packet_size > and_media_data->dec_buf_size) {
                PJ_LOG(4,(THIS_FILE, "Decoding buffer overflow [2]"));
                return PJMEDIA_CODEC_EFRMTOOSHORT;
            }

            write_output = (i == count - 1);

            status = and_media_decode(codec, and_media_data,
                                  (pj_uint8_t *)packets[i].buf + desc_len,
                                  packet_size, 0, &packets[0].timestamp,
                                  write_output, output);
            if (status != PJ_SUCCESS)
                return status;

            whole_len += packet_size;
        }
    }
    return PJ_SUCCESS;
}

#endif

#endif  /* PJMEDIA_HAS_ANDROID_MEDIACODEC */
