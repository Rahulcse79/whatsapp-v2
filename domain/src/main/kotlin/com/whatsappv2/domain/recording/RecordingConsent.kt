package com.whatsappv2.domain.recording

import com.whatsappv2.domain.model.CallId

/**
 * Whether this call may be recorded, and who said so (Task 58, §2.6, §7).
 *
 * ## Not a boolean, and not a setting
 *
 * §2.6 forbids shipping a silent recorder. The obvious implementation — a preference
 * called "record calls" — is exactly the thing it forbids: switched on once, months ago,
 * it records every call afterwards with nobody in the room aware of it, including the
 * person on the other end who never agreed to anything.
 *
 * So consent is **per call** and starts at [None] on every one of them. There is no state
 * this type can be in that makes the *next* call recordable, which is what makes "off by
 * default" a property of the type rather than of a default value somebody can change.
 *
 * ## Two-party consent is a legal question, not a technical one
 *
 * Many jurisdictions require every party to agree, not just the one holding the phone —
 * see `docs/security.md`. This app cannot obtain the far end's consent and does not
 * pretend to: [GrantedByLocalUser] says precisely who agreed, so nothing downstream can
 * read it as more than it is.
 */
sealed interface RecordingConsent {

    /** Nobody has agreed. The state every call begins in, and cannot be configured away. */
    data object None : RecordingConsent

    /**
     * The local user agreed, on this call, at [grantedAtEpochMillis].
     *
     * Named for who gave it. A recording made under this consent has the far end's
     * agreement only if the user asked them — which is what the in-call indicator and the
     * announcement in `docs/security.md` exist to make possible.
     */
    data class GrantedByLocalUser(
        val callId: CallId,
        val grantedAtEpochMillis: Long,
    ) : RecordingConsent
}
