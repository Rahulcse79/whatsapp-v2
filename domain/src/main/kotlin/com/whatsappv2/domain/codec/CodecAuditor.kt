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
) {

    /**
     * @param registeredAudio `(codecId, priority)` from `Endpoint.codecEnum2()`.
     * @param registeredVideo the same from `videoCodecEnum2()`.
     * @param compiledIn the codecs whose `config_site.h` flag is `1`. What separates
     *   *not compiled* — a decision — from *registration failed* — a build defect. Without
     *   it every absence collapses into one indistinguishable case, which is the state the
     *   project is in today.
     */
    fun audit(
        registeredAudio: List<Pair<String, Int>>,
        registeredVideo: List<Pair<String, Int>>,
        compiledIn: Set<String>,
    ): CodecAudit {
        val audio = registeredAudio.map { (id, priority) -> RegisteredCodec.parse(id, priority) }
        val video = registeredVideo.map { (id, priority) -> RegisteredCodec.parse(id, priority) }

        val registeredNames = (audio + video).map { it.name }.toSet()
        val compiled = compiledIn.map { it.lowercase() }.toSet()
        val unnegotiable = knownUnnegotiable.map { it.lowercase() }.toSet()

        val absent = declared.mapNotNull { codec ->
            val reason = when {
                // Registered and known to have no peer. Checked FIRST, because a codec in
                // this state is present in the registry — every later branch would miss it,
                // and it is the state Opus is in today.
                codec.name in registeredNames && codec.name in unnegotiable ->
                    AbsenceReason.NoPeerAccepts

                codec.name in registeredNames -> null

                // Declared, not registered, and the build never contained it. A decision.
                codec.name !in compiled -> AbsenceReason.NotCompiled

                // Declared, compiled in, and still not registered. A build defect, and the
                // one case a build log cannot show.
                else -> AbsenceReason.RegistrationFailed
            }
            reason?.let { codec to it }
        }.toMap()

        // A stranded codec IS registered, so it must not also appear in the registered lists
        // handed to CodecAudit — its own invariant would reject that, correctly. It is
        // reported as absent-with-a-reason because that is what the UI has to say about it.
        val strandedNames = absent.filterValues { it == AbsenceReason.NoPeerAccepts }
            .keys.map { it.name }.toSet()

        return CodecAudit(
            registeredAudio = audio.filterNot { it.name in strandedNames },
            registeredVideo = video.filterNot { it.name in strandedNames },
            absent = absent,
        )
    }
}
