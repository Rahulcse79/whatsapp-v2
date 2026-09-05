package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.DtmfDigit

/**
 * The in-call keypad (Task 43).
 *
 * ## One digit, sent immediately
 *
 * Every key press sends its tone straight away rather than building a string to send at
 * the end. An IVR acts on each tone as it arrives — a menu changes, a prompt times out —
 * so a batch delivered at once is a sequence the caller never typed.
 *
 * ## The tone the caller hears is the stack's
 *
 * liblinphone plays the digit locally as it sends it, which is what its `sendDtmf`
 * contract promises. A second tone generated here would double every keypress, so this
 * screen's own feedback is visual: [dialled] shows what has been sent, which is also the
 * only record of it — the digits are deliberately not logged, because a DTMF sequence is a
 * PIN or a card number as often as it is a menu choice (§7, DoD 12).
 *
 * ## A, B and C are not on a phone, and are still required
 *
 * Some PBX and carrier signalling uses them, and [DtmfDigit] carries all sixteen tones for
 * that reason. They sit behind a disclosure rather than in the grid, because a keypad that
 * does not look like a keypad costs every user something to buy a case almost none of them
 * will meet.
 */
@Composable
internal fun CallKeypad(
    dialled: String,
    onDigit: (DtmfDigit) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lettersShown by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_KEYPAD),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
    ) {
        Text(
            // A space rather than nothing, so the row keeps its height and the keys below
            // do not jump down the screen on the first keypress.
            text = dialled.ifEmpty { " " },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TAG_KEYPAD_SENT),
        )

        KEYPAD_ROWS.forEach { row -> KeypadRow(row, onDigit) }
        if (lettersShown) KeypadRow(LETTER_KEYS, onDigit)

        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.large)) {
            TextButton(
                onClick = { lettersShown = !lettersShown },
                modifier = Modifier.testTag(TAG_KEYPAD_LETTERS),
            ) {
                Text(if (lettersShown) "Hide A-D" else "A-D")
            }
            TextButton(onClick = onHide, modifier = Modifier.testTag(TAG_KEYPAD_HIDE)) {
                Text("Hide keypad")
            }
        }
    }
}

@Composable
private fun KeypadRow(digits: List<DtmfDigit>, onDigit: (DtmfDigit) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge)) {
        digits.forEach { digit ->
            TextButton(
                onClick = { onDigit(digit) },
                modifier = Modifier
                    .size(AppTheme.sizing.callActionButton)
                    .testTag(keypadKeyTag(digit)),
            ) {
                Text(
                    text = digit.symbol.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The keypad as people expect to see it, and not as [DtmfDigit] happens to be declared.
 *
 * Written out rather than derived from the enum: a phone starts at 1 and ends with `*`,
 * `0`, `#`, and an enum ordered `ZERO` first would put a keypad on screen that nobody has
 * ever used.
 */
private val KEYPAD_ROWS = listOf(
    listOf(DtmfDigit.ONE, DtmfDigit.TWO, DtmfDigit.THREE),
    listOf(DtmfDigit.FOUR, DtmfDigit.FIVE, DtmfDigit.SIX),
    listOf(DtmfDigit.SEVEN, DtmfDigit.EIGHT, DtmfDigit.NINE),
    listOf(DtmfDigit.STAR, DtmfDigit.ZERO, DtmfDigit.HASH),
)

private val LETTER_KEYS = listOf(DtmfDigit.A, DtmfDigit.B, DtmfDigit.C, DtmfDigit.D)

internal const val TAG_KEYPAD = "call-keypad"
internal const val TAG_KEYPAD_SENT = "call-keypad-sent"
internal const val TAG_KEYPAD_LETTERS = "call-keypad-letters"
internal const val TAG_KEYPAD_HIDE = "call-keypad-hide"

internal fun keypadKeyTag(digit: DtmfDigit): String = "call-keypad-${digit.symbol}"

@ThemePreviews
@Composable
private fun CallKeypadPreview() = PreviewSurface {
    CallKeypad(
        dialled = "*21",
        onDigit = {},
        onHide = {},
        modifier = Modifier.padding(AppTheme.spacing.large),
    )
}
