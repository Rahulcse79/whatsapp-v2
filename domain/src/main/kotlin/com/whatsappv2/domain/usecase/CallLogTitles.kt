package com.whatsappv2.domain.usecase

import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.model.CallLogEntry
import javax.inject.Inject

/**
 * What to call the person on a history row (Task 49, §5.2).
 *
 * ## Why the address book is asked again, when the row already has a name
 *
 * [CallLogEntry.contactName] is a snapshot: `CallLogRecorder` writes whatever the address
 * book said at the moment the call ended, and never revisits it. That is deliberate and it
 * is kept — a call to somebody since deleted from the address book still shows who it was.
 * What it cannot do is learn. A contact added **after** a call leaves that row reading as a
 * number for the life of the database, which is the reported defect, and no amount of
 * editing the address book fixes a row already written.
 *
 * So the current name wins, the snapshot is the fallback, and the two together mean:
 *
 * | Address book says | Row shows |
 * |---|---|
 * | a name | that name — including for calls that predate the contact |
 * | nothing (deleted, or never there) | the snapshot, if the row has one |
 * | nothing, and no snapshot | the peer's own display name, then the extension |
 *
 * ## Where it is called from, and where it must not be
 *
 * Once per entry as a page is loaded, not once per row as the list scrolls: `:feature:history`
 * applies this inside the paging transform, above `cachedIn`, so a resolved title is computed
 * on the loading dispatcher and then cached with the page. `ContactRepository` keeps its own
 * bounded cache on top of that, so a deployment of six extensions costs six provider reads
 * however long the log is.
 *
 * Calling it from a composable would undo all of that: a recomposition per frame is a read of
 * somebody's address book per frame.
 */
class CallLogTitles @Inject constructor(
    private val contacts: ContactRepository,
) {

    /** The best name available for [entry] right now, asking the address book once. */
    suspend operator fun invoke(entry: CallLogEntry): String =
        entry.titleWith(contacts.resolve(entry.remote)?.displayName)
}

/**
 * The label for this entry, given what the address book says about it *now*.
 *
 * Pure, and separate from [CallLogTitles], so the precedence can be tested without a
 * repository and so the one rule that decides every label in the app lives in one place.
 *
 * The order is the argument. [currentContactName] and [CallLogEntry.contactName] are both
 * the user's own word for the person, so they beat [CallLogEntry.remoteDisplayName], which
 * is whatever the far end chose to call itself — a PBX that puts "Reception" in every `From`
 * header must not overwrite the name in somebody's phone. Last is the address, as
 * `SipUri.label` renders it: the extension, never the whole `sip:user@host` URI, because a
 * call list where every row repeats the same host is a call list with no room for names.
 */
fun CallLogEntry.titleWith(currentContactName: String?): String =
    currentContactName ?: contactName ?: remoteDisplayName ?: remote.label()
