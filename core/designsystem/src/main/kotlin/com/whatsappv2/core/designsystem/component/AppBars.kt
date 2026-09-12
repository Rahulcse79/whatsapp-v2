package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The app's top bar, on the brand header (see `BarColors`).
 *
 * One component for every screen, so the header is the same green in light and the same
 * near-black in dark wherever the user is, and so a control drawn on it — the
 * registration chip, a search field — inherits the right content colour through
 * [LocalContentColor] instead of guessing.
 *
 * [below] is for a screen whose header carries more than a title: the Calls screen's
 * All / Missed tabs sit inside the header block rather than under it, the way a
 * messaging app's tabs do, so the header reads as one shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /** Replaces the title text when set — a search field, say. Drawn in the bar's content colour. */
    titleContent: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val bar = AppTheme.barColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(bar.topStart, bar.topEnd))),
    ) {
        CompositionLocalProvider(LocalContentColor provides bar.onTop) {
            TopAppBar(
                title = titleContent ?: {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = bar.onTop,
                    navigationIconContentColor = bar.onTop,
                    actionIconContentColor = bar.onTop,
                ),
            )
            below?.invoke(this)
        }
    }
}

/**
 * A row of text tabs inside the header (see [AppTopBar.below]).
 *
 * The unselected tab is the header's secondary tone rather than a faded copy of the
 * selected one, and the indicator is the header's own — white on green, green on black —
 * so "which tab am I on" is answered by two channels, weight and colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeaderTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** A modifier per tab, so a caller can tag the one a test means. */
    tabModifier: (Int) -> Modifier = { Modifier },
) {
    val bar = AppTheme.barColors
    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = bar.onTop,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex),
                color = bar.topIndicator,
                width = AppTheme.sizing.tabIndicatorWidth,
            )
        },
        divider = {},
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                selectedContentColor = bar.onTop,
                unselectedContentColor = bar.onTopVariant,
                text = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                },
                modifier = tabModifier(index),
            )
        }
    }
}

/**
 * The bottom navigation bar, on the brand palette.
 *
 * Material's `NavigationBarItem`s — their pill, their ripple, their label motion — on a
 * row the height the design system says (`Sizing.bottomBar`, the 64 dp short bar), with
 * a hairline above so the bar reads as a separate surface without a shadow, and the
 * system inset padded inside the colour so it runs under the gesture bar.
 */
@Composable
fun AppNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val bar = AppTheme.barColors
    Surface(color = bar.navContainer, modifier = modifier) {
        Column {
            HorizontalDivider(color = bar.navEdge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(AppTheme.sizing.bottomBar)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** One destination in [AppNavigationBar]. The label is always shown; an icon-only bar makes people guess. */
@Composable
fun RowScope.AppNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val bar = AppTheme.barColors
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = bar.navIndicator,
            selectedIconColor = bar.onNavIndicator,
            selectedTextColor = bar.navSelectedLabel,
            unselectedIconColor = bar.navUnselected,
            unselectedTextColor = bar.navUnselected,
        ),
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun AppTopBarPreview() = PreviewSurface {
    AppTopBar(
        title = "Calls",
        actions = {
            IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = null) }
        },
        below = { AppHeaderTabs(tabs = listOf("All", "Missed"), selectedIndex = 0, onSelect = {}) },
    )
}

@ThemePreviews
@Composable
private fun AppNavigationBarPreview() = PreviewSurface {
    AppNavigationBar {
        AppNavigationItem(selected = true, onClick = {}, icon = Icons.AutoMirrored.Filled.Chat, label = "Chats")
        AppNavigationItem(selected = false, onClick = {}, icon = Icons.Filled.History, label = "Calls")
    }
}
