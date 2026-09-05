package com.whatsappv2.data.sip.network.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.network.NetworkMonitor
import com.whatsappv2.domain.registration.NetworkStatus
import com.whatsappv2.domain.registration.NetworkTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform's view of the network, reduced to [NetworkStatus].
 *
 * **The only class in the project that touches `ConnectivityManager`.**
 *
 * It sits in its own package for the same reason `RealLinphoneCoreGateway` does: it needs
 * a real device to mean anything, so keeping it beside the testable recovery logic would
 * drag that package's coverage gate down until the gate measured nothing. What it
 * produces — an id and a transport — is asserted against on the JVM through
 * [NetworkMonitor]; this class is verified on-device from Task 33.
 *
 * ## Why the model is this small
 *
 * The platform reports far more than this: validation, captive portals, metering,
 * bandwidth. All of it is dropped, and the dropping is what makes the stream quiet —
 * `onCapabilitiesChanged` fires whenever any of those flip, and reducing to id and
 * transport means a validation change on a link that is otherwise the same produces no
 * emission at all.
 *
 * `NET_CAPABILITY_VALIDATED` is deliberately not consulted; see [NetworkStatus].
 */
@Singleton
internal class ConnectivityNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : NetworkMonitor {

    override val status: Flow<NetworkStatus> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)

        if (manager == null) {
            // Not a configuration any real device has, but a null system service must not
            // take the process down. Reporting "no network" is the honest degradation:
            // nothing will be retried, rather than retried blindly.
            logger.error(TAG, "ConnectivityManager unavailable; network recovery is inert")
            send(NetworkStatus.Unavailable)
            awaitClose()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {

            // Capabilities, not onAvailable: this always follows it and is the first
            // point at which the transport is known. Emitting from both would report the
            // same network twice, once without knowing what kind it is.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(NetworkStatus.Available(network.networkHandle, capabilities.toTransport()))
            }

            // The default network went away. A replacement may arrive immediately after,
            // which reads here as unavailable-then-available - a flap the recovery
            // coordinator's debounce is there to absorb.
            override fun onLost(network: Network) {
                trySend(NetworkStatus.Unavailable)
            }

            override fun onUnavailable() {
                trySend(NetworkStatus.Unavailable)
            }
        }

        // Seeded before registering, so a collector that starts while the device is
        // already connected is not blind until the next change - which, on a stable
        // network, may be never.
        send(manager.currentStatus())

        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        // The platform repeats itself freely; a repeat is not a change.
        .distinctUntilChanged()

    private fun ConnectivityManager.currentStatus(): NetworkStatus {
        val active = activeNetwork ?: return NetworkStatus.Unavailable
        val capabilities = getNetworkCapabilities(active) ?: return NetworkStatus.Unavailable
        return NetworkStatus.Available(active.networkHandle, capabilities.toTransport())
    }

    /**
     * VPN is checked first on purpose.
     *
     * A VPN over Wi-Fi carries both transports, and the VPN is the one that decides the
     * source address every SIP binding is made from — which is the only thing this
     * distinction is used for.
     */
    private fun NetworkCapabilities.toTransport(): NetworkTransport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
