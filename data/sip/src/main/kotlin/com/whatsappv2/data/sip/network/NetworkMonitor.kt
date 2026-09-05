package com.whatsappv2.data.sip.network

import com.whatsappv2.domain.registration.NetworkStatus
import kotlinx.coroutines.flow.Flow

/**
 * What network the device is on, as a stream.
 *
 * The seam that keeps `ConnectivityManager` out of everything above it — the same trick
 * as [com.whatsappv2.data.sip.registration.LinphoneCoreGateway], and for the same reason:
 * a recovery rule that can only be exercised by toggling airplane mode on a real handset
 * is a rule that gets exercised once, by hand, and never again.
 *
 * Emits the current status on collection so a late collector is not left blind until the
 * next change, which after a process start is the whole point.
 */
internal interface NetworkMonitor {
    val status: Flow<NetworkStatus>
}

/**
 * Tells the SIP transport that the link underneath it changed.
 *
 * Separate from re-registering, and it has to happen first. The stack holds sockets bound
 * to the old source address; issuing a REGISTER without rebinding them sends it from an
 * interface that no longer exists, and the request never leaves the device.
 *
 * A [fun interface] rather than another method on the engine: this is one instruction to
 * one collaborator, and keeping it separate lets the recovery loop be tested with a
 * recording lambda instead of a whole stack.
 */
internal fun interface TransportRebinder {

    /**
     * @param reachable false to tear the transports down, true to bring them back up.
     *   A change is signalled as `false` then `true`; the stack treats that as "the
     *   world moved" and re-creates its sockets.
     */
    fun setNetworkReachable(reachable: Boolean)
}
