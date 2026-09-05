package com.whatsappv2.data.sip.registration

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Where the integration suite points, from instrumentation arguments (Task 32).
 *
 * Nothing here is committed. `data/sip/build.gradle.kts` reads Gradle properties or
 * environment variables and passes them through as instrumentation arguments; a developer
 * sets them once in `~/.gradle/gradle.properties` and CI supplies them as secrets. See
 * `docs/testing.md`.
 */
internal data class TestTarget(
    val host: String,
    val domain: String,
    val port: Int,
    val extension: String,
    val secondaryExtension: String,
    val password: String,
) {
    /** `sip:host:port`, the registrar these tests register against. */
    val registrarUri: String get() = "sip:$host:$port"

    companion object {
        /**
         * The configured target, or null when the suite was not told about a server.
         *
         * Null rather than a default: an invented host would produce a test that fails
         * with a timeout and reads exactly like a broken client.
         */
        fun fromArguments(): TestTarget? {
            val arguments = InstrumentationRegistry.getArguments()
            fun argument(name: String) = arguments.getString(name)?.takeIf { it.isNotBlank() }

            val host = argument("sipTestHost") ?: return null
            return TestTarget(
                host = host,
                domain = argument("sipTestDomain") ?: host,
                port = argument("sipTestPort")?.toIntOrNull() ?: DEFAULT_SIP_PORT,
                extension = argument("sipTestExtension") ?: return null,
                secondaryExtension = argument("sipTestExtensionSecondary")
                    ?: return null,
                password = argument("sipTestPassword") ?: return null,
            )
        }

        /**
         * The target, or the whole test is skipped with a message saying what to set.
         *
         * A skip, not a failure. A red run that only means "nobody gave this a server"
         * teaches people to ignore red runs, which costs more than the coverage is worth.
         */
        fun requireConfigured(): TestTarget {
            val target = fromArguments()
            assumeTrue(
                "No SIP test target configured. Set sip.test.host, sip.test.extension, " +
                    "sip.test.extension.secondary and sip.test.password — see docs/testing.md.",
                target != null,
            )
            return target!!
        }

        private const val DEFAULT_SIP_PORT = 5060
    }
}
