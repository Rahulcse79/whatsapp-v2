package com.whatsappv2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The target of the restart alarm [RegistrationService.scheduleRestart] books.
 *
 * It exists so that something outside this process holds the instruction to come back.
 * A handset that ends the process with its task takes every coroutine and every pending
 * `startForegroundService` with it; an alarm lives in the system and fires into a fresh
 * process, whose `SipApplication.onCreate` restores the accounts while the service it
 * starts here holds that process up.
 *
 * On a platform that kept the process this is one more `onStartCommand`, which the
 * service answers by re-reading its state — a no-op.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RegistrationService.start(context, expectRestore = true)
    }
}
