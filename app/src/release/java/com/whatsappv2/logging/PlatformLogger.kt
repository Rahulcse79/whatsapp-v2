package com.whatsappv2.logging

import android.util.Log
import com.whatsappv2.core.common.logging.Logger

/**
 * Release-variant logger.
 *
 * Verbose and debug are empty by construction — this class, not a runtime level check,
 * is what guarantees they never emit. R8 inlines the empty bodies and removes the call
 * sites, so the release binary contains no debug logging path (Task 5 done-when #3).
 *
 * Warnings and errors survive because a field failure that leaves no trace is not
 * diagnosable. Callers must not pass credentials, SIP headers, phone numbers or
 * contact data into them — see the redaction helpers in :core:common (§7, DoD 12).
 */
class PlatformLogger : Logger {
    override fun verbose(tag: String, message: String) = Unit

    override fun debug(tag: String, message: String) = Unit

    override fun info(tag: String, message: String) = Unit

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
