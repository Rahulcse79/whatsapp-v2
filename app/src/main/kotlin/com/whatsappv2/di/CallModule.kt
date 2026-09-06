package com.whatsappv2.di

import com.whatsappv2.call.AndroidCameraAvailability
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.PlatformCallRegistry
import com.whatsappv2.telecom.TelecomCallRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The platform half of calling.
 *
 * `:data:sip` asks for a [PlatformCallRegistry] and `:app` is the only module allowed to
 * answer with Telecom — the engine may not import `android.telecom` any more than
 * `:domain` may import Android at all. The camera binding below has the same shape.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {

    @Binds
    @Singleton
    abstract fun bindCallRegistry(registry: TelecomCallRegistry): PlatformCallRegistry

    /**
     * The other half of the same arrangement (Task 51).
     *
     * Whether a camera exists and may be used is an Android question, so `:domain` states
     * the contract and `:app` answers it — the same shape as the registry above, and for
     * the same reason.
     */
    @Binds
    @Singleton
    abstract fun bindCameraAvailability(availability: AndroidCameraAvailability): CameraAvailability
}
