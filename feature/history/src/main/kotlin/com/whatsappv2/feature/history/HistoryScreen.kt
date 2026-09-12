package com.whatsappv2.feature.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.compose.LazyPagingItems
import com.whatsappv2.core.designsystem.component.AppHeaderTabs
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogQuery
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Call history (Task 48, §5.2) — and, since Task 70, the app's home screen.
 *
 * Paged rather than a list: ten thousand entries is an ordinary year of calls for a
 * business handset, and loading them to draw twenty would make opening the screen a
 * visible pause and scrolling it a stutter.
 *
 * ## What it carries besides the list
 *
 * A floating button for the dialler (Task 70), and search over the whole log in the top
 * bar. The dialler is a callback on [HistoryActions] — this module may not navigate to
 * another feature, so it reports the press and `:app` decides where it goes.
 *
 * The list is stateless in the Compose sense — every action goes up through [HistoryActions]
 * — so it can be rendered from literal rows in a test and in a preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    rows: LazyPagingItems<HistoryRow>,
    actions: HistoryActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { HistoryHeader(state = state, actions = actions, zone = zone) },
        floatingActionButton = { HistoryFab(actions = actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Only the filters that are *on*, and only while one is. Slides open rather
            // than appearing, so the list moves once instead of jumping by the row's height.
            AnimatedVisibility(visible = state.query.hasMenuFilters) {
                ActiveFilters(query = state.query, actions = actions, zone = zone)
            }

            if (rows.itemCount == 0) {
                // The empty state says what to do next, and offers it: a first call from
                // an empty log, or a way back to everything from a search that found
                // nothing. A sentence with no button is advice; a button is a way out.
                val narrowed = state.query.activeFilterCount > 0
                EmptyState(
                    title = state.query.emptyTitle,
                    description = state.query.emptyDescription,
                    icon = Icons.Filled.History,
                    actionLabel = when {
                        state.query.isMatchAll -> "Make a call"
                        narrowed -> "Clear filters"
                        else -> null
                    },
                    onAction = when {
                        state.query.isMatchAll -> actions.onOpenDialer
                        narrowed -> actions.onFiltersCleared
                        else -> null
                    },
                    modifier = Modifier.testTag(TAG_EMPTY),
                )
                return@Column
            }

            CallList(rows = rows, actions = actions, zone = zone)
        }
    }

    state.openEntry?.let { row ->
        CallDetail(row, actions, zone)
    }

    if (state.confirmingClearAll) {
        ConfirmDialog(
            title = "Clear call history?",
            message = "Every entry is removed. This cannot be undone.",
            confirmLabel = "Clear all",
            onConfirm = actions.onClearAllConfirmed,
            onDismiss = actions.onClearAllDismissed,
            destructive = true,
            modifier = Modifier.testTag(TAG_CONFIRM_CLEAR),
        )
    }
}

/**
 * The header: title or search field, its actions, and the All / Missed tabs — one green
 * block in light, one near-black block in dark (`AppTopBar`).
 *
 * All and Missed stay where the thumb can reach them because they are what people
 * actually flip between. Everything else that narrows the list — direction, date — lives
 * behind the one filter icon, in [FilterMenu]. Five chips and a funnel used to sit under
 * the tabs together; two of them said "Missed", and none of them said which was on.
 *
 * That leaves three things in the bar: search, filters, and the three dots everything
 * rare lives behind.
 */
@Composable
private fun HistoryHeader(state: HistoryUiState, actions: HistoryActions, zone: ZoneId) {
    AppTopBar(
        title = "Calls",
        titleContent = if (state.searching) {
            { SearchField(text = state.query.text, onTextChanged = actions.onSearchTextChanged) }
        } else {
            null
        },
        navigationIcon = {
            // Only while searching. A back arrow on the app's home screen invites a press
            // that has nowhere to go.
            if (state.searching) {
                IconButton(onClick = { actions.onSearchToggled(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
            }
        },
        actions = {
            if (!state.searching) {
                IconButton(
                    onClick = { actions.onSearchToggled(true) },
                    modifier = Modifier.testTag(TAG_SEARCH),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search calls")
                }
            }
            FilterMenu(query = state.query, actions = actions, zone = zone)
            if (!state.searching) {
                OverflowMenu(actions = actions)
            }
        },
        below = {
            AppHeaderTabs(
                tabs = CallLogFilter.entries.map { if (it == CallLogFilter.ALL) "All" else "Missed" },
                selectedIndex = CallLogFilter.entries.indexOf(state.query.tabFilter),
                onSelect = { actions.onFilterChanged(CallLogFilter.entries[it]) },
                tabModifier = { Modifier.testTag(filterTag(CallLogFilter.entries[it])) },
            )
        },
    )
}

/**
 * The screen's rare actions, behind the usual three dots.
 *
 * Clearing the whole log was an icon of its own in the bar: a permanent, one-tap-from-a-
 * dialog invitation to delete everything, sitting next to Search. It is something a person
 * does once a year, and the bar is for what they do every day — so it moved in here, in
 * the error colour, which is where a destructive action reads as destructive.
 */
@Composable
private fun OverflowMenu(actions: HistoryActions) {
    var open by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TAG_OVERFLOW)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.testTag(TAG_OVERFLOW_MENU),
        ) {
            DropdownMenuItem(
                text = { Text("Call recordings") },
                leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onOpenRecordings()
                },
                modifier = Modifier.testTag(TAG_RECORDINGS),
            )
            DropdownMenuItem(
                text = { Text("Clear call history") },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                onClick = {
                    open = false
                    actions.onClearAllRequested()
                },
                modifier = Modifier.testTag(TAG_CLEAR_ALL),
            )
        }
    }
}

/** The search box, focused the moment it appears — opening it is the request to type. */
@Composable
private fun SearchField(text: String, onTextChanged: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val bar = AppTheme.barColors

    TextField(
        value = text,
        onValueChange = onTextChanged,
        singleLine = true,
        placeholder = { Text("Name, number or address", color = bar.onTopVariant) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = bar.onTop,
            unfocusedTextColor = bar.onTop,
            cursorColor = bar.onTop,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .testTag(TAG_SEARCH_FIELD),
    )
}

/**
 * The one filter control: an icon with a count, and a menu behind it (redesign, item 1).
 *
 * The menu has two groups and nothing else. *Show* is the direction — All, Incoming,
 * Outgoing, Missed — one of which is always on and is marked with a tick, in bold, in the
 * accent colour, so the selected filter is never in doubt. *Date* is a range from the
 * calendar, named once one is applied. A reset row appears only when there is something
 * to reset. The count on the icon is the number of menu filters in force, so a narrowed
 * list says so even from the far end of the screen.
 *
 * Missed is both a tab and a direction because it is the same axis; choosing it here
 * moves the tab, and the count does not include it — the tab is already showing it.
 */
@Composable
private fun FilterMenu(query: CallLogQuery, actions: HistoryActions, zone: ZoneId) {
    var open by rememberSaveable { mutableStateOf(false) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    val active = query.menuFilterCount
    val bar = AppTheme.barColors

    Box {
        // The count wraps the button rather than the glyph inside it. An IconButton clips
        // its content to the circle it draws its ripple in, and a badge pinned to the
        // corner of a 24dp glyph in the middle of that circle loses its outer edge to the
        // clip — a number with its corner shaved off, which reads as a rendering fault.
        BadgedBox(
            badge = {
                if (active > 0) {
                    Badge(containerColor = bar.topBadge, contentColor = bar.onTopBadge) {
                        Text(active.toString())
                    }
                }
            },
        ) {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag(TAG_FILTERS)) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = if (active > 0) "Filters, $active active" else "Filters",
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.testTag(TAG_FILTER_MENU),
        ) {
            FilterMenuItems(
                query = query,
                actions = actions,
                zone = zone,
                onPickDates = { pickingDates = true },
                onDone = { open = false },
            )
        }
    }

    if (pickingDates) {
        DateRangeDialog(
            initialFrom = query.fromEpochMillis?.toUtcDayMillis(zone),
            initialTo = query.toEpochMillis?.toUtcDayMillis(zone),
            onConfirm = { start, end ->
                pickingDates = false
                val bounds = dateRangeBounds(start, end, zone)
                actions.onDateRangeChanged(bounds.fromEpochMillis, bounds.toEpochMillis)
            },
            onDismiss = { pickingDates = false },
        )
    }
}

/** The menu's rows: the direction group, the date row, and a reset when there is something to reset. */
@Composable
private fun FilterMenuItems(
    query: CallLogQuery,
    actions: HistoryActions,
    zone: ZoneId,
    onPickDates: () -> Unit,
    onDone: () -> Unit,
) {
    MenuHeading("Show")
    CallDirectionFilter.entries.forEach { direction ->
        val selected = query.direction == direction
        DropdownMenuItem(
            text = { MenuLabel(direction.label, selected) },
            leadingIcon = { Icon(direction.icon(), contentDescription = null) },
            trailingIcon = { if (selected) SelectedMark() },
            onClick = {
                onDone()
                actions.onDirectionChanged(direction)
            },
            colors = menuItemColors(selected),
            modifier = Modifier
                .selectedRow(selected)
                .testTag(directionItemTag(direction)),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = AppTheme.spacing.extraSmall))
    MenuHeading("Date")
    val range = query.dateRangeLabel(zone)
    DropdownMenuItem(
        text = { MenuLabel(range ?: "Choose a date range", selected = range != null) },
        leadingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
        trailingIcon = { if (range != null) SelectedMark() },
        onClick = {
            onDone()
            onPickDates()
        },
        colors = menuItemColors(range != null),
        modifier = Modifier
            .selectedRow(range != null)
            .testTag(TAG_DATE_ITEM),
    )

    if (query.hasMenuFilters) {
        HorizontalDivider(modifier = Modifier.padding(vertical = AppTheme.spacing.extraSmall))
        DropdownMenuItem(
            text = { Text("Reset filters") },
            leadingIcon = { Icon(Icons.Outlined.FilterListOff, contentDescription = null) },
            onClick = {
                onDone()
                actions.onFiltersCleared()
            },
            modifier = Modifier.testTag(TAG_RESET_FILTERS),
        )
    }
}

/** A group's name inside the menu — quiet, so the options are what the eye reads. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = AppTheme.spacing.medium,
            end = AppTheme.spacing.medium,
            top = AppTheme.spacing.small,
            bottom = AppTheme.spacing.extraSmall,
        ),
    )
}

/** The selected option is bold as well as tinted, so it reads as selected without colour. */
@Composable
private fun MenuLabel(text: String, selected: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** The tick on the option in force. Untinted, so it takes the row's content colour. */
@Composable
private fun SelectedMark() {
    Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected")
}

/**
 * The pill behind the option in force.
 *
 * Three channels say which filter is on — a filled row, a bold label, and a tick — because
 * "the selected filter is clearly highlighted" cannot rest on a tint of the text alone:
 * at a glance a menu of four similar rows with one of them slightly greener is a menu of
 * four similar rows. Inset from the menu's edges and rounded, so it reads as a chosen
 * thing rather than as a band across the sheet.
 */
@Composable
private fun Modifier.selectedRow(selected: Boolean): Modifier = if (selected) {
    padding(horizontal = AppTheme.spacing.small)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.primaryContainer)
} else {
    this
}

/** On the pill, every part of the row is drawn in the colour paired with it. */
@Composable
private fun menuItemColors(selected: Boolean) = if (selected) {
    MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
        leadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        trailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
} else {
    MenuDefaults.itemColors()
}

/**
 * The filters in force, each with its own way off (redesign, item 1).
 *
 * Under the header, only while a menu filter is on: a direction other than the tabs
 * already show, or a date range. One chip per filter and a cross on each, so what is
 * narrowing the list is visible without opening the menu and can be dropped without
 * finding it there. Empty and absent otherwise — a resting log is a list, not a form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilters(query: CallLogQuery, actions: HistoryActions, zone: ZoneId) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small)
            .testTag(TAG_ACTIVE_FILTERS),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
    ) {
        if (query.direction.isMenuOnly) {
            ActiveFilterChip(
                label = query.direction.label,
                icon = query.direction.icon(),
                onClear = { actions.onDirectionChanged(CallDirectionFilter.ANY) },
                clearDescription = "Show all directions",
                modifier = Modifier.testTag(activeDirectionTag(query.direction)),
            )
        }
        query.dateRangeLabel(zone)?.let { label ->
            ActiveFilterChip(
                label = label,
                icon = Icons.Outlined.DateRange,
                onClear = { actions.onDateRangeChanged(null, null) },
                clearDescription = "Clear the date range",
                modifier = Modifier.testTag(TAG_ACTIVE_DATE),
            )
        }
    }
}

@Composable
private fun ActiveFilterChip(
    label: String,
    icon: ImageVector,
    onClear: () -> Unit,
    clearDescription: String,
    modifier: Modifier = Modifier,
) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AppTheme.sizing.chipIcon)) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = clearDescription,
                modifier = Modifier.size(AppTheme.sizing.chipIcon),
            )
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    initialFrom: Long?,
    initialTo: Long?,
    onConfirm: (startUtcDayMillis: Long, endUtcDayMillis: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom,
        initialSelectedEndDateMillis = initialTo,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = pickerState.selectedStartDateMillis
            // One day is a range too: a start with no end means that day.
            TextButton(
                onClick = { if (start != null) onConfirm(start, pickerState.selectedEndDateMillis) },
                enabled = start != null,
                modifier = Modifier.testTag(TAG_DATE_CONFIRM),
            ) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.testTag(TAG_DATE_DIALOG),
    ) {
        DateRangePicker(
            state = pickerState,
            // The dialog is already headed; a second title inside it is a title twice.
            title = null,
            showModeToggle = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CallList(
    rows: LazyPagingItems<HistoryRow>,
    actions: HistoryActions,
    zone: ZoneId,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_LIST),
        // Room for the floating buttons, so the newest call is not the one row the user
        // can never fully read (Task 70).
        contentPadding = PaddingValues(
            bottom = AppTheme.spacing.huge + AppTheme.sizing.callActionButton,
        ),
    ) {
        items(
            count = rows.itemCount,
            // Keyed by row identity so deleting one animates that row out rather than
            // re-drawing everything below it.
            key = { index -> rows.peek(index)?.key() ?: index },
        ) { index ->
            when (val row = rows[index]) {
                is HistoryRow.DayHeader -> DayHeading(row.epochDay)
                is HistoryRow.Call -> CallRow(row, actions, zone)
                null -> Unit
            }
        }
    }
}

/**
 * The dialler (Task 70).
 *
 * A second, smaller FAB above it opened the group-call page. That page is gone: it built a
 * group this app had no way to act on, because ADR-003's dial-in MCU gives a client no way
 * to create a room or invite anyone into one. Dialling a bridge is an ordinary call and
 * the dialler already places it.
 */
@Composable
private fun HistoryFab(actions: HistoryActions) {
    FloatingActionButton(
        onClick = actions.onOpenDialer,
        elevation = FloatingActionButtonDefaults.elevation(),
        modifier = Modifier.testTag(TAG_DIALER),
    ) {
        Icon(Icons.Filled.Dialpad, contentDescription = "Open the dialler")
    }
}

@Composable
private fun DayHeading(epochDay: Long) {
    Text(
        text = LocalDate.ofEpochDay(epochDay).format(DAY_FORMAT),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small)
            .testTag(dayHeadingTag(epochDay)),
    )
}

@Composable
private fun CallRow(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry

    // Swipe to call back, rather than two buttons on every row (item 3). The log is a
    // list people scan, and a pair of icons per line competes with the thing they are
    // scanning for. Left is video, right is audio.
    //
    // This is a swipe *action*, not a dismissal: the row performs the call and springs
    // back, so the entry stays in the log — which is the whole point of a log. The box
    // reports the swipe once it settles, and `reset` animates the row home. (It used to
    // veto the settle in `confirmValueChange`; that callback is deprecated without a
    // replacement, and letting the row settle then return is the documented shape.)
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { SwipeAffordance(swipe.dismissDirection) },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> actions.onVideoCallBack(entry)
                SwipeToDismissBoxValue.StartToEnd -> actions.onCallBack(entry)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { swipe.reset() }
        },
        modifier = Modifier.testTag(entryTag(entry)),
    ) {
        CallRowContent(row, actions, zone)
    }
}

/** The row itself, so [CallRow] stays the gesture and this stays the layout. */
@Composable
private fun CallRowContent(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { actions.onEntryOpened(row) }
            // A swipe is not an affordance everyone has. TalkBack reads these two as
            // actions on the row, so removing the buttons did not remove the ability
            // to call back — it removed two taps from everybody who can swipe.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Call ${row.title} back") {
                        actions.onCallBack(entry)
                        true
                    },
                    CustomAccessibilityAction("Video call ${row.title} back") {
                        actions.onVideoCallBack(entry)
                        true
                    },
                )
            }
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        // The face first, the way every list of people is arranged. The direction moved
        // down beside the time it belongs to: it describes what happened, not who it was
        // with, and it had been sitting where the person should be.
        Avatar(displayName = row.title.takeIf { title -> title.any(Char::isLetter) })

        CallRowText(row = row, zone = zone, modifier = Modifier.weight(1f))

        // What kind of call it was, at the end of the row where a calling app keeps it
        // (item 5.3). The log records it and the row did not show it, so a missed video
        // call and a missed audio call looked identical. Both kinds get a glyph: video is
        // not the exception being flagged, it is one of two things a call can be.
        Icon(
            imageVector = if (entry.media.hasVideo) Icons.Filled.Videocam else Icons.Filled.Call,
            contentDescription = if (entry.media.hasVideo) "Video call" else "Voice call",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(AppTheme.sizing.listTrailingIcon)
                .testTag(mediaTag(entry)),
        )
    }
}

/** Who it was, and underneath, what happened and when. */
@Composable
private fun CallRowText(row: HistoryRow.Call, zone: ZoneId, modifier: Modifier = Modifier) {
    val entry = row.entry
    Column(modifier = modifier) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium,
            // A missed call is what someone opens this screen looking for, so it
            // is the one the eye lands on. Weight as well as colour: colour alone
            // is not a channel everybody has.
            color = if (entry.wasMissed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
        ) {
            Icon(
                imageVector = entry.directionIcon(),
                contentDescription = entry.directionDescription(),
                tint = entry.directionTint(),
                modifier = Modifier.size(AppTheme.spacing.medium),
            )
            Text(
                text = entry.subtitle(zone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What appears behind a row being swiped, so the gesture says what it will do.
 *
 * A swipe with no feedback is a guess. The icon and the side it sits on are the whole
 * instruction: drag right for a voice call, left for video.
 */
@Composable
private fun SwipeAffordance(direction: SwipeToDismissBoxValue) {
    val audio = direction == SwipeToDismissBoxValue.StartToEnd
    val colour = if (audio) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (direction == SwipeToDismissBoxValue.Settled) Color.Transparent else colour)
            .padding(horizontal = AppTheme.spacing.large),
        contentAlignment = if (audio) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(
                imageVector = if (audio) Icons.Filled.Call else Icons.Filled.Videocam,
                contentDescription = null,
                tint = if (audio) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
    }
}

@Composable
private fun CallDetail(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry
    ConfirmDialog(
        title = row.title,
        // The reason comes from Task 44's table, so the sentence here and the one the
        // dialler showed when the call failed are the same sentence.
        message = buildString {
            appendLine(entry.remote.render())
            appendLine(if (entry.media.hasVideo) "Video call" else "Voice call")
            appendLine(entry.reason.userMessage())
            append(entry.subtitle(zone))
        },
        confirmLabel = "Delete",
        onConfirm = { actions.onDelete(entry) },
        onDismiss = actions.onDetailDismissed,
        destructive = true,
        dismissLabel = "Close",
        modifier = Modifier.testTag(TAG_DETAIL),
    )
}

// ---------------------------------------------------------------- presentation

/** The glyph a direction filter wears in the menu and on its chip; the row's own glyphs are the same family. */
private fun CallDirectionFilter.icon(): ImageVector = when (this) {
    CallDirectionFilter.ANY -> Icons.Outlined.Phone
    CallDirectionFilter.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    CallDirectionFilter.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
    CallDirectionFilter.MISSED -> Icons.AutoMirrored.Filled.CallMissed
}

/** A direction the tabs cannot show — the ones the menu exists for. */
private val CallDirectionFilter.isMenuOnly: Boolean
    get() = this == CallDirectionFilter.INCOMING || this == CallDirectionFilter.OUTGOING

/** The applied range, named, or null when there is none. */
private fun CallLogQuery.dateRangeLabel(zone: ZoneId): String? {
    val from = fromEpochMillis ?: return null
    val to = toEpochMillis ?: return null
    return dateRangeLabel(from, to, zone)
}

/**
 * How many filters the menu is holding that the tabs are not already showing.
 *
 * The badge on the filter icon. Missed is excluded on purpose: it is on the tab row
 * with the tab lit, and counting it would say "1 filter" over a screen that is visibly
 * on Missed.
 */
private val CallLogQuery.menuFilterCount: Int
    get() = listOf(direction.isMenuOnly, fromEpochMillis != null && toEpochMillis != null).count { it }

private val CallLogQuery.hasMenuFilters: Boolean get() = menuFilterCount > 0

private fun HistoryRow.key(): Any = when (this) {
    is HistoryRow.DayHeader -> "day-$epochDay"
    is HistoryRow.Call -> "call-${entry.id.value}"
}

private fun CallLogEntry.directionIcon(): ImageVector = when {
    wasMissed -> Icons.AutoMirrored.Filled.CallMissed
    direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
}

/**
 * The arrow's colour, from roles meant to be drawn on the page.
 *
 * These were the `on*Container` roles, which are the colours you put *on* a filled badge:
 * near-black in light by design. The badge they were paired with is gone, so all three
 * arrows were the same black mark on the surface and only their shape told them apart —
 * at 12dp, next to the time, that is no distinction at all. Primary, secondary and error
 * are the page-level roles, they differ at a glance, and the missed one is the red the
 * title above it already uses.
 */
@Composable
private fun CallLogEntry.directionTint(): Color = when {
    wasMissed -> MaterialTheme.colorScheme.error
    direction == CallDirection.INCOMING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun CallLogEntry.directionDescription() = when {
    wasMissed -> "Missed call"
    direction == CallDirection.INCOMING -> "Incoming call"
    else -> "Outgoing call"
}

private fun CallLogEntry.subtitle(zone: ZoneId): String {
    val at = Instant.ofEpochMilli(startedAtEpochMillis).atZone(zone).format(TIME_FORMAT)
    return if (wasAnswered) "$at · ${formatDuration(durationSeconds)}" else at
}

/** `m:ss`, or `h:mm:ss` past the hour. A 75-minute call is not 75:00. */
internal fun formatDuration(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${remainder.padded()}"
    } else {
        "$minutes:${remainder.padded()}"
    }
}

private fun Long.padded(): String = toString().padStart(2, '0')

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

internal const val TAG_LIST = "history-list"
internal const val TAG_EMPTY = "history-empty"
internal const val TAG_DETAIL = "history-detail"
internal const val TAG_CONFIRM_CLEAR = "history-confirm-clear"
internal const val TAG_CLEAR_ALL = "history-clear-all"
internal const val TAG_OVERFLOW = "history-overflow"
internal const val TAG_RECORDINGS = "history-recordings"
internal const val TAG_OVERFLOW_MENU = "history-overflow-menu"
internal const val TAG_SEARCH = "history-search"
internal const val TAG_SEARCH_FIELD = "history-search-field"

/** Identifies a direction in the filter menu, so a test presses the one it means. */
internal fun directionItemTag(direction: CallDirectionFilter) = "history-direction-${direction.name.lowercase()}"

/** The active-filter chip for a direction, with its cross. */
internal fun activeDirectionTag(direction: CallDirectionFilter) = "history-active-${direction.name.lowercase()}"
internal const val TAG_FILTERS = "history-filters"
internal const val TAG_FILTER_MENU = "history-filter-menu"
internal const val TAG_RESET_FILTERS = "history-reset-filters"
internal const val TAG_ACTIVE_FILTERS = "history-active-filters"
internal const val TAG_ACTIVE_DATE = "history-active-date"
internal const val TAG_DATE_ITEM = "history-date-item"
internal const val TAG_DATE_DIALOG = "history-date-dialog"
internal const val TAG_DATE_CONFIRM = "history-date-confirm"
internal const val TAG_DIALER = "history-dialer"

/** The audio/video glyph on one row, so a test can ask which kind the row says it was. */
internal fun mediaTag(entry: CallLogEntry) = "history-media-${entry.id.value}"

internal fun filterTag(filter: CallLogFilter) = "history-filter-${filter.name.lowercase()}"
internal fun dayHeadingTag(epochDay: Long) = "history-day-$epochDay"

/** Identifies one row, so a test can find the entry it means rather than a position. */
internal fun entryTag(entry: CallLogEntry) = "history-entry-${entry.id.value}"
