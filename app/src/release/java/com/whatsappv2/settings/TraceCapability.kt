package com.whatsappv2.settings

/**
 * Whether SIP tracing can be offered in this build.
 *
 * Release builds must not offer tracing at all. A runtime check could be flipped by a
 * remote config or a stray boolean; an absent capability cannot.
 *
 * Selected by build type through the source set, exactly like PlatformLogger - so the
 * release binary contains the constant `false` and the toggle is compiled out of the
 * settings screen (§7, DoD 12).
 */
object TraceCapability {
    const val IS_AVAILABLE: Boolean = false
}
