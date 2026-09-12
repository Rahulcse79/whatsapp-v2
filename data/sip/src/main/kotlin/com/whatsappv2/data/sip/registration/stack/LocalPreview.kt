package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import org.pjsip.pjsua2.VideoPreview
import org.pjsip.pjsua2.VideoPreviewOpParam
import org.pjsip.pjsua2.VideoWindowHandle

/**
 * This device's own picture, drawn into the call screen's preview surface (Task 52).
 *
 * `PjCall.applyVideoWindows` cannot do it: a sendrecv stream has one window, and it shows
 * the far end. The local picture lives in the *preview* window that `setup_vid_capture`
 * creates for the capture device — hidden, and with its renderer never started, because
 * pjsua only needs the capture feeding the encoder. `pjsua_vid_preview_start` on the same
 * device finds that window, hands it the surface, and starts the renderer. Until this
 * existed the call screen's preview view was a hole in the remote picture: the surface it
 * offered was attached to nothing (TC15, 2026-09-11).
 *
 * Started again with a new surface, PJSIP swaps the window and returns — that is the
 * re-attach after the screen is recreated. The preview takes a reference on the capture
 * window, and a stopped capture with a running preview keeps the camera open, so [stop]
 * runs before every `STOP_TRANSMIT`, before a camera switch, and before the call is
 * released.
 *
 * One per call, and only ever touched on the PJSIP thread.
 */
internal class LocalPreview(private val logger: Logger) {

    private var preview: VideoPreview? = null

    /** Draws [captureDevice]'s picture into [surface]; the device may be PJSIP's "default". */
    fun draw(captureDevice: Int, surface: Any) {
        runCatching {
            val running = preview ?: VideoPreview(captureDevice).also { preview = it }
            running.start(
                VideoPreviewOpParam().apply {
                    window = VideoWindowHandle().apply { handle.setWindow(surface) }
                    show = true
                },
            )
        }.onFailure { logger.warn(TAG, "Could not draw the local preview: ${it.message}") }
    }

    fun stop() {
        val running = preview ?: return
        preview = null
        runCatching { running.stop() }
            .onFailure { logger.warn(TAG, "Could not stop the local preview: ${it.message}") }
        runCatching { running.delete() }
    }

    private companion object {
        const val TAG = "PjsipGateway"
    }
}
