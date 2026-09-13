package com.whatsappv2.onboarding

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What the tour says, as data (first-run step 2).
 *
 * ## Six pages, and each one names a control the user will actually look for
 *
 * The temptation with a tour is to describe the product. Nobody remembers that. What a
 * person carries out of one is *where a thing is* — so every page here is anchored to a
 * real control on a real screen, spelled the way the app spells it, and the [where] line
 * is the part worth remembering. "Merge" is the word on the button; "conference" is not.
 *
 * Pages are ordered by when the user will need them: register, call, then the things that
 * only matter once a call is up, then the two that are about coming back later.
 *
 * Data rather than layout so the copy can be reviewed by somebody who does not read
 * Compose, and so the pager stays one composable however many pages there are.
 */
internal data class TourPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    /** Where the thing actually is. The line the user is meant to keep. */
    val where: String,
)
