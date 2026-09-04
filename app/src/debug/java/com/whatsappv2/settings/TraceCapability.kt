package com.whatsappv2.settings

/**
 * Whether SIP tracing can be offered in this build.
 *
 * Debug builds may trace: this is where registration problems are diagnosed.
 *
 * Selected by build type through the source set, exactly like PlatformLogger - so the
 * release binary contains the constant `false` and the toggle is compiled out of the
 * settings screen (§7, DoD 12).
 */
object TraceCapability {
    const val IS_AVAILABLE: Boolean = true
}
