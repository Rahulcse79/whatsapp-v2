package com.whatsappv2.di

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.domain.engine.PlatformCallRegistry
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.telecom.TelecomCallRegistry
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
import kotlin.test.assertIs
import kotlin.test.assertSame
import com.whatsappv2.core.common.logging.Logger as AppLogger

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

    @Inject
    lateinit var callRegistry: PlatformCallRegistry

    @Inject
    lateinit var calls: SipCallController

    @Inject
    lateinit var media: SipMediaController

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

    @Test
    fun `the calling path resolves to Telecom rather than to nothing`() {
        // The engine asks for a PlatformCallRegistry before every INVITE (Task 35). A
        // missing binding here is a call that cannot be placed, and Dagger would only say
        // so at run time on a device - which is exactly the failure this module exists to
        // catch on the JVM.
        assertIs<TelecomCallRegistry>(callRegistry)
    }

    @Test
    fun `the call and media controllers are the same engine`() {
        // Role interfaces for interface segregation, one singleton behind them: two
        // engines would mean a call placed on one and muted on the other.
        //
        // The type argument is explicit because the two references have no common
        // supertype but `Any`, and identity is exactly what is being asserted — that both
        // roles resolve to one object, whatever its type.
        assertSame<Any>(calls, media)
    }
}
