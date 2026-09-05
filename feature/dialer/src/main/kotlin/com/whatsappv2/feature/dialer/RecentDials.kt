package com.whatsappv2.feature.dialer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has been dialled, most recent first (Task 36).
 *
 * ## Why this is in memory and says so
 *
 * The call log is Task 47 and does not exist yet. The dialer's shortcuts do not need it —
 * what they need is "the numbers dialled from this dialer" — so that is exactly what this
 * holds, and only for the life of the process. The alternative would be to build half a
 * call log here and then have two of them.
 *
 * When Task 48 lands, the shortcuts read from the stored log instead. The screen does not
 * change: it already takes a list of strings.
 *
 * Deduplicated and capped, because a redial list where the same extension appears four
 * times is a list with one useful entry.
 */
@Singleton
class RecentDials @Inject constructor() {

    private val entries = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = entries.asStateFlow()

    /** Records a dialled target, moving a repeat back to the front rather than duplicating it. */
    fun record(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return

        entries.update { current ->
            (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(MAX_ENTRIES)
        }
    }

    private companion object {
        /** Enough for a row of shortcuts; more would need a screen of its own, which is Task 48. */
        const val MAX_ENTRIES = 4
    }
}
