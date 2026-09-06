package com.whatsappv2.data.sip.call

import com.whatsappv2.domain.engine.VideoSurfaceController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the call screen's views to the SIP stack (Task 52).
 *
 * A three-line class, and it earns its place: it is the only thing that connects a
 * composable in `:feature:calls` to a native renderer in `:data:sip` without either
 * knowing the other exists. The feature depends on the `:domain` port, this implements it,
 * and `:app` binds them — the same shape as `PlatformCallRegistry` and
 * `CameraAvailability`, and the arrangement architecture Rule 3 requires.
 */
@Singleton
internal class StackVideoSurfaceController @Inject constructor(
    private val gateway: LinphoneCallGateway,
) : VideoSurfaceController {

    override fun attach(remoteView: Any?, localPreview: Any?) {
        gateway.setVideoWindows(remoteView, localPreview)
    }

    /** Nulls, which is what the stack takes to mean "stop drawing and let go". */
    override fun detach() {
        gateway.setVideoWindows(remoteView = null, localPreview = null)
    }
}
