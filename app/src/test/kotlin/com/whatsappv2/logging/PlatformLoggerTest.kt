package com.whatsappv2.logging

import com.whatsappv2.core.common.logging.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.whatsappv2.di.ROBOLECTRIC_SDK

/**
 * The variant-specific logger must satisfy the facade and never throw.
 *
 * Which class this exercises depends on the variant under test: `src/debug` logs
 * everything, `src/release` has empty verbose and debug bodies. Unit tests run against
 * the debug variant by default, so this covers the debug implementation; the release
 * class is verified by the log-capture check in Task 63 (DoD 12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PlatformLoggerTest {

    @Test
    fun `accepts every level without throwing`() {
        val logger: Logger = PlatformLogger()
        logger.verbose("tag", "v")
        logger.debug("tag", "d")
        logger.info("tag", "i")
        logger.warn("tag", "w")
        logger.warn("tag", "w", IllegalStateException("boom"))
        logger.error("tag", "e")
        logger.error("tag", "e", IllegalStateException("boom"))
    }
}
