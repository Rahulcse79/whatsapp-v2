package com.whatsappv2.calllog

import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.repository.AppSettingsRepository
import com.whatsappv2.domain.repository.CallLogRepository
import com.whatsappv2.domain.usecase.PruneCallHistoryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the call log inside the retention the user chose.
 *
 * ## Three moments, one subscription
 *
 * Pruning has to happen when the app starts, when the setting changes, and when a call is
 * added — the third because a phone left open for a week would otherwise keep showing
 * entries that are already past their date. Combining the setting with the log's own
 * `changes()` gives all three from one collector: the app start is the first emission,
 * the setting change is a new value, and a recorded call is a change.
 *
 * `distinctUntilChanged` goes on the retention stream *before* the combine, and nowhere
 * after it. Before, it drops the settings writes that did not touch the retention — a
 * theme change is not a reason to prune. After, it would compare one retention with the
 * same retention and swallow the recorded call, which is the one moment that catches a
 * device that has been awake for a week.
 *
 * ## It settles on its own
 *
 * A prune that removes rows is itself a change, so the log emits once more and the prune
 * runs once more. That second run removes nothing, and a delete that matches no row
 * invalidates nothing, so the loop ends there — one extra indexed `DELETE` per real prune,
 * not a spin.
 *
 * ## Started from the application, and holding nothing until then
 *
 * Same rule as `CallLogWriter`, and for the same reason: this costs one suspended
 * coroutine before the first emission, and buys a log that is correct without anybody
 * having opened the history screen.
 */
@Singleton
class CallHistoryPruner @Inject constructor(
    private val prune: PruneCallHistoryUseCase,
    private val settings: AppSettingsRepository,
    private val callLog: CallLogRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Begins pruning. Safe to call once; the scope owns the coroutine from here. */
    fun start() {
        combine(
            settings.observeSettings().map { it.callHistoryRetention }.distinctUntilChanged(),
            callLog.changes(),
        ) { retention, _ -> retention }
            .onEach { retention -> prune(retention) }
            .launchIn(scope)
    }
}
