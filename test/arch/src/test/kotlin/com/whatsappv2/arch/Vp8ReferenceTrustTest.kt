package com.whatsappv2.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The VP8 decoder's reference-trust invariant, asserted against the vendored source.
 *
 * ## The defect this exists to stop coming back
 *
 * `and_vid_mediacodec.cpp` refuses to hand the decoder a partial VP8 picture — a missing
 * frame start, an interior RTP hole, a tail short of the declared first partition, a packet
 * that never reached the component's input buffer. Every one of those refusals is correct,
 * and every one of them also **breaks the decoder's reference chain**: the picture that was
 * refused is the one the next inter-frame is coded against.
 *
 * Until 2026-09-30 the next inter-frame was fed in anyway. `dec_seen_keyframe` was set by
 * the first keyframe of a stream and never cleared again, so the rule "no inter-frames
 * without a reference" was enforced exactly once — at decoder start — and never at the
 * moment it was actually needed. The component decodes a difference against a frame it does
 * not have, returns a picture, and the error propagates through every inter-frame after it:
 * a tile of macroblock rubble that reads healthy at every counter (RTP arriving, jitter
 * buffer draining, decode fps non-zero, frames reaching the renderer) and does not recover.
 * Measured in a three-party mesh on 2026-09-30 — one tile persistently mosaic for minutes
 * while its neighbour on the same handset stayed clean.
 *
 * ## What this test can and cannot prove
 *
 * It cannot run the decoder. `and_vid_mediacodec.cpp` is NDK code that talks to
 * `AMediaCodec`, there is no host harness for it in this repository, and standing one up
 * would mean refactoring vendored source for the sake of the test.
 *
 * What it can do is hold the invariant the defect broke, in the form that would have caught
 * it: **trust is granted in exactly one place and taken away everywhere a picture is
 * refused.** The original bug was not a wrong line, it was a missing one — Phase 3 added
 * four refusal paths and none of them cleared the flag — and a missing line is precisely
 * what a structural assertion catches and a behavioural test of the paths that *do* exist
 * does not.
 *
 * So: every counter that records a refused picture must sit next to a call that drops
 * reference trust, and the flag must be assigned nowhere but the three places that own it.
 * A fifth refusal path added without clearing trust fails here.
 */
class Vp8ReferenceTrustTest {

    private val source: List<String> by lazy {
        val file = File(ArchitectureRules.projectRoot, SOURCE)
        assertTrue(file.isFile, "$SOURCE is missing; has the vendored tree been fetched?")
        file.readLines()
    }

    /**
     * The flag is written in three places and no others.
     *
     * `open_codec`'s reset (a new component holds nothing), the loss helper, and the regain
     * helper. Any fourth assignment is somebody deciding on their own terms that the decoder
     * has a reference, which is the whole of the defect above.
     */
    @Test
    fun `reference trust is assigned only where it is owned`() {
        val assignments = source.withIndex()
            .filter { (_, line) -> ASSIGNMENT.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertEquals(
            3,
            assignments.size,
            "dec_seen_keyframe must be assigned exactly three times — the decoder-start " +
                "reset, vp8_reference_lost() and vp8_reference_regained(). Found:\n" +
                assignments.joinToString("\n"),
        )
    }

    /**
     * Every refused picture drops reference trust.
     *
     * Matched on the drop counters rather than on `return`, because the counters are what
     * each refusal is already obliged to record and they are therefore the complete list:
     * a path that refuses a picture without incrementing one of them is invisible in the
     * logs too, which is a separate defect this repository would not tolerate either.
     *
     * [WINDOW] lines of slack, because the counter is followed by a rate-limited `PJ_LOG`
     * spanning several lines before the refusal itself.
     */
    @Test
    fun `every refused picture drops reference trust`() {
        val orphans = source.withIndex()
            .filter { (_, line) -> DROP_COUNTER.containsMatchIn(line) }
            .filterNot { (index, _) ->
                source.subList(index, minOf(index + WINDOW, source.size))
                    .any { LOSS_CALL.containsMatchIn(it) }
            }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertTrue(
            orphans.isEmpty(),
            "A picture refused without clearing the decoder's reference leaves the next " +
                "inter-frame to be decoded against a frame the component does not hold, " +
                "which is persistent mosaic corruption. Each of these increments a drop " +
                "counter with no vp8_reference_lost() within $WINDOW lines:\n" +
                orphans.joinToString("\n"),
        )
    }

    /**
     * Trust is regained only after the completeness checks, never before them.
     *
     * A keyframe that itself lost packets must leave the stream in recovery. The ordering
     * that guarantees it is that the keyframe test is gated on `sized_ok` — the verdict of
     * the head, hole and tail checks — so this asserts the gate is still there rather than
     * that the checks exist, which rule 12's hash already covers.
     */
    @Test
    fun `a keyframe restores trust only once it has passed the completeness checks`() {
        val gate = source.indexOfFirst { GATE.containsMatchIn(it) }
        assertTrue(
            gate >= 0,
            "The keyframe gate must stay conditional on sized_ok. Without it an incomplete " +
                "keyframe would end a recovery it cannot actually end, and the stream would " +
                "leave recovery still holding no reference.",
        )

        // The declaration is skipped, not the call: `vp8_reference_regained` is defined
        // near the top of the file and so precedes the gate by construction. Only a call
        // reached WITHOUT passing the gate would be the defect.
        val callsBeforeGate = source.take(gate)
            .withIndex()
            .filter { (_, line) -> REGAIN_CALL.containsMatchIn(line) && !DECLARATION.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertTrue(
            callsBeforeGate.isEmpty(),
            "vp8_reference_regained() must be reached through the sized_ok gate at line " +
                "${gate + 1}, so that only a picture which passed every completeness check " +
                "can end a recovery. Called before it at:\n" + callsBeforeGate.joinToString("\n"),
        )

        val callsAfterGate = source.drop(gate)
            .count { REGAIN_CALL.containsMatchIn(it) && !DECLARATION.containsMatchIn(it) }

        assertEquals(
            1,
            callsAfterGate,
            "Exactly one call, inside the gate. More than one means a second way to declare " +
                "the reference trustworthy; none means the gate no longer restores it.",
        )
    }

    private companion object {
        const val SOURCE = "third_party/pjproject/pjmedia/src/pjmedia-codec/and_vid_mediacodec.cpp"

        /** Lines the counter and its log may occupy before the refusal clears trust. */
        const val WINDOW = 22

        val ASSIGNMENT = Regex("""dec_seen_keyframe\s*=""")

        /**
         * The refusal counters.
         *
         * `vp8_frames_drop_no_keyframe` is deliberately absent: it counts inter-frames
         * withheld *because* trust is already gone, so requiring it to drop trust again
         * would be requiring a no-op.
         */
        val DROP_COUNTER =
            Regex("""\+\+\w+->(vp8_frames_drop_missing_head|vp8_frames_drop_incomplete)\b""")

        val LOSS_CALL = Regex("""vp8_reference_lost\s*\(""")
        val REGAIN_CALL = Regex("""vp8_reference_regained\s*\(""")
        val GATE = Regex("""if\s*\(sized_ok\s*&&\s*!\w+->dec_seen_keyframe\)""")

        /** A function's own signature, which is not a call to it. */
        val DECLARATION = Regex("""^\s*static\s""")
    }
}
