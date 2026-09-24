package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who sends video to whom, as arithmetic (2026-09-22).
 *
 * The whole point of a pure planner is that the questions that matter — does anybody get
 * their own face back, does everybody get everybody else, does the fifth participant get
 * refused — are decided here rather than discovered on four handsets held by four people.
 */
class VideoMixTest {

    private val a = "call-a"
    private val b = "call-b"
    private val c = "call-c"

    private fun sourcesFeeding(links: Set<VideoLink>, sink: VideoPortRef): Set<VideoPortRef> =
        links.filterTo(mutableSetOf()) { it.to == sink }.mapTo(mutableSetOf()) { it.from }

    @Test
    fun `one remote leg is a call, not a conference, and is left to pjsua`() {
        // pjsua already wires camera -> encoder and decoder -> window for a 1:1 call.
        // Re-stating it here would make every ordinary video call pay for the
        // conference's bookkeeping.
        assertTrue(VideoMix.wanted(emptySet()).isEmpty())
        assertTrue(VideoMix.wanted(setOf(a)).isEmpty())
    }

    @Test
    fun `nobody is sent their own picture back`() {
        val links = VideoMix.wanted(setOf(a, b, c))

        for (member in listOf(a, b, c)) {
            val feeding = sourcesFeeding(links, VideoPortRef.encoder(member))
            assertFalse(
                VideoPortRef.decoder(member) in feeding,
                "$member is being sent its own stream back: $feeding",
            )
        }
    }

    @Test
    fun `each peer is sent our camera and every other peer, and nothing else`() {
        val links = VideoMix.wanted(setOf(a, b, c))

        assertEquals(
            setOf(VideoPortRef.camera, VideoPortRef.decoder(b), VideoPortRef.decoder(c)),
            sourcesFeeding(links, VideoPortRef.encoder(a)),
        )
        assertEquals(
            setOf(VideoPortRef.camera, VideoPortRef.decoder(a), VideoPortRef.decoder(c)),
            sourcesFeeding(links, VideoPortRef.encoder(b)),
        )
        assertEquals(
            setOf(VideoPortRef.camera, VideoPortRef.decoder(a), VideoPortRef.decoder(b)),
            sourcesFeeding(links, VideoPortRef.encoder(c)),
        )
    }

    @Test
    fun `nothing is composed for our own screen`() {
        // The screen draws a tile per participant from each call's own window, so the
        // mixer composes only what a peer cannot compose for itself. A link into a local
        // renderer here would be a picture the screen could neither label nor lay out.
        val links = VideoMix.wanted(setOf(a, b, c))

        val sinks = links.mapTo(mutableSetOf()) { it.to }
        assertEquals(setOf(a, b, c).mapTo(mutableSetOf()) { VideoPortRef.encoder(it) }, sinks)
    }

    @Test
    fun `no sink is ever given more sources than the mixer will draw`() {
        // `vid_conf` composes four sources onto a sink and silently does not draw a fifth.
        // At the product ceiling the busiest sink carries three, one inside the limit —
        // and this test is what stops MAX_PARTICIPANTS being raised without the mixer
        // being raised with it.
        val members = (1..VideoMix.MAX_MEMBERS).mapTo(mutableSetOf()) { "call-$it" }
        val links = VideoMix.wanted(members)

        val sinks = links.mapTo(mutableSetOf()) { it.to }
        sinks.forEach { sink ->
            val count = sourcesFeeding(links, sink).size
            assertTrue(count <= VID_CONF_MAX_SOURCES, "$sink has $count sources, mixer draws $VID_CONF_MAX_SOURCES")
        }
    }

    @Test
    fun `a four-party conference is nine links`() {
        // Three peers, each encoder taking the camera and the two other decoders. Nothing
        // is composed for this screen. Stated as a number so a change to the shape of the
        // arrangement has to be deliberate.
        assertEquals(9, VideoMix.wanted(setOf(a, b, c)).size)
    }

    @Test
    fun `the fifth participant is refused rather than quietly left out of the picture`() {
        assertFalse(VideoMix.isFull(setOf(a, b)))
        assertTrue(VideoMix.isFull(setOf(a, b, c)), "three remote legs plus this device is the ceiling")
        assertEquals(VideoMix.MAX_PARTICIPANTS - 1, VideoMix.MAX_MEMBERS)
    }

    @Test
    fun `planning opens only what is missing and closes only what is no longer wanted`() {
        val two = VideoMix.wanted(setOf(a, b))

        // Nothing established: open everything.
        assertEquals(two, VideoMix.plan(emptySet(), setOf(a, b)).connect)
        assertTrue(VideoMix.plan(emptySet(), setOf(a, b)).disconnect.isEmpty())

        // Everything established: a no-op, which is what makes remix safe on every event.
        assertTrue(VideoMix.plan(two, setOf(a, b)).isEmpty)
    }

    @Test
    fun `a member joining adds only its own links, and leaving removes only its own`() {
        val two = VideoMix.wanted(setOf(a, b))
        val three = VideoMix.wanted(setOf(a, b, c))

        val joining = VideoMix.plan(two, setOf(a, b, c))
        assertTrue(joining.disconnect.isEmpty(), "joining must not tear down the conference: ${joining.disconnect}")
        assertTrue(joining.connect.all { c in listOf(it.from.callKey, it.to.callKey) })

        val leaving = VideoMix.plan(three, VideoMix.without(setOf(a, b, c), c))
        assertTrue(leaving.connect.isEmpty(), "leaving must not open new links: ${leaving.connect}")
        assertTrue(leaving.disconnect.all { c in listOf(it.from.callKey, it.to.callKey) })
        assertEquals(two, three - leaving.disconnect)
    }

    @Test
    fun `rejoining after leaving gives back exactly the arrangement that was there before`() {
        // A leg that drops and comes back is the case a conference has to survive, and
        // the one where a stale link is silent: the member is in the roster and in
        // nobody's picture.
        val three = VideoMix.wanted(setOf(a, b, c))
        val afterLeaving = three - VideoMix.plan(three, setOf(a, b)).disconnect
        val rejoining = VideoMix.plan(afterLeaving, setOf(a, b, c))

        assertEquals(three, afterLeaving + rejoining.connect)
    }

    @Test
    fun `dropping to one member tears the whole mix down`() {
        val three = VideoMix.wanted(setOf(a, b, c))
        val plan = VideoMix.plan(three, setOf(a))

        assertEquals(three, plan.disconnect)
        assertTrue(plan.connect.isEmpty())
    }

    /** `vid_conf.c`: `tr_size[4]` and `for (i = 0; i < cp->transmitter_cnt && i < 4; ++i)`. */
    private companion object {
        const val VID_CONF_MAX_SOURCES = 4
    }

    @Test
    fun `a mesh composes no canvas for anybody`() {
        // Each peer receives every other peer's camera on its own dialog, so a composed
        // canvas as well would draw every participant twice — once in their own tile and
        // once inside somebody else's.
        assertTrue(VideoMix.wanted(setOf("a", "b", "c"), compose = false).isEmpty())
    }

    @Test
    fun `turning composition off closes the canvases that were open`() {
        val established = VideoMix.wanted(setOf("a", "b"))
        val plan = VideoMix.plan(established, setOf("a", "b"), compose = false)

        assertTrue(plan.connect.isEmpty())
        assertEquals(established, plan.disconnect)
    }
}
