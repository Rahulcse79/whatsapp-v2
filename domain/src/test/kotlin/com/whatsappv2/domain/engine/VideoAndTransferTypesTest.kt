package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The small value types Tasks 51-55 added, including the two "nothing here" defaults. */
class VideoAndTransferTypesTest {

    private val callId = CallId("call-1")
    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    @Test
    fun `a video request redacts the caller's address`() {
        val request = VideoRequest(callId, bob, fromDisplayName = "Bob", receivedAtEpochMillis = 1)

        // §7, DoD 12: the remote URI is a phone number, and SipUri redacts its own.
        val rendered = request.toString()
        assertTrue(rendered.contains("call-1"))
        assertFalse(rendered.contains("bob@example.com"))
    }

    @Test
    fun `every transfer event names the call it is about`() {
        val events = listOf(
            TransferEvent.Accepted(callId, TransferType.BLIND),
            TransferEvent.Progressing(callId, responseCode = 180),
            TransferEvent.Succeeded(callId),
            TransferEvent.Failed(callId, SipError.Busy(486)),
        )

        assertTrue(events.all { it.callId == callId })
    }

    @Test
    fun `a context with no camera says so rather than throwing`() {
        // The honest default for a graph with nothing bound: video downgrades to audio,
        // and the call still goes out (Task 51).
        assertFalse(NoCameraAvailable.isCameraUsable())
    }

    @Test
    fun `a context with no renderer accepts surfaces and does nothing with them`() {
        // Safe to call in either order and with nothing attached, which is what a
        // composable's onDispose needs (Task 52).
        NoVideoSurfaces.attach(remoteView = Any(), localPreview = null)
        NoVideoSurfaces.detach()
        NoVideoSurfaces.detach()
    }

}
