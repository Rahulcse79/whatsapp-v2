package com.whatsappv2.data.sip.call

/**
 * Telling "add video" apart from "put the video back" (Task 54).
 *
 * ## Why SDP alone cannot answer this
 *
 * Both arrive as a re-INVITE carrying `m=video` on a call whose video is not running at
 * that instant, and the offers are byte-for-byte the same kind of thing. What separates
 * them is history, which SDP does not carry:
 *
 * - **Hold deactivates video.** Resuming a held video call re-offers it. The user already
 *   consented to that camera; asking again is asking twice.
 * - **A bridge re-syncs its legs.** When one party of a conference changes media, the
 *   bridge re-INVITEs the others with the full offer.
 * - **A session timer refreshes.** The refresh carries the whole offer, video included.
 *
 * Only the first case — video this call has *never* carried — is a question for the user.
 *
 * ## What it cost to get this wrong
 *
 * Until 2026-09-15 the rule was the first two arguments alone, and a three-way conference
 * could not survive being merged: FreeSWITCH re-INVITEd the second leg while the first was
 * answering a video offer, that leg raised a prompt for video it already had, and the
 * bridge gave up thirteen seconds later with
 * `BYE … Reason: SIP;cause=488;text="Incomplete offer/answer"`, taking every leg with it.
 *
 * A pure function, and separate from the stack, because that is what lets the rule be
 * enumerated in a JVM test. The decision used to live inside a pjsua2 callback where
 * nothing could reach it, which is why three wrong answers shipped one after another.
 */
internal object VideoOfferClassifier {

    /**
     * True when [remoteVideoCount] video streams offered by the far end are something the
     * user should be *asked* about.
     *
     * @param remoteVideoCount `CallInfo.remVideoCount` — how many video streams the peer
     *   offered. Zero is an audio-only offer and never a question.
     * @param videoActive whether a video stream is running on this call right now. False
     *   while held, and false for a genuine escalation — which is exactly why it cannot
     *   decide this on its own.
     * @param everHadVideo whether this call has carried video at any point in its life.
     *   The deciding input: video the user has already accepted once is not re-asked.
     */
    fun isEscalation(remoteVideoCount: Int, videoActive: Boolean, everHadVideo: Boolean): Boolean =
        remoteVideoCount > 0 && !videoActive && !everHadVideo
}
