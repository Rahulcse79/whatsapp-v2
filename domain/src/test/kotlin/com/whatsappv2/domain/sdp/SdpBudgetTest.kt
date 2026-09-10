package com.whatsappv2.domain.sdp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SDP size budget, against the offer that was actually measured on the wire.
 *
 * Every number here came off a handset or off a probe of the reference server on
 * 2026-09-10. The point of the test is not that the arithmetic is right — it is that a
 * change which re-inflates the offer past what the network carries fails the build instead
 * of producing a call that rings for thirty-two seconds and dies.
 */
class SdpBudgetTest {

    /** The audio INVITE measured on the device: 1742 bytes total, 1092 of them SDP. */
    private companion object {
        const val MEASURED_AUDIO_SDP = 1092
        const val MEASURED_AUDIO_INVITE = 1742
        const val MEASURED_HEADERS = MEASURED_AUDIO_INVITE - MEASURED_AUDIO_SDP
    }

    @Test
    fun `the header estimate matches the INVITE that was measured`() {
        // If this drifts, every other figure below is being computed against a fiction.
        assertEquals(SdpBudget.TYPICAL_INVITE_HEADER_BYTES, MEASURED_HEADERS)
    }

    @Test
    fun `the measured limit is the Ethernet MTU minus the IP and UDP headers`() {
        // SHOW YOUR WORKING: 1472 + 8 + 20 = 1500. The probe found 1472 answered and 1475
        // not, which is this arithmetic and not a coincidence.
        assertEquals(1500, SdpBudget.MAX_UDP_REQUEST_BYTES + 8 + 20)
    }

    @Test
    fun `the offer this app actually sent does not fit, which is the reported defect`() {
        // 1742 bytes was retransmitted seven times over 32 seconds and drew no response of
        // any kind. This is that call, as a number.
        assertFalse(SdpBudget.fitsOneDatagram(MEASURED_AUDIO_SDP))
        assertEquals(470, SdpBudget.excessBytes(MEASURED_AUDIO_SDP))
    }

    @Test
    fun `dropping the two AES_256 suites and ICE gets the audio offer delivered`() {
        // The fix, as arithmetic. 1092 SDP bytes, less two 116-byte crypto lines, less the
        // 198 bytes of ICE, is 662 — and 662 + 650 headers = 1312, against a measured hard
        // limit of 1472. The datagram is no longer fragmented, so it is no longer dropped,
        // which is the whole of the reported defect.
        val trimmed = MEASURED_AUDIO_SDP -
            (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES) -
            SdpBudget.ICE_BYTES_PER_MEDIA_LINE

        assertEquals(662, trimmed)
        assertTrue(
            trimmed + SdpBudget.TYPICAL_INVITE_HEADER_BYTES <= SdpBudget.MAX_UDP_REQUEST_BYTES,
            "a trimmed audio offer must fit one datagram, or audio calls do not connect",
        )
    }

    @Test
    fun `the trimmed offer is still 40 bytes short of RFC 3261's headroom, and says so`() {
        // The honest half, and the reason `fitsOneDatagram` is not asserted above. §18.1.1
        // wants 200 bytes of margin below the MTU; the trimmed offer leaves 160. So it is
        // delivered on THIS path and would stop being delivered on one with a smaller MTU,
        // or the moment a proxy adds a Record-Route header.
        //
        // Closing the last 40 bytes means one telephone-event clock rate instead of two
        // (PJMEDIA_TELEPHONE_EVENT_ALL_CLOCKRATES, worth ~52 bytes), which is a native
        // rebuild. Recorded as a number rather than rounded away, so nobody has to
        // rediscover it from a call that fails on a different network.
        val trimmed = MEASURED_AUDIO_SDP -
            (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES) -
            SdpBudget.ICE_BYTES_PER_MEDIA_LINE

        assertFalse(SdpBudget.fitsOneDatagram(trimmed))
        assertEquals(40, SdpBudget.excessBytes(trimmed))
    }

    @Test
    fun `a video offer does not fit even after every trim, and the budget says so`() {
        // The honest half of item 2. A second m-line costs roughly what the first one did:
        // its own crypto block, its own ICE candidates, rtpmap entries for VP8 and H264, an
        // H264 fmtp and rtcp-fb attributes. Even at the trimmed rate it does not fit, which
        // is why video needs a TCP listener on the server and not only a smaller offer.
        val trimmedAudio = MEASURED_AUDIO_SDP -
            (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES) -
            SdpBudget.ICE_BYTES_PER_MEDIA_LINE
        val videoMediaLine = 400 + (2 * SdpBudget.AES_128_CRYPTO_LINE_BYTES)

        assertFalse(
            SdpBudget.fitsOneDatagram(trimmedAudio + videoMediaLine),
            "if this ever passes, re-measure on a device before claiming video is fixed",
        )
    }

    @Test
    fun `the safe bound leaves RFC 3261's 200 bytes of headroom`() {
        assertEquals(1272, SdpBudget.SAFE_UDP_REQUEST_BYTES)
        assertEquals(622, SdpBudget.SAFE_SDP_BYTES)
    }

    @Test
    fun `an offer that fits reports no excess`() {
        // The 781-byte INVITE that DID draw a 100 Trying, expressed as the budget sees it.
        val sdp = 781 - MEASURED_HEADERS
        assertTrue(SdpBudget.fitsOneDatagram(sdp))
        assertEquals(0, SdpBudget.excessBytes(sdp))
    }
}
