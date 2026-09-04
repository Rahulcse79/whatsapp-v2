package com.whatsappv2.core.common.logging

/**
 * The only logging surface the codebase may use.
 *
 * `android.util.Log` is forbidden everywhere else (enforced by a detekt
 * `ForbiddenImport` rule) for two reasons: release builds must not emit SIP messages,
 * credentials, phone numbers or contact data (§7, DoD 12), and a facade lets the
 * release variant omit the debug paths entirely.
 *
 * This interface is pure Kotlin and lives in a JVM module; the Android-backed
 * implementation is supplied by `:app`, which has the build variants needed to drop
 * verbose and debug logging by construction rather than by a runtime check.
 */
interface Logger {
    fun verbose(tag: String, message: String)
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** Discards everything. The default in tests and in any context without a real sink. */
object NoOpLogger : Logger {
    override fun verbose(tag: String, message: String) = Unit
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}
