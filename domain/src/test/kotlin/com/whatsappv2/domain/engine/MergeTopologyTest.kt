package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Which conference a merge builds (ADR-003, ADR-009).
 *
 * The rule decided here is the one that made the feature work at all: a device-hosted star
 * cannot show peers to each other, because a peer holds one leg and can only receive the
 * picture on it. Every case below is an enumeration of that rule rather than a check that
 * some code ran.
 */
class MergeTopologyTest {

    private val account = AccountId("acct-1")

    private fun call(
        id: String,
        video: Boolean = false,
        state: CallState = CallState.Connected(),
    ) = CallSnapshot(
        callId = CallId(id),
        accountId = account,
        remote = requireNotNull(SipUri.parse("sip:$id@sip.example.com").getOrNull()),
        remoteDisplayName = null,
        direction = CallDirection.OUTGOING,
        state = state,
        media = if (video) MediaProfile.AUDIO_VIDEO else MediaProfile.AUDIO,
        startedAtEpochMillis = 0,
        connectedAtEpochMillis = 0,
    )

    @Test
    fun `two audio calls are mixed on the device`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002"), call("1003")),
            bridgeConfigured = true,
        )

        // Configured bridge and all, because audio mixed here needs no server at all —
        // routing it through one would hand back the dependency ADR-009 removed.
        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), mix.callIds)
    }

    @Test
    fun `two video calls go to the bridge`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003", video = true)),
            bridgeConfigured = true,
        )

        val bridge = assertIs<MergeTopology.Bridge>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), bridge.callIds)
    }

    @Test
    fun `one video leg is enough to send the whole conference to the bridge`() {
        // The defect this prevents: mixing here and bridging there would be two
        // conferences that cannot hear each other. The bridge takes the audio-only member
        // happily and leaves them off the canvas.
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003"), call("1004")),
            bridgeConfigured = true,
        )

        val bridge = assertIs<MergeTopology.Bridge>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003"), CallId("1004")), bridge.callIds)
    }

    @Test
    fun `a video merge with no bridge configured is declined, not downgraded`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003", video = true)),
            bridgeConfigured = false,
        )

        // Declining says what is wrong. Silently dropping to audio would take the cameras
        // off two people who asked for a video conference and tell them nothing.
        val unavailable = assertIs<MergeTopology.Unavailable>(topology)
        assertEquals(MergeTopology.Unavailable.Reason.NO_BRIDGE_CONFIGURED, unavailable.reason)
    }

    @Test
    fun `ringing and held calls are not merged, but established ones still are`() {
        val topology = MergeTopology.of(
            calls = listOf(
                call("1002"),
                call("1003"),
                call("1005", state = CallState.Outgoing.Calling),
            ),
            bridgeConfigured = true,
        )

        // A ringing call has no media to contribute; it joins when it is answered.
        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), mix.callIds)
    }

    @Test
    fun `one established call is not a conference`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002"), call("1003", state = CallState.Outgoing.Calling)),
            bridgeConfigured = true,
        )

        val unavailable = assertIs<MergeTopology.Unavailable>(topology)
        assertEquals(MergeTopology.Unavailable.Reason.NOT_ENOUGH_CALLS, unavailable.reason)
    }

    @Test
    fun `four video participants are within the ceiling`() {
        // The size this feature is specified to: the user plus three, which is four legs
        // into the room. Named so the ceiling changing is a test failure and not a surprise.
        val topology = MergeTopology.of(
            calls = listOf(
                call("1002", video = true),
                call("1003", video = true),
                call("1004", video = true),
            ),
            bridgeConfigured = true,
        )

        assertIs<MergeTopology.Bridge>(topology)
    }

    @Test
    fun `a fifth participant is refused on video, and allowed on audio`() {
        // Four is the video ceiling counting this handset, so three legs is the most that
        // can be merged. Audio's ceiling is ADR-009's eight and is a different number for
        // a different reason, so the same four legs mix here quite happily.
        val fourLegs = listOf("1002", "1003", "1004", "1005")

        val video = MergeTopology.of(
            calls = fourLegs.map { call(it, video = true) },
            bridgeConfigured = true,
        )
        assertEquals(
            MergeTopology.Unavailable.Reason.TOO_MANY_CALLS,
            assertIs<MergeTopology.Unavailable>(video).reason,
        )

        val audio = MergeTopology.of(
            calls = fourLegs.map { call(it) },
            bridgeConfigured = true,
        )
        assertIs<MergeTopology.LocalMix>(audio)
    }

    @Test
    fun `more calls than the ceiling is refused`() {
        val tooMany = (1..9).map { call("100$it") }

        val unavailable = assertIs<MergeTopology.Unavailable>(
            MergeTopology.of(tooMany, bridgeConfigured = true),
        )
        assertEquals(MergeTopology.Unavailable.Reason.TOO_MANY_CALLS, unavailable.reason)
    }
}
