package com.whatsappv2.feature.calls

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.model.DtmfDigit

/**
 * What the call screen can do, gathered into one value.
 *
 * Seven callbacks and every one of them is the same kind of thing: something the user did.
 * Grouping them keeps the screen's signature readable and means the next control — a
 * transfer button, a video toggle — adds a field here rather than another argument threaded
 * through four call sites. `:feature:dialer` groups its own for the same reason.
 */
@Stable
data class CallActions(
    val onAnswer: (Boolean) -> Unit = {},
    val onReject: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onToggleMute: (Boolean) -> Unit = {},
    val onToggleSpeaker: (Boolean) -> Unit = {},
    val onToggleHold: (Boolean) -> Unit = {},
    val onDtmf: (DtmfDigit) -> Unit = {},
)
