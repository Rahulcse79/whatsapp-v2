package com.whatsappv2.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * The feature tour (first-run step 2), shown once and then never again.
 *
 * ## Swipeable, because a tour you can only click through is a wall
 *
 * A pager rather than a stack of screens with Next buttons: the gesture is the one every
 * person already has for "show me the next one", and it makes going *back* free, which
 * matters more than it sounds — somebody who skimmed page three and wants it again will
 * swipe, and will not hunt for a Back button they were not given.
 *
 * The card lifts and fades with how far it is from settled, so a half-finished swipe reads
 * as a half-finished swipe. That is the whole of the animation; a tour that performs is a
 * tour that is in the way.
 *
 * ## Skip is present on every page and says what it does
 *
 * Not hidden, not greyed, not at the end. Someone reinstalling knows all of this already,
 * and a first-run flow that makes them prove they have read it is a flow they resent. It
 * marks the tour seen exactly as finishing does — [onFinish] is the same callback, because
 * "I have seen enough" and "I have seen it all" are the same fact about whether to show it
 * again.
 */
@Composable
internal fun FeatureTour(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    pages: List<TourPage> = TourPages,
) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AppTheme.spacing.small),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.alpha(if (last) 0f else 1f).testTag(TAG_TOUR_SKIP),
                ) {
                    Text("Skip")
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).testTag(TAG_TOUR_PAGER),
            ) { page ->
                // How far this page is from settled, 0 at rest and 1 when fully away. The
                // card reads its own offset rather than the pager's current page, so two
                // cards mid-swipe are each drawn for where they actually are.
                val drift = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                    .absoluteValue.coerceIn(0f, 1f)
                TourCard(
                    page = pages[page],
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - drift
                        val shrink = 1f - drift * CARD_SHRINK
                        scaleX = shrink
                        scaleY = shrink
                    },
                )
            }

            TourFooter(
                pageCount = pages.size,
                current = pager.currentPage,
                last = last,
                onNext = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                onFinish = onFinish,
            )
        }
    }
}

/** One feature: its mark, what it does, and — the line worth keeping — where it lives. */
@Composable
private fun TourCard(page: TourPage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(AppTheme.spacing.huge)
                    .size(AppTheme.sizing.callActionIcon),
            )
        }

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.extraLarge),
        )
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.medium),
        )

        // The highlight: the control's real path, in the app's own words, on its own
        // surface so the eye returns to it. This is the part a person keeps.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(top = AppTheme.spacing.extraLarge),
        ) {
            Text(
                text = page.where,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = AppTheme.spacing.large,
                    vertical = AppTheme.spacing.medium,
                ),
            )
        }
    }
}

/** The dots and the one button that moves forward. */
@Composable
private fun TourFooter(
    pageCount: Int,
    current: Int,
    last: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(AppTheme.spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge),
    ) {
        PageDots(pageCount = pageCount, current = current)

        Button(
            onClick = if (last) onFinish else onNext,
            modifier = Modifier.fillMaxWidth().testTag(TAG_TOUR_NEXT),
        ) {
            Text(if (last) "Get started" else "Next")
            if (!last) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = AppTheme.spacing.small)
                        .size(AppTheme.sizing.chipIcon),
                )
            }
        }
    }
}

/**
 * Where you are in the tour.
 *
 * The settled dot stretches into a bar rather than just changing colour, so the position
 * survives being looked at with the colour ignored — the same rule the status dots follow.
 * Announced once, as a sentence, instead of as a row of anonymous shapes.
 */
@Composable
private fun PageDots(pageCount: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${current + 1} of $pageCount"
        },
    ) {
        repeat(pageCount) { index ->
            val settled = index == current
            val width by animateDpAsState(
                targetValue = if (settled) AppTheme.sizing.tabIndicatorWidth else AppTheme.sizing.statusDot,
                label = "dot-width",
            )
            val fade by animateFloatAsState(if (settled) 1f else DOT_DIM, label = "dot-alpha")
            Box(
                modifier = Modifier
                    .height(AppTheme.sizing.statusDot)
                    .width(width)
                    .clip(CircleShape)
                    .alpha(fade)
                    .background(
                        if (settled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
        Spacer(Modifier.width(AppTheme.spacing.none))
    }
}

/** How much a page shrinks as it leaves; small enough to feel like depth, not a zoom. */
private const val CARD_SHRINK = 0.12f

/** An unsettled dot's opacity. Visible as a position, quiet as a mark. */
private const val DOT_DIM = 0.5f

internal const val TAG_TOUR_PAGER = "tour-pager"
internal const val TAG_TOUR_NEXT = "tour-next"
internal const val TAG_TOUR_SKIP = "tour-skip"

@ThemePreviews
@Composable
private fun FeatureTourPreview() = PreviewSurface { FeatureTour(onFinish = {}) }
