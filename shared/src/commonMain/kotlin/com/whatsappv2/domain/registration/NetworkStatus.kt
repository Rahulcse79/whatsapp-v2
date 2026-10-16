package com.whatsappv2.domain.registration

/**
 * The kind of link the device is currently using.
 *
 * Named rather than boolean because the two that matter behave differently: a Wi-Fi to
 * cellular handover changes the source address and invalidates every SIP binding, and a
 * log line saying which way it went is the difference between a reproducible bug report
 * and "it dropped sometimes".
 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,

    /** A VPN, which may sit over either of the above. */
    VPN,

    /** Something the platform reported that none of the above describes. */
    OTHER,
}

/**
 * Whether the device has a network, and which one.
 *
 * A `:domain` type with no Android in it, so the recovery rules can be decided and tested
 * without a device. The `ConnectivityManager` callback that produces it lives in
 * `:data:sip`.
 *
 * ## Why there is no "validated" flag
 *
 * `NET_CAPABILITY_VALIDATED` means the platform reached the public internet. Gating
 * registration on it would break the deployment this app is actually for: a FreeSWITCH
 * server on a private LAN with no internet route is a supported and common configuration,
 * and its clients would sit unregistered on a network that works perfectly. So the app
 * tries, and lets the REGISTER itself be the test of whether the registrar is reachable.
 */
sealed interface NetworkStatus {

    /**
     * The connection currently in use, or `null` when there is none.
     *
     * Declared on the interface so a caller that only wants to compare "same link or a
     * different one" does not have to branch on the shape first — which is most of them,
     * because that comparison is the whole of network recovery.
     */
    val networkId: Long?

    /**
     * No usable network at all — airplane mode, or out of coverage.
     *
     * The important consequence is negative: nothing may retry in this state. A REGISTER
     * with no network cannot succeed, and a client that keeps trying anyway wakes the
     * radio for nothing (§6, DoD 6).
     */
    data object Unavailable : NetworkStatus {
        override val networkId: Long? get() = null
    }

    /**
     * A network is up.
     *
     * @param networkId identifies **this connection**, not this kind of connection. The
     *   platform issues a new one each time a network is established, so leaving Wi-Fi
     *   and coming back to the same access point yields a different id — which is
     *   correct, because the source address, and therefore every SIP binding, is new.
     */
    data class Available(
        override val networkId: Long,
        val transport: NetworkTransport,
    ) : NetworkStatus
}
