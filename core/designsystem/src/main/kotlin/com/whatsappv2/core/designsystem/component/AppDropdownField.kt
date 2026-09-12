package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * A setting chosen from a list too long to be chips.
 *
 * ## Why this exists beside the chip rows
 *
 * The settings screen picks between two or three behaviours with `FilterChip`s, and that
 * is right when the options are few and the labels are short — every choice is visible and
 * costs one tap. It stops being right at about five: the chips wrap, the row stops reading
 * as a row, and the selected one is somewhere in a paragraph of pills. A dropdown trades
 * one extra tap for a selection that is always legible in one line, which is the trade a
 * nine-item list wants.
 *
 * ## The field is read-only, not disabled
 *
 * `readOnly` keeps it focusable and in the accessibility tree, where a screen reader
 * announces the label and then the value — "Keep call history for, 20 days" — with no
 * extra description needed. `enabled = false` would grey the current selection out, which
 * is the one thing on the row the user came to read. Typing is not offered because there
 * is nothing to type: the list is the whole of the input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AppDropdownField(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** A tag per option, for the lists a test picks from; null leaves them untagged. */
    optionTag: ((T) -> String)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        open = false
                        // Re-choosing the current value is a dismissal, not a change, and
                        // must not cost the caller a settings write.
                        if (option != selected) onSelect(option)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = optionTag?.let { Modifier.testTag(it(option)) } ?: Modifier,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AppDropdownFieldPreview() = PreviewSurface {
    AppDropdownField(
        label = "Keep call history for",
        options = PREVIEW_DAYS,
        selected = PREVIEW_DAYS[2],
        labelOf = { "$it days" },
        onSelect = {},
    )
}

private val PREVIEW_DAYS = listOf(7, 14, 20, 30)
