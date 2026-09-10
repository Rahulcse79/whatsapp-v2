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
    fun `trimming the crypto suites alone does NOT get the offer delivered`() {
        // The correction that matters, and it was wrong in this file until it was measured
        // against the real INVITE rather than against an estimate. The two AES_256 lines are
        // 108 bytes each, not 116 — so the trim saves 216 and the offer lands at 1526, which
        // is still 54 bytes above the 1472 the path carries. Still fragmented. Still dropped.
        //
        // The SRTP trim is therefore a necessary part of the fix and not the whole of it.
        val cryptoOnly = MEASURED_AUDIO_SDP - (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES)

        assertEquals(1526, cryptoOnly + SdpBudget.TYPICAL_INVITE_HEADER_BYTES)
        assertTrue(
            cryptoOnly + SdpBudget.TYPICAL_INVITE_HEADER_BYTES > SdpBudget.MAX_UDP_REQUEST_BYTES,
            "the crypto trim alone leaves the INVITE over the path limit",
        )
    }

    @Test
    fun `the crypto trim AND ICE off is what gets the offer delivered`() {
        // 1742 - 216 (two AES_256 lines) - 198 (ice-ufrag, ice-pwd, two candidates) = 1328,
        // which is under the 1472 the path carries. ICE is an ACCOUNT setting and
        // NatPolicy.DEFAULT turns it on, so this only holds for an account configured with
        // ICE off — on a flat LAN, which is where these calls run, it buys nothing anyway.
        val trimmed = MEASURED_AUDIO_SDP -
            (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES) -
            SdpBudget.ICE_BYTES_PER_MEDIA_LINE

        assertEquals(1328, trimmed + SdpBudget.TYPICAL_INVITE_HEADER_BYTES)
        assertTrue(
            trimmed + SdpBudget.TYPICAL_INVITE_HEADER_BYTES <= SdpBudget.MAX_UDP_REQUEST_BYTES,
            "with ICE off the offer fits one datagram",
        )
    }

    @Test
    fun `even the fully trimmed offer is short of RFC 3261's headroom, and says so`() {
        // §18.1.1 wants 200 bytes of margin below the MTU; 1328 leaves 144. So it is
        // delivered on THIS path and would stop being delivered on one with a smaller MTU,
        // or the moment a proxy adds a Record-Route header. Recorded as a number rather than
        // rounded away.
        val trimmed = MEASURED_AUDIO_SDP -
            (2 * SdpBudget.AES_256_CRYPTO_LINE_BYTES) -
            SdpBudget.ICE_BYTES_PER_MEDIA_LINE

        assertFalse(SdpBudget.fitsOneDatagram(trimmed))
        assertEquals(56, SdpBudget.excessBytes(trimmed))
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

        assertTrue(
            trimmedAudio + videoMediaLine + SdpBudget.TYPICAL_INVITE_HEADER_BYTES >
                SdpBudget.MAX_UDP_REQUEST_BYTES,
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
