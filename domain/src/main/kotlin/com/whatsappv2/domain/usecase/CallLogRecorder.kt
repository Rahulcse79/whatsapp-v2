package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.repository.CallLogRepository
import com.whatsappv2.domain.repository.SipAccountRepository
import javax.inject.Inject

/**
 * Turns a finished call into the row the history screen reads (Task 47, §5.2).
 *
 * ## One entry per ending, not per outcome
 *
 * It collects [SipCallController.endedCalls], which emits once per call, so "exactly one
 * entry per call" is a property of the seam rather than of bookkeeping here. Answered,
 * missed, rejected and failed calls all arrive the same way and all get written — a log
 * that dropped the failures would be missing the entries anyone actually goes looking for.
 *
 * ## Why the clock, and not the snapshot
 *
 * [CallSnapshot] carries when the call started and when it connected, but not when it
 * ended: nothing downstream of the state machine needed that until now. Rather than widen
 * the snapshot for one reader, the ending is stamped here, at the moment the engine
 * reports it. The two differ by the time it takes to deliver one emission.
 *
 * ## And the account's domain, for the same reason
 *
 * The snapshot names the account but not its domain, and the row needs the domain to tell,
 * later, whether the far end's address was the server's or its own
 * ([CallLogEntry.redialTarget]). Read here, once, at the ending — the account table is the
 * only thing that knows it, and reading it when the row is redialled would compare against
 * whatever the domain had become by then, which is the case that needs telling apart.
 */
class CallLogRecorder @Inject constructor(
    private val calls: SipCallController,
    private val log: CallLogRepository,
    private val contacts: ContactRepository,
    private val accounts: SipAccountRepository,
    private val clock: Clock,
) {

    /**
     * Records endings until the caller's scope is cancelled.
     *
     * Suspends forever by design — the caller owns the coroutine, because the lifetime
     * of the recording is the lifetime of the process rather than of any screen.
     */
    suspend fun record() {
        calls.endedCalls.collect { snapshot ->
            val entry = snapshot.toLogEntry(clock.nowEpochMillis()) ?: return@collect
            // Resolved once, here, and stored with the row. The alternative — resolving
            // when the list is drawn — would re-read the address book for every visible
            // entry on every scroll, and would rewrite history whenever a contact was
            // renamed or deleted (Task 49).
            val contact = contacts.resolve(entry.remote)
            // Null if the account was deleted while the call was up: the row is still
            // written, and reads as one whose domain was never recorded.
            val domain = accounts.findById(entry.accountId)?.domain
            log.record(entry.copy(contactName = contact?.displayName, accountDomain = domain))
        }
    }
}

/**
 * The finished call as a log row, or null if the call is not finished.
 *
 * The null is not defensive: [SipCallController.endedCalls] promises a terminal snapshot,
 * and a non-terminal one arriving means the engine broke that promise. Writing a row with
 * an invented reason would hide that; skipping it leaves the log honest and the bug
 * visible in the calls that never appear.
 */
fun CallSnapshot.toLogEntry(endedAtEpochMillis: Long): CallLogEntry? {
    val terminal = state as? CallState.Terminated ?: return null

    return CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = accountId,
        remote = remote,
        // Filled in by the recorder, which is the only thing that has an account table to
        // ask. Null here keeps this function pure and testable without one.
        accountDomain = null,
        remoteDisplayName = remoteDisplayName,
        // Likewise, from the address book.
        contactName = null,
        direction = direction,
        startedAtEpochMillis = startedAtEpochMillis,
        answeredAtEpochMillis = connectedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        reason = terminal.reason,
        media = media,
        // Set by `joinConference` for a dial-in room and by `mixCalls` for one this
        // device mixed; the snapshot carries it to the ending either way, which is why
        // nothing here has to know which kind it was.
        isConference = isConference,
        conferenceKey = conferenceKey,
    )
}
