package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.call.ConferenceMix
import com.whatsappv2.data.sip.call.MixLink
import com.whatsappv2.data.sip.call.MixPlan

/**
 * The conference, as `pjmedia_conf` actually holds it (ADR-009).
 *
 * Its own class rather than more of `RealPjsipCoreGateway` for two reasons. The gateway is
 * at detekt's `LargeClass` bound, which is the cheap reason. The real one is that a
 * conference has *state* — who is in it, and which links are open — and a second piece of
 * state inside an adapter that already runs a call state machine is how adapters become
 * unreadable. This owns both fields and is the only thing that moves them.
 *
 * ## Wanted and linked are different sets, deliberately
 *
 * [members] is who the app asked for; [links] is what the bridge accepted. A participant
 * whose call is still ringing is in the first and not the second, because it has no audio
 * port yet. [remix] re-plans from whatever ports exist right now, so the gap closes by
 * itself the moment their media comes up — nothing has to remember that somebody was
 * waiting.
 *
 * ## A link is only open on the port it was opened on
 *
 * pjsua rebuilds a call's conference port on every re-INVITE that touches its media — a
 * resume does, and so does a codec change: `Removing port 5 … Added port 6` for the same
 * call. Every link the old port held goes with it, silently. [links] used to be a set of
 * call-key pairs, so a link opened on port 5 still counted as open when the member came
 * back on port 6 and was never opened again. That was ADR-009's "two of eight legs RX
 * 0pkt while all eight showed TX": two members who had been held and resumed, each
 * hearing everyone and heard by nobody. [portIds] remembers which port each member was
 * linked on, and a member who comes back on a different one has every link forgotten
 * before the plan is made — so the plan opens them again.
 *
 * ## PJSIP thread only
 *
 * Every entry point is called from inside the gateway's `onPjsip` block or from a pjsua2
 * callback, which is already on that thread. Neither field is synchronised and neither
 * needs to be — the same rule the gateway's own `linkDownSeen` follows.
 */
internal class ConferenceBridge(
    private val logger: Logger,
    /** A call's audio port, or null while it has none. */
    private val mediaOf: (String) -> ConferencePort?,
) {

    private var members: Set<String> = emptySet()
    private var links: Set<MixLink> = emptySet()

    /** The bridge port each linked member was linked on — see the class comment. */
    private val portIds = mutableMapOf<String, Int>()

    /** True while a conference exists, so callers can skip the work entirely. */
    val isActive: Boolean get() = members.isNotEmpty()

    /**
     * Mixes exactly [callKeys], and returns the members that actually have a port.
     *
     * Idempotent. Fewer than two live members tears every link down, which is how a
     * conference ends — there is no separate teardown to forget.
     */
    fun set(callKeys: Set<String>): Set<String> {
        members = callKeys
        return remix()
    }

    /**
     * Re-states the current membership against the ports that exist now.
     *
     * Called after anything that could have moved a port: a media-state change, a hold, a
     * re-INVITE. Cheap and idempotent, so the caller does not have to work out whether
     * anything actually moved.
     */
    fun remix(): Set<String> {
        if (members.isEmpty()) return emptySet()

        val live = members.filterTo(mutableSetOf()) { mediaOf(it) != null }
        forgetRebuiltPorts(live)
        val plan = ConferenceMix.plan(links, live)
        if (plan.isEmpty) return live

        val applied = apply(plan)
        links = links - plan.disconnect + applied.opened

        if (applied.deferred > 0) {
            logger.info(TAG, "Conference: ${applied.deferred} link(s) deferred until their media is up")
        }
        logger.info(TAG, "Conference: ${live.size} member(s), ${links.size} link(s) open")
        return live
    }

    /**
     * Forgets every link of a member whose bridge port is not the one it was linked on.
     *
     * The old port took those links with it when pjsua removed it; believing they are
     * still open is how a member ends up hearing everyone and heard by nobody.
     */
    private fun forgetRebuiltPorts(live: Set<String>) {
        val rebuilt = live.filter { key ->
            val current = mediaOf(key)?.id ?: return@filter false
            val linkedOn = portIds[key] ?: return@filter false
            current != linkedOn
        }
        if (rebuilt.isEmpty()) return
        links = links.filterNotTo(mutableSetOf()) { it.from in rebuilt || it.to in rebuilt }
        rebuilt.forEach(portIds::remove)
        logger.info(TAG, "Conference: ${rebuilt.size} member(s) came back on a new port; relinking them")
    }

    /**
     * Drops [callKey] from the conference and releases every link it holds.
     *
     * Called before the call's media is freed. A link into a released port is a
     * use-after-free in a native bridge, not a stale entry in a map, so this runs on the
     * way out rather than being tidied up later.
     */
    fun remove(callKey: String) {
        if (!isActive) return
        members = ConferenceMix.without(members, callKey)
        // The departing port may already be unusable, so close its links by plan — apply()
        // guards every call and drops them from the set either way.
        val plan = ConferenceMix.plan(links, members.filterTo(mutableSetOf()) { mediaOf(it) != null })
        apply(plan)
        links = links - plan.disconnect
        portIds -= callKey
        if (members.size < ConferenceMix.MINIMUM_MEMBERS) {
            logger.info(TAG, "Conference ended; ${members.size} member(s) left")
            members = emptySet()
            links = emptySet()
            portIds.clear()
        }
    }

    /** What [apply] managed to do, which is not always what it was asked to do. */
    private data class Applied(val opened: Set<MixLink>, val deferred: Int)

    /**
     * Opens and closes links, reporting what happened rather than what was asked.
     *
     * A link that throws is counted, not propagated: that is the difference between one
     * refused port and a conference that collapses. The other members stay connected and
     * the next [remix] tries the missing link again.
     */
    private fun apply(plan: MixPlan): Applied {
        var deferred = 0
        val opened = buildSet {
            plan.connect.forEach { link ->
                val from = mediaOf(link.from)
                val to = mediaOf(link.to)
                // Two members on one slot is a cached port that has been reused; the
                // bridge would loop it to itself, and the member would hear themselves.
                if (from == null || to == null || from.id == to.id) {
                    deferred++
                    return@forEach
                }
                runCatching { from.transmitTo(to) }
                    .onSuccess {
                        add(link)
                        portIds[link.from] = from.id
                        portIds[link.to] = to.id
                    }
                    .onFailure {
                        deferred++
                        logger.warn(TAG, "Conference link refused: ${it.message}")
                    }
            }
        }

        plan.disconnect.forEach { link ->
            val from = mediaOf(link.from) ?: return@forEach
            val to = mediaOf(link.to) ?: return@forEach
            runCatching { from.stopTransmitTo(to) }
        }
        return Applied(opened, deferred)
    }

    private companion object {
        const val TAG = "PjsipGateway"
    }
}

/**
 * One port on the audio bridge, as much of `AudioMedia` as the conference needs.
 *
 * An interface so [ConferenceBridge] can be exercised on the JVM with ports that record
 * what was asked of them: `AudioMedia` loads the native library the moment the class is
 * touched, which no unit test may do. The gateway supplies the real one.
 */
internal interface ConferencePort {
    /** pjmedia's slot for this port; a new number means a new port, whatever the call. */
    val id: Int

    fun transmitTo(other: ConferencePort)

    fun stopTransmitTo(other: ConferencePort)
}
