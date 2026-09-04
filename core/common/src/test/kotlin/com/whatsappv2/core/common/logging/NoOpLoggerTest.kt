package com.whatsappv2.core.common.logging

import kotlin.test.Test

class NoOpLoggerTest {

    @Test
    fun `discards every level without throwing`() {
        val logger: Logger = NoOpLogger
        logger.verbose("tag", "message")
        logger.debug("tag", "message")
        logger.info("tag", "message")
        logger.warn("tag", "message")
        logger.warn("tag", "message", IllegalStateException("boom"))
        logger.error("tag", "message")
        logger.error("tag", "message", IllegalStateException("boom"))
    }
}
