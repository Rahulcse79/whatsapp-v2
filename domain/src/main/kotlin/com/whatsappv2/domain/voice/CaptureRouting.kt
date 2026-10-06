package com.whatsappv2.domain.voice

/**
 * Whether the microphone should reach one call, given everything that wants it silent.
 *
 * ## Why this is one function and not two call sites
 *
 * `capture -> leg` is a single conference connection and two features want to break it: the
 * user's mute, and the trained-voice gate. While each wrote that connection on its own,
 * whichever ran last won — so un-muting could defeat a closed gate, and the gate reopening
 * could un-mute somebody who had deliberately muted themselves. Neither is a trade-off
 * anyone chose; both are just the consequence of two owners.
 *
 * Making the answer a function of both inputs means it cannot be written inconsistently:
 * every change recomputes it from the current state of each. Either reason silences the
 * microphone and neither can undo the other.
 *
 * It stays in `:domain` because the rule is a product decision — "mute always wins" is a
 * sentence about the product, not about pjmedia — and because the gateway that applies it
 * cannot run on the JVM.
 */
object CaptureRouting {

    /**
     * True when the microphone should be connected to the call.
     *
     * @param microphoneMuted what the *user* asked for, which is absolute: a muted
     *   microphone stays muted however confident the gate is that this is them.
     * @param gateOpen the trained-voice gate's standing decision, or true when filtering is
     *   off, no profile is enrolled, or the gate failed to start — every one of which must
     *   leave the microphone alone.
     */
    fun shouldTransmit(microphoneMuted: Boolean, gateOpen: Boolean): Boolean =
        !microphoneMuted && gateOpen
}
