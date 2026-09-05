package com.whatsappv2.data.sip.network

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.registration.NetworkStatus
import com.whatsappv2.domain.registration.NetworkTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The network, as a test drives it.
 *
 * A [MutableStateFlow] rather than a channel, mirroring the real monitor: a collector that
 * starts late still learns where the device is, which after a process start is the whole
 * point.
 */
internal class FakeNetworkMonitor(
    initial: NetworkStatus = NetworkStatus.Unavailable,
) : NetworkMonitor {

    private val state = MutableStateFlow(initial)
    override val status: Flow<NetworkStatus> = state

    fun onWifi(networkId: Long = WIFI_ID) {
        state.value = NetworkStatus.Available(networkId, NetworkTransport.WIFI)
    }

    fun onCellular(networkId: Long = CELLULAR_ID) {
        state.value = NetworkStatus.Available(networkId, NetworkTransport.CELLULAR)
    }

    /** Airplane mode, or out of coverage. */
    fun lost() {
        state.value = NetworkStatus.Unavailable
    }

    private companion object {
        const val WIFI_ID = 1L
        const val CELLULAR_ID = 2L
    }
}

/**
 * A [Logger] that keeps what it was told.
 *
 * Task 30's last done-when asks for log evidence of the backoff sequence. Capturing it
 * here makes it an assertion rather than something a person reads off `logcat` once and
 * pastes into a document.
 */
internal class RecordingLogger : Logger {

    val lines: MutableList<String> = mutableListOf()

    override fun verbose(tag: String, message: String) = record(message)
    override fun debug(tag: String, message: String) = record(message)
    override fun info(tag: String, message: String) = record(message)
    override fun warn(tag: String, message: String, throwable: Throwable?) = record(message)
    override fun error(tag: String, message: String, throwable: Throwable?) = record(message)

    /** Only the lines mentioning [needle], which is how one behaviour is read out. */
    fun matching(needle: String): List<String> = lines.filter { needle in it }

    private fun record(message: String) {
        lines += message
    }
}
