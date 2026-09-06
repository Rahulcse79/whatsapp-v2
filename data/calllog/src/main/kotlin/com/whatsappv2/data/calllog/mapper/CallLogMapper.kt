package com.whatsappv2.data.calllog.mapper

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.calllog.db.CallLogEntity
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri

/**
 * Between the stored row and the domain entry.
 *
 * ## Reading is lenient, writing is not
 *
 * A row is written from a domain value that was already valid, so [toEntity] cannot fail.
 * Reading can: a row written by an older version, or edited by hand, may hold an address
 * that no longer parses or a reason this build has never heard of. [toDomain] returns null
 * for those rather than throwing, so one unreadable row costs the user that row and not
 * the whole history screen.
 */
internal fun CallLogEntry.toEntity(): CallLogEntity = CallLogEntity(
    id = id.value,
    accountId = accountId.value,
    remoteUri = remote.render(),
    remoteDisplayName = remoteDisplayName,
    contactName = contactName,
    direction = direction.name,
    startedAtEpochMillis = startedAtEpochMillis,
    answeredAtEpochMillis = answeredAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    reason = reason.name,
    hasAudio = media.hasAudio,
    hasVideo = media.hasVideo,
)

/** The stored row as a domain entry, or null if it cannot be read as one. */
internal fun CallLogEntity.toDomain(): CallLogEntry? {
    val remote = SipUri.parse(remoteUri).getOrNull() ?: return null
    val direction = enumOrNull<CallDirection>(direction) ?: return null
    val reason = enumOrNull<HangupReason>(reason) ?: return null
    // A row claiming neither audio nor video is not a call that happened. `of` says so by
    // returning null, and this is the one place that answer has to be respected.
    val media = MediaProfile.of(audio = hasAudio, video = hasVideo) ?: return null

    return CallLogEntry(
        id = CallLogId(id),
        accountId = AccountId(accountId),
        remote = remote,
        remoteDisplayName = remoteDisplayName,
        contactName = contactName,
        direction = direction,
        startedAtEpochMillis = startedAtEpochMillis,
        answeredAtEpochMillis = answeredAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        reason = reason,
        media = media,
    )
}

/**
 * [enumValueOf] without the exception.
 *
 * A stored name that no longer exists is data, not a programming error: it is what a
 * downgrade or a hand-edited database looks like, and it must not take a screen down.
 */
private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
