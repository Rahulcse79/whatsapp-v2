package com.whatsappv2.domain.voice

import kotlinx.coroutines.flow.Flow

/**
 * The one active voice profile, and the only way anything reaches or replaces it
 * (ADR-013).
 *
 * A Flow rather than a getter because three things watch it at once — the settings
 * screen, the gate that uses it, and whatever is enrolling — and a profile deleted on one
 * of them has to stop being used by the others immediately rather than at the next call.
 *
 * ## Nothing here crosses the network, by construction
 *
 * There is no upload, no sync and no backup. The enrolment audio never reaches this
 * interface at all: the caller turns it into an embedding and discards it, so the most
 * this repository has ever held is 192 floats. That is a property of the shape, not a
 * promise in a comment — there is no method here that could send anything anywhere.
 */
interface VoiceProfileRepository {

    /** The active profile, or null when the user has not enrolled one. */
    val profile: Flow<VoiceProfile?>

    /** The active profile right now, for a caller that cannot wait for the Flow. */
    suspend fun current(): VoiceProfile?

    /**
     * Stores [profile], replacing whatever was there.
     *
     * Replace rather than merge: see [VoiceProfile] for why a profile averaged across
     * sessions drifts away from every one of them.
     */
    suspend fun replace(profile: VoiceProfile)

    /** Forgets the profile. The gate stops running; it does not start muting. */
    suspend fun delete()
}
