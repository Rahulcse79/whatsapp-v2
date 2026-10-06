package com.whatsappv2.domain.voice

/**
 * Turns speech into a speaker embedding (ADR-013).
 *
 * A port so the SIP layer, which owns the microphone, never has to know what runs the
 * model — and so the 24 MB of weights and the ONNX Runtime that executes them are a
 * dependency of `:data:voice` rather than of `:data:sip`.
 */
interface SpeakerEmbedder {

    /**
     * Whether a model is loaded and usable.
     *
     * The gate asks before it starts, because its contract is to fail **open**: a model
     * that will not load must leave the microphone alone rather than mute it.
     */
    val isAvailable: Boolean

    /**
     * The L2-normalised embedding of [samples], or null when there is no opinion to give.
     *
     * Null rather than an exception, and "no opinion" rather than "not the user": the
     * caller treats it as the user's voice. Reasons: too little audio to make one frame,
     * or a model that is not there.
     *
     * Blocking, and tens of milliseconds. Never call it from an audio callback.
     *
     * @param samples 16 kHz mono 16-bit PCM.
     */
    fun embed(samples: ShortArray): FloatArray?
}
