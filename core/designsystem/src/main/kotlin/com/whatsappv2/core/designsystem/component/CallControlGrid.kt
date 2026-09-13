package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The in-call controls, laid out on a fixed grid of [CALL_CONTROL_COLUMNS] per row.
 *
 * ## Why a grid and not a row per group
 *
 * Because the two rows of the call screen used `Arrangement.SpaceEvenly` independently,
 * and space-evenly divides by *how many buttons that row happens to hold*. The first row
 * always holds four; the second held four, five or six depending on whether the call could
 * be merged and whether a camera was running. So the columns never lined up — Mute sat
 * over Video on an ordinary call and over nothing in particular on a conference, and the
 * whole block shifted sideways the moment a second call arrived. That is the "unbalanced"
 * part, and no amount of padding fixes it, because the cause is that the two rows were
 * measuring against different denominators.
 *
 * Here every control gets exactly one column of four, whatever else is on screen. A row
 * with fewer than four is padded with empty columns rather than re-centred, so a control
 * keeps its position when its neighbours come and go: Merge appearing must not move
 * Transfer, or the button under the user's thumb changes meaning between two frames of the
 * same call.
 *
 * ## Controls are passed as a list, not as a slot lambda
 *
 * A `content` slot would have to be measured to be chunked, which means a custom layout;
 * a list can be chunked before anything is composed. They are stateless icon buttons, so
 * there is no composition state to key — what each cell draws is decided entirely by the
 * call state handed to it.
 */
@Composable
fun CallControlGrid(
    controls: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    if (controls.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        controls.chunked(CALL_CONTROL_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { control ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        control()
                    }
                }
                // Empty columns rather than a re-centred short row — see the class comment.
                repeat(CALL_CONTROL_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Four.
 *
 * Wide enough that the controls every call has — mute, speaker, hold, keypad — fit on one
 * row, and narrow enough that a 64.dp button plus its label still clears the minimum touch
 * target on the narrowest handset this ships to.
 */
const val CALL_CONTROL_COLUMNS = 4

@ThemePreviews
@Composable
private fun CallControlGridPreview() = PreviewSurface {
    CallControlGrid(
        controls = listOf(
            { CallActionButton(Icons.Filled.Mic, "Mute microphone", {}, label = "Mute") },
            { CallActionButton(Icons.Filled.Videocam, "Turn on my video", {}, label = "Video") },
            { CallActionButton(Icons.Filled.Pause, "Hold call", {}, label = "Hold") },
            { CallActionButton(Icons.Filled.Dialpad, "Show the keypad", {}, label = "Keypad") },
            // A fifth, to show that a short row keeps its column rather than re-centring.
            { CallActionButton(Icons.Filled.Mic, "Record this call", {}, label = "Record") },
        ),
    )
}
