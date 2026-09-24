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
 * Whether a merge can be built, and out of which legs (ADR-009).
 *
 * Every conference is composed on this device now, so what is enumerated here is the two
 * ceilings and the sets they are counted over — the conference against ADR-009's eight,
 * and the picture against `vid_conf`'s four, over the legs carrying video alone. The
 * cases that used to choose a FreeSWITCH room are gone with the room.
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
        )

        // Audio mixed here needs no server at all — routing it through one would hand
        // back the dependency ADR-009 removed.
        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), mix.callIds)
    }

    @Test
    fun `an all-video merge is composed on the device`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003", video = true)),
        )

        // `pjmedia`'s video bridge gives each peer a canvas of this camera and every
        // other peer, so no room is needed for the picture.
        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), mix.callIds)
    }

    @Test
    fun `a merge of video and audio-only legs is mixed here, with every leg in it`() {
        // The case that cost a real conference on 2026-09-24. Two video legs and one
        // audio leg were sent to a room that answered 480, so three people got nothing;
        // before that they would have got an audio conference and lost the picture. The
        // mixer composes among the legs that have a camera and leaves the other in the
        // audio mix, which is what `videoMixable` then works out — every leg is merged
        // either way, which is what this asserts.
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003", video = true), call("1004")),
        )

        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003"), CallId("1004")), mix.callIds)
    }

    @Test
    fun `the video ceiling is counted over the video legs, not the conference`() {
        // Five legs: three on video, two on audio. The conference is inside ADR-009's
        // eight and the picture is inside `vid_conf`'s four — this handset plus three
        // cameras — so it is allowed. Counting the video ceiling over all five, which is
        // what the rule used to do, refused this merge outright.
        val topology = MergeTopology.of(
            calls = listOf(
                call("1002", video = true),
                call("1003", video = true),
                call("1004", video = true),
                call("1005"),
                call("1006"),
            ),
        )

        assertIs<MergeTopology.LocalMix>(topology)
    }

    @Test
    fun `a merge needs no conference room to be configured`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002", video = true), call("1003", video = true)),
        )

        // The whole point of composing the picture here: no server, so no dependency on
        // one being reachable or configured, and no way for a merge to fail for want of
        // a dialplan entry.
        assertIs<MergeTopology.LocalMix>(topology)
    }

    @Test
    fun `ringing and held calls are not merged, but established ones still are`() {
        val topology = MergeTopology.of(
            calls = listOf(
                call("1002"),
                call("1003"),
                call("1005", state = CallState.Outgoing.Calling),
            ),
        )

        // A ringing call has no media to contribute; it joins when it is answered.
        val mix = assertIs<MergeTopology.LocalMix>(topology)
        assertEquals(setOf(CallId("1002"), CallId("1003")), mix.callIds)
    }

    @Test
    fun `one established call is not a conference`() {
        val topology = MergeTopology.of(
            calls = listOf(call("1002"), call("1003", state = CallState.Outgoing.Calling)),
        )

        val unavailable = assertIs<MergeTopology.Unavailable>(topology)
        assertEquals(MergeTopology.Unavailable.Reason.NOT_ENOUGH_CALLS, unavailable.reason)
    }

    @Test
    fun `four video participants are within the ceiling`() {
        // The size this feature is specified to: the user plus three. Named so the ceiling
        // changing is a test failure and not a surprise. Composed on the device, because
        // the busiest sink then carries three sources — camera plus two decoders — which
        // is inside `vid_conf`'s four.
        val topology = MergeTopology.of(
            calls = listOf(
                call("1002", video = true),
                call("1003", video = true),
                call("1004", video = true),
            ),
        )

        assertIs<MergeTopology.LocalMix>(topology)
    }

    @Test
    fun `a fifth participant is refused on video, and allowed on audio`() {
        // Four is the video ceiling counting this handset, so three legs is the most that
        // can be merged. Audio's ceiling is ADR-009's eight and is a different number for
        // a different reason, so the same four legs mix here quite happily.
        val fourLegs = listOf("1002", "1003", "1004", "1005")

        val video = MergeTopology.of(
            calls = fourLegs.map { call(it, video = true) },
        )
        assertEquals(
            MergeTopology.Unavailable.Reason.TOO_MANY_CALLS,
            assertIs<MergeTopology.Unavailable>(video).reason,
        )

        val audio = MergeTopology.of(
            calls = fourLegs.map { call(it) },
        )
        assertIs<MergeTopology.LocalMix>(audio)
    }

    @Test
    fun `more calls than the ceiling is refused`() {
        val tooMany = (1..9).map { call("100$it") }

        val unavailable = assertIs<MergeTopology.Unavailable>(
            MergeTopology.of(tooMany),
        )
        assertEquals(MergeTopology.Unavailable.Reason.TOO_MANY_CALLS, unavailable.reason)
    }
}
