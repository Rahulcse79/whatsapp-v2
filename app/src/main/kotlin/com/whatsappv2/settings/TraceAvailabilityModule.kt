package com.whatsappv2.settings

import com.whatsappv2.feature.settings.TraceAvailability
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Answers whether this build may offer SIP tracing.
 *
 * The value comes from [TraceCapability], which is a build-type source set - so the
 * release binary carries the constant `false` and the toggle is compiled out, rather than
 * being hidden by a runtime check that could be flipped (§7, DoD 12).
 */
@Module
@InstallIn(SingletonComponent::class)
object TraceAvailabilityModule {

    @Provides
    @Singleton
    fun provideTraceAvailability() = TraceAvailability { TraceCapability.IS_AVAILABLE }
}
