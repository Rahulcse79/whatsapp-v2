package com.whatsappv2.di

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger as AppLogger
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.test.assertSame

/**
 * Proves the Hilt graph actually resolves, on the JVM.
 *
 * A missing binding is a run-time failure in Dagger's generated code, so a build that
 * compiles says nothing about whether the app can start. Robolectric lets that be
 * caught in ordinary CI instead of on a device.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [ROBOLECTRIC_SDK])
class HiltGraphTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var logger: AppLogger

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `graph provides a Logger`() {
        // lateinit would have thrown on access had the binding been missing.
        assertSame(logger, logger)
    }

    @Test
    fun `graph provides a DispatcherProvider backed by the real dispatchers`() {
        assertSame(Dispatchers.IO, dispatchers.io)
        assertSame(Dispatchers.Default, dispatchers.default)
    }
}
