package com.whatsappv2.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Every gap, padding and inset in the app comes from here.
 *
 * A scale rather than free `.dp` literals: once one screen uses 12.dp and its neighbour
 * uses 14.dp, nobody can tell whether the difference is intentional, and every later
 * change becomes a judgement call. An architecture rule forbids raw `.dp` outside this
 * module so the scale cannot be quietly bypassed.
 *
 * Steps are 4dp apart, which is the Material grid.
 */
data class Spacing(
    /** 0dp — for explicitly removing a default. */
    val none: Dp = 0.dp,

    /** 4dp — between an icon and its label. */
    val extraSmall: Dp = 4.dp,

    /** 8dp — between tightly related elements. */
    val small: Dp = 8.dp,

    /** 12dp — inside a compact list row. */
    val medium: Dp = 12.dp,

    /** 16dp — the default screen margin. */
    val large: Dp = 16.dp,

    /** 24dp — between unrelated sections. */
    val extraLarge: Dp = 24.dp,

    /** 32dp — around a full-screen empty or error state. */
    val huge: Dp = 32.dp,
)

/** Corner radii, so a card and a dialog cannot disagree by two pixels. */
data class Radius(
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 20.dp,
    val full: Dp = 1_000.dp,
)

/** Fixed sizes for things whose dimensions are part of their identity. */
data class Sizing(
    /** Minimum touch target. Below this, people miss (Material accessibility). */
    val minimumTouchTarget: Dp = 48.dp,

    /** Avatar in a list row. */
    val avatarSmall: Dp = 40.dp,

    /** Avatar on the in-call screen. */
    val avatarLarge: Dp = 96.dp,

    /** Diameter of a call action button. */
    val callActionButton: Dp = 64.dp,

    /** Diameter of the answer and hang-up buttons, deliberately larger. */
    val callPrimaryButton: Dp = 72.dp,

    /** Icon inside a call action button. */
    val callActionIcon: Dp = 28.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalRadius = staticCompositionLocalOf { Radius() }
val LocalSizing = staticCompositionLocalOf { Sizing() }
