package com.whatsappv2.core.common.dispatcher

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame

class DispatcherProviderTest {

    private val provider = DefaultDispatcherProvider()

    @Test
    fun `io maps to Dispatchers IO`() {
        assertSame(Dispatchers.IO, provider.io)
    }

    @Test
    fun `default maps to Dispatchers Default`() {
        assertSame(Dispatchers.Default, provider.default)
    }

    @Test
    fun `unconfined maps to Dispatchers Unconfined`() {
        assertSame(Dispatchers.Unconfined, provider.unconfined)
    }

    // `main` is deliberately not asserted here: Dispatchers.Main has no implementation
    // on a plain JVM test classpath and throws on access. It is exercised in the
    // Android-side tests that have a main looper.
}
