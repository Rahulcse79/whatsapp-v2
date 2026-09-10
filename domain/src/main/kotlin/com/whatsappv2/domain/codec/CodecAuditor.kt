package com.whatsappv2.domain.codec

/**
 * Computes a [CodecAudit] from the declared feature set and what the library registered.
 *
 * ## Why this is pure, in `:domain`, with no PJSIP anywhere near it
 *
 * The interesting part of the audit is a **set difference and a reason per element**, and
 * that is a rule. Principle 3: *if a rule can only be verified by placing a real call, it is
 * in the wrong layer.* The adapter's job is to read `codecEnum2()` and hand over strings;
 * every decision about what an absence *means* happens here, on the JVM, under test, with no
 * device, no server and no network.
 *
 * ## Complexity
 *
 * O(n + m) over a declared set and a registry that are each ≤ ~30 entries, **once per
 * endpoint start**. Never in the call path. `docs/data-structures.md` §4.
 */
class CodecAuditor(
    /**
     * Codecs the build was configured to contain, from `pjsip/config/pj/config_site.h` (N-8).
     *
     * Injected rather than read from a constant so the audit can be tested against a
     * declared set that is not this build's — which is the only way to prove
     * [AbsenceReason.RegistrationFailed] is reachable at all.
     */
    private val declared: Set<DeclaredCodec>,

    /**
     * Codecs known to have no peer on the deployed server.
     *
     * **Configuration, not a constant, and deliberately so.** "No peer accepts it" is a fact
     * about a deployment, not about this build: installing `mod_opus` on the server changes
     * the answer with no change here. Baking it in would make the app lie the moment somebody
     * fixed the server.
     *
     * Empty by default: absent evidence must read as *not known to be stranded*, never as a
     * claim that a codec works.
     */
    private val knownUnnegotiable: Set<String> = emptySet(),

    /**
     * Where [knownUnnegotiable] came from, in a phrase a log line can carry.
     *
     * Travels with the claim so a reader can weigh it. "Recorded from `show codec` on the
     * reference server, 2026-09-09" and "observed on this connection" deserve very different
     * confidence, and a reason that cannot say which is a reason nobody can check.
     */
    private val knownUnnegotiableSource: String = "no evidence recorded",
) {

    /**
     * @param registeredAudio `(codecId, priority)` from `Endpoint.codecEnum2()`.
     * @param registeredVideo the same from `videoCodecEnum2()`.
     * @param compiledIn the codecs whose `config_site.h` flag is `1`. What separates
     *   *not compiled* — a decision — from *registration failed* — a build defect. Without
     *   it every absence collapses into one indistinguishable case, which is the state the
     *   project is in today.
     */
    /**
     * @param modelFilesUnusable per codec name, why its model files cannot be used — for a
     *   codec that registers and then fails when a stream opens, which is worse than one
     *   that never registered. Lyra is the only such codec today. Checked before the peer,
     *   because a codec whose weights are missing has no call to be stranded on.
     */
    fun audit(
        registeredAudio: List<Pair<String, Int>>,
        registeredVideo: List<Pair<String, Int>>,
        compiledIn: Set<String>,
        modelFilesUnusable: Map<String, String> = emptyMap(),
    ): CodecAudit {
        val audio = registeredAudio.map { (id, priority) -> RegisteredCodec.parse(id, priority) }
        val video = registeredVideo.map { (id, priority) -> RegisteredCodec.parse(id, priority) }

        val registeredNames = (audio + video).map { it.name }.toSet()
        val compiled = compiledIn.map { it.lowercase() }.toSet()
        val unnegotiable = knownUnnegotiable.map { it.lowercase() }.toSet()
        val unusableModels = modelFilesUnusable.mapKeys { it.key.lowercase() }

        val absent = declared.mapNotNull { codec ->
            val reason = when {
                // Registered, and the weights it needs at stream time are not there. The
                // one absence that advertises itself in every offer.
                codec.name in registeredNames && codec.name in unusableModels ->
                    AbsenceReason.ModelFilesUnusable(unusableModels.getValue(codec.name))

                // Registered and known to have no peer. Checked before the build cases,
                // because a codec in this state is present in the registry — every later
                // branch would miss it, and it is the state Opus is in today.
                codec.name in registeredNames && codec.name in unnegotiable ->
                    AbsenceReason.ExpectedUnsupportedByServer(knownUnnegotiableSource)

                codec.name in registeredNames -> null

                // Declared, not registered, and the build never contained it. A decision.
                codec.name !in compiled -> AbsenceReason.NotCompiled

                // Declared, compiled in, and still not registered. A build defect, and the
                // one case a build log cannot show.
                else -> AbsenceReason.RegistrationFailed
            }
            reason?.let { codec to it }
        }.toMap()

        // The registry goes back UNFILTERED, and that is the fix rather than an oversight.
        //
        // A stranded codec is registered — that is what makes it stranded rather than
        // missing — so removing it from these lists produced a "registered codecs" log line
        // that was a subset of the registry while claiming to be the registry. On 2026-09-10
        // that line was read as proof this build contains no Opus and no G.722, and a real
        // INVITE off the same handset carried both. One filtered list, one whole wrong
        // diagnosis. Strandedness travels in [CodecAudit.absent], where it can be read as
        // the expectation it is.
        return CodecAudit(
            registeredAudio = audio,
            registeredVideo = video,
            absent = absent,
        )
    }
}
