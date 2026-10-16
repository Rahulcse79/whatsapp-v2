package com.whatsappv2.domain.engine

/**
 * How a call is being placed, which decides what the platform is told about it.
 *
 * ## Why the platform cannot be told about every leg of a group call
 *
 * Android Telecom allows a self-managed connection service **one outgoing call in
 * progress**: while a connection is dialling, `TelecomManager.placeCall` for a second is
 * refused (`CallsManager.isOutgoingCallPermitted`, measured on a TC15 on 2026-09-19 as
 * "Telecom refused an outgoing call" for the second leg). A conference called back from
 * history used to work around that by dialling its members one after another, each once
 * the one before had answered or given up — which put the third member's phone ringing a
 * minute after the first's. Calling everyone at the same moment therefore means placing
 * INVITEs the platform is not told about.
 *
 * ## What the platform is for, and why one leg is enough
 *
 * Telecom's connection gives a call audio focus and the in-call audio mode, routing
 * (earpiece, speaker, Bluetooth), arbitration with the cellular radio, and the lock
 * screen's buttons. Every one of those is a property of the *device*, not of a leg: a
 * conference this handset mixes has one microphone, one speaker path and one screen,
 * however many SIP dialogs feed it. So one registered leg carries the platform's side of
 * a whole group call, and the others are ordinary SIP calls the engine mixes in — which
 * is also the arrangement Telecom fights least, since it holds every other connection the
 * moment one becomes active (ADR-009).
 *
 * The engine keeps the invariant that **at least one live leg is registered while any leg
 * is live**: a [CONFERENCE_MEMBER] registers itself when nothing else is dialling, and
 * when the registered leg ends before the others, a survivor is registered in its place
 * (`PjsipSipEngine.promoteSurvivor`). See `CallSnapshot.platformManaged`.
 */
enum class CallPlacement {
    /**
     * A call on its own: registered with the platform before its INVITE, and refused
     * outright when the platform refuses it — the rule every call has always followed
     * (Task 34, §3).
     */
    STANDALONE,

    /**
     * One of several legs placed together for a conference this device assembles.
     *
     * Registered with the platform only when no registered call is already dialling —
     * asking then would be refused, and the leg is placed regardless. A refusal for any
     * other reason (a cellular call) is still honoured, as it is for [STANDALONE].
     */
    CONFERENCE_MEMBER,
}
