package com.whatsappv2.calllog

import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.usecase.CallLogRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the call log being written for as long as the process lives (Task 47).
 *
 * The recording belongs to the application rather than to the history screen: a call that
 * ends while the user is on the dialler, or with no activity on screen at all, is still a
 * call to record — and it is the missed ones that arrive that way. Starting it here is
 * what makes "every call outcome produces exactly one entry" true rather than "every call
 * the user happened to be watching".
 *
 * Started from [com.whatsappv2.SipApplication] alongside the audio coordinator and for
 * the same reason: it holds nothing until a call ends, so starting it early costs a
 * suspended coroutine and buys the entries that would otherwise be missed.
 */
@Singleton
class CallLogWriter @Inject constructor(
    private val recorder: CallLogRecorder,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Begins recording. Safe to call once; the scope owns the coroutine from here. */
    fun start() {
        scope.launch { recorder.record() }
    }
}
