package com.whatsappv2.feature.group

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.model.CallId

/**
 * What the group-call page is showing (Task 78).
 *
 * ## The group is local, and the page says so
 *
 * ADR-003 chose a dial-in MCU: the bridge owns the room, and joining it is calling its
 * URI. Nothing in this codebase — and nothing specified for it — can create a room on a
 * server or invite anyone to one. So a "group" here is a list somebody assembled on this
 * device plus the address of a bridge to dial, and [members] never leaves the phone.
 *
 * That is why this is a dummy page rather than a half-built feature. Server-side group
 * creation is a server contract to agree first; presenting one that does not exist would
 * be worse than presenting none.
 */
data class GroupCallUiState(
    /** What the user is calling this group. Local, and only used as a screen title. */
    val name: String = "",

    /** The bridge to dial — `3000` or `sip:3000@example.com`, resolved like any extension. */
    val conferenceAddress: String = "",

    /** Who the user picked. Shown so they can see the group; not sent anywhere. */
    val members: List<SipContact> = emptyList(),

    /** What has been typed into the member search. */
    val query: String = "",

    /** Contacts matching [query], bounded — this is a picker, not an address-book dump. */
    val matches: List<SipContact> = emptyList(),

    /** True while a join is in flight, so the buttons cannot be pressed twice. */
    val isJoining: Boolean = false,
) {
    /**
     * Whether the call buttons do anything.
     *
     * The address is the only thing actually required: a conference with no members picked
     * is a perfectly ordinary dial-in, and refusing it would be this page inventing a rule
     * the bridge does not have.
     */
    val canJoin: Boolean get() = conferenceAddress.isNotBlank() && !isJoining
}

/** What the group page can do, gathered into one value, as the other features do. */
@Stable
data class GroupCallActions(
    val onNameChanged: (String) -> Unit = {},
    val onAddressChanged: (String) -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onAddMember: (SipContact) -> Unit = {},
    val onRemoveMember: (SipContact) -> Unit = {},
    val onStartAudioCall: () -> Unit = {},
    val onStartVideoCall: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** A one-shot thing the screen must react to, as opposed to state it renders. */
sealed interface GroupCallEvent {

    /** The join is on its way; the caller opens the call screen for it. */
    data class CallPlaced(val callId: CallId) : GroupCallEvent

    /** The join was refused, with the sentence Task 44 gives that failure. */
    data class Refused(val message: String) : GroupCallEvent

    /** Joined, but not as asked — a video join with no usable camera becomes audio. */
    data class Notice(val message: String) : GroupCallEvent
}
