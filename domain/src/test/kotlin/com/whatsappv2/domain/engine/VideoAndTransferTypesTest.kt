package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The small value types Tasks 51-55 added, including the two "nothing here" defaults. */
class VideoAndTransferTypesTest {

    private val callId = CallId("call-1")
    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    @Test
    fun `a conference tile is never given another participant's aspect ratio`() {
        // The stretching bug this exists to stop. `remote` is whichever call decoded most
        // recently, so an unconditional fallback handed a tile whose own shape had not
        // arrived yet somebody else's -- a real shape, and the wrong one. The renderer scales
        // each stream to the shape of the view it is given, so a 16:9 peer drawn into a box
        // built for a 9:16 one comes out as a column.
        val sizes = VideoSizes(
            remote = VideoSize(720, 1280),
            remotes = mapOf("call-a" to VideoSize(640, 360)),
        )

        assertEquals(VideoSize(640, 360), sizes.remoteFor("call-a"), "a known tile keeps its own shape")
        assertEquals(
            VideoSize.UNKNOWN, sizes.remoteFor("call-b"),
            "an unknown tile must not borrow the 720x1280 shape of another participant",
        )
    }

    @Test
    fun `a one-to-one call still guesses before its first frame, because the guess is its own`() {
        // The narrow case the fallback is kept for: nothing has decoded yet, so `remote` is
        // the only shape there is and a frame later it will be this very call's. Sizing the
        // view to nothing here is a visible flash at the start of every call.
        val sizes = VideoSizes(remote = VideoSize(640, 360))

        assertEquals(VideoSize(640, 360), sizes.remoteFor("call-a"))
    }

    @Test
    fun `an empty sizes object reports nothing rather than a made-up shape`() {
        assertEquals(VideoSize.UNKNOWN, VideoSizes.UNKNOWN.remoteFor("call-a"))
    }

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
        NoVideoSurfaces.attach(remoteViews = mapOf("call-a" to Any()), localPreview = null)
        NoVideoSurfaces.detach()
        NoVideoSurfaces.detach()
        NoVideoSurfaces.setDisplayRotation(90)
        assertEquals(VideoSizes.UNKNOWN, NoVideoSurfaces.videoSizes.value, "no renderer, no frames to measure")
    }

    @Test
    fun `a frame's size is known only with both axes, and its aspect follows`() {
        // What the call screen sizes the remote view by: an unknown frame must not
        // produce a 0-by-something box or a division by zero.
        assertFalse(VideoSize.UNKNOWN.isKnown)
        assertEquals(0f, VideoSize.UNKNOWN.aspectRatio)
        assertFalse(VideoSize(640, 0).isKnown)

        val landscape = VideoSize(1280, 720)
        assertTrue(landscape.isKnown)
        assertEquals(1280f / 720f, landscape.aspectRatio)
        assertEquals(VideoSize(720, 1280), landscape.transposed(), "a quarter turn swaps the axes")
        assertEquals(landscape, landscape.transposed().transposed())
    }
}
