package com.whatsappv2.di

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
 * `:domain` may import Android at all. This binding is the whole of that arrangement.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {

    @Binds
    @Singleton
    abstract fun bindCallRegistry(registry: TelecomCallRegistry): PlatformCallRegistry
}
