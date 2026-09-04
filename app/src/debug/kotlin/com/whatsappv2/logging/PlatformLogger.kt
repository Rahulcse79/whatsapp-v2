package com.whatsappv2.logging

import android.util.Log
import com.whatsappv2.core.common.logging.Logger

/**
 * Debug-variant logger. Everything reaches logcat.
 *
 * This file exists only in `src/debug`; the release variant compiles a different class
 * with the same name whose verbose and debug bodies are empty. Levels are therefore
 * dropped **by construction** — there is no runtime check to get wrong, and no debug
 * logging code in the release binary at all (Task 5 done-when #3).
 */
class PlatformLogger : Logger {
    override fun verbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
