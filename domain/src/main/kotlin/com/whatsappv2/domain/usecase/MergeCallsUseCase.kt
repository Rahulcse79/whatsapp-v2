package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.map
import com.whatsappv2.domain.engine.ConferenceRoom
import com.whatsappv2.domain.engine.MergeTopology
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One button, two conferences (ADR-003 and ADR-009).
 *
 * Merge means "put these calls together", and how that is done depends on what is in them:
 * audio is mixed on this device, video goes to the bridge. [MergeTopology] decides which,
 * and this runs the decision — so the ViewModel asks for a merge rather than choosing a
 * topology, and the choice itself stays a pure function with a JVM test.
 *
 * ## Why the ViewModel does not do this itself
 *
 * Because resolving the room needs the account, and the account needs a repository the
 * call screen has no other reason to hold. Putting it here keeps `CallViewModel` talking
 * only to things about calls, and puts the one rule that spans both conference mechanisms
 * in one readable place.
 */
class MergeCallsUseCase @Inject constructor(
    private val calls: SipCallController,
    private val conferences: SipConferenceController,
    private val accounts: SipAccountRepository,
    private val room: ConferenceRoom,
) {

    /**
     * Merges every established call on this device.
     *
     * Everything, not a chosen pair: the phone has one microphone and one camera, so
     * "merge" can only ever mean all of them. Ringing calls are left out and join when
     * they are answered.
     */
    suspend operator fun invoke(): Outcome<MergeResult, SipError> {
        val snapshots = calls.activeCalls.value

        // Resolved before the decision, not after, so "a bridge is configured" means a
        // room this device can actually address rather than merely a non-blank string.
        // An account whose domain will not make a URI is not a bridge.
        //
        // The account of a call being merged comes first, and the default account is the
        // fallback: the room has to be on the server the calls are already on, and on a
        // handset with two accounts the default may not be that one.
        val account = snapshots.firstOrNull { it.state.isEstablished }
            ?.let { accounts.findById(it.accountId) }
            ?: accounts.observeDefaultAccount().first()
        val roomUri = account?.let { room.uriOn(it.domain) }

        return when (val topology = MergeTopology.of(snapshots, bridgeConfigured = roomUri != null)) {
            is MergeTopology.Unavailable -> failure(topology.reason.toSipError())

            is MergeTopology.LocalMix ->
                conferences.mixCalls(topology.callIds).map { MergeResult.Mixed(it) }

            is MergeTopology.Bridge ->
                // `roomUri` is non-null here by construction: `Bridge` is only ever
                // returned when `bridgeConfigured` was true, and that *is* this being
                // non-null. The elvis is a compiler obligation, not a real branch.
                conferences
                    .mergeIntoConference(topology.callIds, roomUri ?: return failure(NO_ROOM))
                    .map { MergeResult.Bridged(it) }
        }
    }

    private fun MergeTopology.Unavailable.Reason.toSipError(): SipError = when (this) {
        MergeTopology.Unavailable.Reason.NOT_ENOUGH_CALLS ->
            SipError.InvalidState("a conference needs at least two established calls")

        MergeTopology.Unavailable.Reason.TOO_MANY_CALLS ->
            SipError.InvalidState(
                "this device conferences at most ${SipConferenceController.MAX_LOCAL_CONFERENCE} calls",
            )

        MergeTopology.Unavailable.Reason.NO_BRIDGE_CONFIGURED -> NO_ROOM
    }

    private companion object {
        /**
         * Named once because two paths report it: the decision that no room is configured,
         * and the compiler's insistence that the resolved room might be null.
         *
         * The sentence says what is missing rather than what failed. A user told "merge
         * failed" on a video call would try again; one told the conference server is not
         * set up knows it is not their doing.
         */
        val NO_ROOM = SipError.InvalidState(
            "a video conference needs a conference room, and none is configured",
        )
    }
}

/** What a merge produced, so the screen can follow the right call afterwards. */
sealed interface MergeResult {

    /** Mixed on this device (ADR-009). The members keep their own call ids. */
    data class Mixed(val callIds: Set<CallId>) : MergeResult

    /**
     * Moved into the bridge (ADR-003), which replaced every leg with one call to the room.
     *
     * [callId] is this device's leg into the conference, and it is the call the screen
     * must follow: the legs the user merged are being transferred away and will end.
     */
    data class Bridged(val callId: CallId) : MergeResult
}
