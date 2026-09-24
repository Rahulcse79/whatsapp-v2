/* 
 * Copyright (C) 2011 Teluu Inc. (http://www.teluu.com)
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
#ifndef __PJMEDIA_VID_STREAM_H__
#define __PJMEDIA_VID_STREAM_H__


/**
 * @file vid_stream.h
 * @brief Video Stream.
 */

#include <pjmedia/endpoint.h>
#include <pjmedia/jbuf.h>
#include <pjmedia/port.h>
#include <pjmedia/rtcp.h>
#include <pjmedia/rtcp_fb.h>
#include <pjmedia/transport.h>
#include <pjmedia/vid_codec.h>
#include <pjmedia/stream_common.h>
#include <pj/sock.h>

PJ_BEGIN_DECL


/**
 * @defgroup PJMED_VID_STRM Video streams
 * @ingroup PJMEDIA_PORT
 * @brief Video communication via the network
 * @{
 *
 * A video stream is a bidirectional video communication between two
 * endpoints. It corresponds to a video media description ("m=video" line)
 * in SDP session descriptor.
 *
 * A video stream consists of two unidirectional channels:
 *  - encoding channel, which transmits unidirectional video to remote, and
 *  - decoding channel, which receives unidirectional media from remote.
 *
 * A video stream exports two media port interface (see @ref PJMEDIA_PORT),
 * one for each direction, and application normally uses this interface to
 * interconnect the stream to other PJMEDIA components, e.g: the video
 * capture port supplies frames to the encoding port and video renderer
 * consumes frames from the decoding port.
 *
 * A video stream internally manages the following objects:
 *  - an instance of video codec (see @ref PJMEDIA_VID_CODEC),
 *  - an @ref PJMED_JBUF,
 *  - two instances of RTP sessions (#pjmedia_rtp_session, one for each
 *    direction),
 *  - one instance of RTCP session (#pjmedia_rtcp_session),
 *  - and a reference to video transport to send and receive packets
 *    to/from the network (see @ref PJMEDIA_TRANSPORT).
 *
 * Video streams are created by calling #pjmedia_vid_stream_create(),
 * specifying #pjmedia_stream_info structure in the parameter. Application
 * can construct the #pjmedia_vid_stream_info structure manually, or use 
 * #pjmedia_vid_stream_info_from_sdp() function to construct the
 * #pjmedia_vid_stream_info from local and remote SDP session descriptors.
 */


/**
 * Enumeration of video stream sending rate control.
 */
typedef enum pjmedia_vid_stream_rc_method
{
    /**
     * No sending rate control. All outgoing RTP packets will be transmitted
     * immediately right after encoding process is done.
     */
    PJMEDIA_VID_STREAM_RC_NONE              = 0,

    /**
     * Simple blocking. Each outgoing RTP packet transmission may be delayed
     * to avoid peak bandwidth that is much higher than specified. The thread
     * invoking the video stream put_frame(), e.g: video capture device thread,
     * will be blocked whenever transmission delay takes place.
     */
    PJMEDIA_VID_STREAM_RC_SIMPLE_BLOCKING   = 1,

    /**
     * Using a dedicated sending thread. Outgoing RTP packets will be queued
     * to be sent by a dedicated sending thread to avoid peak bandwidth that
     * is much higher than specified. Unlike simple blocking, the thread
     * invoking the video stream put_frame() will not be blocked. This will
     * generally provide better video latency than the simple blocking method
     * because of more accurate bitrate calculation.
     */
    PJMEDIA_VID_STREAM_RC_SEND_THREAD       = 2

} pjmedia_vid_stream_rc_method;


/**
 * Structure of configuration settings for video stream sending rate control.
 */
typedef struct pjmedia_vid_stream_rc_config
{
    /**
     * Rate control method.
     *
     * Default: PJMEDIA_VID_STREAM_RC_SEND_THREAD.
     */
    pjmedia_vid_stream_rc_method    method;

    /**
     * Upstream/outgoing bandwidth. If this is set to zero, the video stream
     * will use codec maximum bitrate setting.
     *
     * Default: 0 (follow codec maximum bitrate).
     */
    unsigned                        bandwidth;

} pjmedia_vid_stream_rc_config;

/**
 * Structure of configuration settings for video stream sending keyframe 
 * after it is created.
 */
typedef struct pjmedia_vid_stream_sk_config
{
    /**
     * The number of keyframe to be sent after the stream is created.
     *
     * Default: PJMEDIA_VID_STREAM_START_KEYFRAME_CNT
     */
    unsigned                        count;

    /**
     * The keyframe sending interval after the stream is created.
     *
     * Default: PJMEDIA_VID_STREAM_START_KEYFRAME_INTERVAL_MSEC
     */
    unsigned                        interval;

} pjmedia_vid_stream_sk_config;


/** 
 * This structure describes video stream information. Each video stream
 * corresponds to one "m=" line in SDP session descriptor, and it has
 * its own RTP/RTCP socket pair.
 */
typedef struct pjmedia_vid_stream_info
{
    PJ_DECL_STREAM_INFO_COMMON_MEMBER()

    pjmedia_vid_codec_info   codec_info;  /**< Incoming codec format info.  */
    pjmedia_vid_codec_param *codec_param; /**< Optional codec param.        */

    pjmedia_vid_stream_rc_config rc_cfg;
                                    /**< Stream send rate control settings. */

    pjmedia_vid_stream_sk_config sk_cfg;
                                    /**< Stream send keyframe settings.     */
} pjmedia_vid_stream_info;


/**
 * This function will initialize the video stream info based on information
 * in both SDP session descriptors for the specified stream index. 
 * The remaining information will be taken from default codec parameters. 
 * If socket info array is specified, the socket will be copied to the 
 * session info as well.
 *
 * @param si            Stream info structure to be initialized.
 * @param pool          Pool to allocate memory.
 * @param endpt         PJMEDIA endpoint instance.
 * @param local         Local SDP session descriptor.
 * @param remote        Remote SDP session descriptor.
 * @param stream_idx    Media stream index in the session descriptor.
 *
 * @return              PJ_SUCCESS if stream info is successfully initialized.
 */
PJ_DECL(pj_status_t)
pjmedia_vid_stream_info_from_sdp(pjmedia_vid_stream_info *si,
                                 pj_pool_t *pool,
                                 pjmedia_endpt *endpt,
                                 const pjmedia_sdp_session *local,
                                 const pjmedia_sdp_session *remote,
                                 unsigned stream_idx);


/**
 * Initialize the video stream rate control with default settings.
 *
 * @param cfg           Video stream rate control structure to be initialized.
 */
PJ_DECL(void)
pjmedia_vid_stream_rc_config_default(pjmedia_vid_stream_rc_config *cfg);

/**
 * Initialize the video stream send keyframe with default settings.
 *
 * @param cfg           Video stream send keyframe structure to be initialized.
 */
PJ_DECL(void)
pjmedia_vid_stream_sk_config_default(pjmedia_vid_stream_sk_config *cfg);


/*
 * Opaque declaration for video stream.
 */
typedef struct pjmedia_vid_stream pjmedia_vid_stream;


/**
 * Create a video stream based on the specified parameter. After the video
 * stream has been created, application normally would want to get the media
 * port interface of the stream, by calling pjmedia_vid_stream_get_port().
 * The media port interface exports put_frame() and get_frame() function,
 * used to transmit and receive media frames from the stream.
 *
 * Without application calling put_frame() and get_frame(), there will be 
 * no media frames transmitted or received by the stream.
 *
 * @param endpt         Media endpoint.
 * @param pool          Optional pool to allocate memory for the stream. If
 *                      this is not specified, one will be created internally.
 *                      A large number of memory may be needed because jitter
 *                      buffer needs to preallocate some storage.
 * @param info          Stream information to create the stream. Upon return,
 *                      this info will be updated with the information from
 *                      the instantiated codec. Note that if the "pool"
 *                      argument is NULL, some fields in this "info" parameter
 *                      will be allocated from the internal pool of the
 *                      stream, which means that they will only remain valid
 *                      as long as the stream is not destroyed.
 * @param tp            Media transport instance used to transmit and receive
 *                      RTP/RTCP packets to/from the underlying network.
 * @param user_data     Arbitrary user data (for future callback feature).
 * @param p_stream      Pointer to receive the video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_create(
                                        pjmedia_endpt *endpt,
                                        pj_pool_t *pool,
                                        pjmedia_vid_stream_info *info,
                                        pjmedia_transport *tp,
                                        void *user_data,
                                        pjmedia_vid_stream **p_stream);

/**
 * Destroy the video stream.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_destroy(pjmedia_vid_stream *stream);


/**
 * Get the media port interface of the stream. The media port interface
 * declares put_frame() and get_frame() function, which is the only 
 * way for application to transmit and receive media frames from the
 * stream. As bidirectional video streaming may have different video
 * formats in the encoding and decoding direction, there are two media
 * ports exported by the video stream, one for each direction.
 *
 * @param stream        The video stream.
 * @param dir           The video direction.
 * @param p_port        Pointer to receive the port interface.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_get_port(
                                            pjmedia_vid_stream *stream,
                                            pjmedia_dir dir,
                                            pjmedia_port **p_port);


/**
 * Get the media transport object associated with this stream.
 *
 * @param st            The video stream.
 *
 * @return              The transport object being used by the stream.
 */
PJ_DECL(pjmedia_transport*) pjmedia_vid_stream_get_transport(
                                            pjmedia_vid_stream *st);


/**
 * Get the stream statistics. See also #pjmedia_stream_get_stat_jbuf()
 *
 * @param stream        The video stream.
 * @param stat          Media stream statistics.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_get_stat(
                                            const pjmedia_vid_stream *stream,
                                            pjmedia_rtcp_stat *stat);

/**
 * Reset the video stream statistics.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_reset_stat(pjmedia_vid_stream *stream);


/**
 * Frame counters for one video stream, one per stage of the pipeline.
 *
 * These exist to answer a single question that RTP statistics cannot: when a remote
 * tile goes black, which stage stopped producing first. Counting at every stage makes
 * that a subtraction rather than a guess --- RTP arriving while #decoded stands still
 * is a decoder or keyframe problem, #decoded advancing while #render_submit stands
 * still is a video port or device problem, and so on.
 *
 * Monotonic for the life of one stream, and never reset: the caller states rates by
 * differencing two readings, and a counter that restarts under it would report a rate
 * that never happened. A rebuilt stream is a new stream with its own counters, which is
 * what keeps a new decoder from inheriting the history of the one it replaced.
 *
 * Plain unsigned words rather than pj_atomic_t, deliberately. Each counter has exactly
 * one writer --- the capture thread, the encoding thread, the decoding thread, the
 * renderer's clock --- and one reader that is allowed to be a sample behind. On every
 * architecture PJSIP runs on, an aligned 32-bit store is not torn, so the cost here is
 * one add and no barrier, no allocation and no lock on any media thread. This is the
 * same reasoning, and the same shape, as pjmedia_rtcp_stat's own counters.
 */
typedef struct pjmedia_vid_stream_frame_counters
{
    /** Frames delivered by the capture device to the video port. */
    pj_uint32_t  captured;

    /** Frames the encoder accepted and produced output for. */
    pj_uint32_t  encoded;

    /** Frames the decoder produced a picture from. */
    pj_uint32_t  decoded;

    /**
     * Frames handed to the video device for rendering.
     *
     * **Submission, not presentation.** On Android this ends at
     * `andgl_stream_put_frame()`, which posts the frame to an OpenGL job queue and
     * returns; the draw and the buffer swap happen later on another thread. A count
     * here therefore proves pjmedia did its part, and a black tile with this counter
     * advancing is a question for the renderer, the surface or the GPU --- which is
     * exactly the boundary this counter exists to draw.
     */
    pj_uint32_t  render_submit;

    /**
     * Submissions that carried a picture the previous one did not.
     *
     * The video port's clock submits on its own schedule --- pjmedia deliberately runs
     * the renderer at 1.5x the decoded rate (see get_frame() in vid_stream.c) --- so it
     * resubmits the last decoded frame whenever no new one has arrived. #render_submit
     * therefore counts submissions, and this counts *fresh* ones, by comparing each
     * frame's timestamp with the previous submission's.
     *
     * **Measured, and found not to work as intended.** On this pipeline the frame
     * reaching the device carries the video port's own timestamp rather than the
     * decoder's, so every submission looks new and this counter tracks #render_submit
     * exactly --- including on a leg measured decoding nothing at all (1001 <-> 1005,
     * 2026-09-25: decode 0.0 fps, render-submit 38.0, new 38.0).
     *
     * It is kept because that equality is itself the evidence, and because telling a
     * repeat from a new picture here would mean reaching into the renderer, which is
     * out of scope. Until then: `render_submit` proves pjmedia is still submitting, and
     * only `decoded` proves a new picture was produced.
     */
    pj_uint32_t  render_submit_new;

    /* --- send-path diagnostics ----------------------------------------------
     *
     * To place the boundary where ~30 captured frames become ~7 encoded pictures:
     * before the codec (the encoder is simply not being offered frames) or at it
     * (it is offered 30 and produces 7).
     */

    /** Frames handed to the stream's encoding port. The encoder's actual input. */
    pj_uint32_t  enc_input;

    /** Of those, ones dropped before the codec: stream paused, or an empty frame. */
    pj_uint32_t  enc_skip_paused;
    pj_uint32_t  enc_skip_empty;

    /** Calls into the codec. Pairs with `encoded`, which counts those that produced. */
    pj_uint32_t  enc_begin;

    /** Total microseconds spent inside encode_begin(), for a mean call latency. */
    pj_uint32_t  enc_usec;

    /* --- receive-path diagnostics -------------------------------------------
     *
     * Added to answer one question the stage counters could not: when RTP is
     * arriving at zero loss and `decoded` stands still, which operation between the
     * two stops. Counts and reasons, never a line per packet.
     */

    /** RTP payloads accepted and put into the jitter buffer. */
    pj_uint32_t  jbuf_put;

    /** Times decode_frame() ran its eligibility scan over the buffer. */
    pj_uint32_t  scan;

    /** Scan outcomes: a usable payload, a gap, and the end of the buffer. */
    pj_uint32_t  scan_normal;
    pj_uint32_t  scan_missing;
    pj_uint32_t  scan_empty;

    /** Scans that found enough distinct timestamps to assemble a picture. */
    pj_uint32_t  assembled;

    /** Calls into the codec, and those that returned an error. */
    pj_uint32_t  decode_call;
    pj_uint32_t  decode_err;

    /**
     * The decoding-delay target the scan must reach, in whole pictures.
     *
     * A level, not a counter: decode_frame() only assembles once it has seen this
     * many distinct RTP timestamps, and the test is an equality. Reported because a
     * target the scan can never reach is one way a buffer grows while nothing decodes.
     */
    pj_uint32_t  delay_target;

    /**
     * Frames the video device refused.
     *
     * The same call site as #render_submit, counted apart. A device that is not running
     * returns PJ_EINVALIDOP per frame, which is silent otherwise and is precisely the
     * shape of a surface that went away without pjmedia being told.
     */
    pj_uint32_t  render_reject;

} pjmedia_vid_stream_frame_counters;


/**
 * Read a stream's frame counters.
 *
 * @param stream        The video stream.
 * @param counters      Filled with the counters as they stand.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_get_frame_counters(
                            const pjmedia_vid_stream *stream,
                            pjmedia_vid_stream_frame_counters *counters);


/**
 * Get the stream's counter block, so the video port wired to this stream can count
 * the two stages that happen inside it (capture and render submission).
 *
 * The pointer is owned by the stream and is valid for as long as it is. The caller is
 * pjsua, which connects port and stream and is therefore the only party that knows
 * they belong together --- and which must clear it again before the stream is
 * destroyed, so a port that outlives its stream cannot write into freed memory.
 *
 * @param stream        The video stream.
 *
 * @return              The counter block, or NULL if @a stream is NULL.
 */
PJ_DECL(pjmedia_vid_stream_frame_counters*) pjmedia_vid_stream_get_counter_block(
                            pjmedia_vid_stream *stream);


/**
 * Get current jitter buffer state. See also #pjmedia_stream_get_stat()
 *
 * @param stream        The video stream.
 * @param state         Jitter buffer state.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_get_stat_jbuf(
                                            const pjmedia_vid_stream *stream,
                                            pjmedia_jb_state *state);


/**
 * Get the stream info.
 *
 * @param stream        The video stream.
 * @param info          Video stream info.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_get_info(
                                            const pjmedia_vid_stream *stream,
                                            pjmedia_vid_stream_info *info);


/**
 * Start the video stream. This will start the appropriate channels
 * in the video stream, depending on the video direction that was set
 * when the stream was created.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_start(pjmedia_vid_stream *stream);


/**
 * Modify the video stream's codec parameter after the codec is opened.
 * Note that not all codec backends support modifying parameters during
 * runtime and only certain parameters can be changed.
 *
 * Currently, only Video Toolbox and OpenH264 backends support runtime
 * adjustment of encoding bitrate (avg_bps and max_bps).
 *
 * @param stream        The video stream.
 * @param param         The new codec parameter.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t)
pjmedia_vid_stream_modify_codec_param(pjmedia_vid_stream *stream,
                                      const pjmedia_vid_codec_param *param);


/**
 * Query if the stream is started on the specified direction.
 *
 * @param stream        The video stream.
 * @param dir           The direction to be checked.
 *
 * @return              PJ_TRUE if stream is started.
 */
PJ_DECL(pj_bool_t) pjmedia_vid_stream_is_running(pjmedia_vid_stream *stream,
                                                 pjmedia_dir dir);

/**
 * Pause stream channels.
 *
 * @param stream        The video stream.
 * @param dir           Which channel direction to pause.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_pause(pjmedia_vid_stream *stream,
                                              pjmedia_dir dir);

/**
 * Resume stream channels.
 *
 * @param stream        The video stream.
 * @param dir           Which channel direction to resume.
 *
 * @return              PJ_SUCCESS on success;
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_resume(pjmedia_vid_stream *stream,
                                               pjmedia_dir dir);


/**
 * Force stream to send video keyframe on the next transmission.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success;
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_send_keyframe(
                                                pjmedia_vid_stream *stream);


/**
 * Send RTCP SDES for the video stream.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_send_rtcp_sdes(
                                                pjmedia_vid_stream *stream);


/**
 * Send RTCP BYE for the video stream.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_send_rtcp_bye(
                                                pjmedia_vid_stream *stream);


/**
 * Send RTCP PLI for the video stream.
 *
 * @param stream        The video stream.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t) pjmedia_vid_stream_send_rtcp_pli(
                                                pjmedia_vid_stream *stream);


/**
 * Get the RTP session information of the video media stream. This function 
 * can be useful for app with custom media transport to inject/filter some 
 * outgoing/incoming proprietary packets into normal video RTP traffics.
 * This will return the original pointer to the internal states of the stream,
 * and generally it is not advisable for app to modify them.
 * 
 * @param stream        The video media stream.
 *
 * @param session_info  The stream session info.
 *
 * @return              PJ_SUCCESS on success.
 */
PJ_DECL(pj_status_t)
pjmedia_vid_stream_get_rtp_session_info(pjmedia_vid_stream *stream,
                                   pjmedia_stream_rtp_sess_info *session_info);


/**
 * @}
 */

PJ_END_DECL


#endif  /* __PJMEDIA_VID_STREAM_H__ */
