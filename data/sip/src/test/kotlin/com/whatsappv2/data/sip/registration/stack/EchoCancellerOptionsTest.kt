package com.whatsappv2.data.sip.registration.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `medConfig.ecOptions` carries the configuration that was measured, and only that.
 *
 * These are not tautologies dressed as tests. `ecOptions` is a single integer handed to a
 * native library and every way it can be wrong is silent - or, in one case, fatal:
 *
 *  - setting `PJMEDIA_ECHO_WEBRTC` **aborts the process on the first captured frame** at
 *    this app's 48 kHz clock rate. `WebRtcAec_Init` computes `num_bands = 48000/16000 = 3`
 *    and `webrtc_aec_cancel_echo` then passes `echo->channel_count`, which is 1, into
 *    WebRTC's band-count parameter; `aec_core.c:1765` asserts they are equal. That is a
 *    SIGABRT in the audio callback, found on a Samsung M23 on 2026-10-04, and this test is
 *    the thing standing between it and a second occurrence;
 *  - a flag naming an unimplemented feature records a capability the build has not got;
 *  - dropping the speech-enhancer bit turns the denoiser off and nothing says so, because
 *    the fallback path is deliberately silent.
 *
 * Nothing at this layer can be covered by an instrumented test either: the effect is inside
 * a native sound port, on a device, in a call. So the composition is asserted here, against
 * the numbers in `pjmedia/include/pjmedia/echo.h`, and the reasoning for each bit - set or
 * refused - lives in [RealPjsipCoreGateway.EC_OPTIONS]'s documentation.
 */
class EchoCancellerOptionsTest {

    private companion object {
        // Spelled out again rather than imported from the production constants, on purpose:
        // a test that reads the same private constants it is checking would pass if somebody
        // changed one of them, which is the single thing it exists to catch. These numbers
        // come from pjmedia/include/pjmedia/echo.h and nowhere else.
        const val ALGO_MASK = 15L
        const val ECHO_DEFAULT = 0L
        const val WEBRTC = 3L
        const val WEBRTC_AEC3 = 4L
        const val USE_NOISE_SUPPRESSOR = 128L
        const val USE_GAIN_CONTROLLER = 256L
        const val USE_SPEECH_ENHANCER = 2048L
        const val AGGRESSIVENESS_MASK = 0xF000L
    }

    @Test
    fun `the speech enhancer is on, because it is the whole point`() {
        assertTrue(
            RealPjsipCoreGateway.EC_OPTIONS and USE_SPEECH_ENHANCER != 0L,
            "PJMEDIA_ECHO_USE_SPEECH_ENHANCER is what switches RNNoise on in the capture " +
                "path. Without it there is no denoiser at all: measured, the difference " +
                "is DNSMOS BAK 3.93 against 2.06 at 5 dB SNR",
        )
    }

    @Test
    fun `the algorithm field is left at the default, which resolves to Speex`() {
        assertEquals(
            ECHO_DEFAULT,
            RealPjsipCoreGateway.EC_OPTIONS and ALGO_MASK,
            "`echo_common.c` resolves PJMEDIA_ECHO_DEFAULT through an `else if` chain that " +
                "tests Speex at :212 before WebRTC at :219, so 0 selects the Speex " +
                "canceller - the one ADR-009 measured and the one every call this app has " +
                "made has used",
        )
    }

    @Test
    fun `WebRTC AEC is never selected, because it aborts the process at 48 kHz`() {
        assertTrue(
            RealPjsipCoreGateway.EC_OPTIONS and ALGO_MASK != WEBRTC,
            "see the class documentation: aec_core.c:1765 asserts num_bands, pjmedia " +
                "passes channel_count, and the process dies in the capture callback",
        )
    }

    @Test
    fun `AEC3 is never selected, because no AEC3 symbol exists in the built library`() {
        // Checked as the whole algorithm field and not as a bit test: AEC3 is 4 and WebRTC
        // is 3, so `and WEBRTC_AEC3` is non-zero for several values that are not AEC3, and
        // a bit test here would be satisfied by a bug.
        assertTrue(
            RealPjsipCoreGateway.EC_OPTIONS and ALGO_MASK != WEBRTC_AEC3,
            "`strings libpjsua2.so` finds no `aec3` at all, so naming it would select an " +
                "absent algorithm and disable echo cancellation entirely",
        )
    }

    @Test
    fun `no WebRTC-backend-only flag is set, since that backend cannot be used`() {
        // USE_NOISE_SUPPRESSOR is read only at echo_webrtc.c:192 and the aggressiveness
        // bits only by its set_config(). With the WebRTC backend unusable here they are
        // inert, and an inert flag in a configuration word is a claim the build cannot
        // honour.
        assertEquals(
            0L,
            RealPjsipCoreGateway.EC_OPTIONS and USE_NOISE_SUPPRESSOR,
            "PJMEDIA_ECHO_USE_NOISE_SUPPRESSOR is a WebRTC-backend flag",
        )
        assertEquals(
            0L,
            RealPjsipCoreGateway.EC_OPTIONS and AGGRESSIVENESS_MASK,
            "the aggressiveness bits reach only AecConfig.nlpMode, in the WebRTC backend",
        )
    }

    @Test
    fun `the gain controller is not claimed, because pjmedia implements it nowhere`() {
        assertEquals(
            0L,
            RealPjsipCoreGateway.EC_OPTIONS and USE_GAIN_CONTROLLER,
            "PJMEDIA_ECHO_USE_GAIN_CONTROLLER is declared in echo.h:129 and read by no " +
                "backend; the library carries no WebRtcAgc_* symbol",
        )
    }

    @Test
    fun `the whole word is exactly one flag and nothing else`() {
        // The backstop. Each test above pins one property; this one fails if a second flag
        // is added without a decision being recorded next to it.
        assertEquals(USE_SPEECH_ENHANCER, RealPjsipCoreGateway.EC_OPTIONS)
        assertEquals(2048L, RealPjsipCoreGateway.EC_OPTIONS)
    }
}
