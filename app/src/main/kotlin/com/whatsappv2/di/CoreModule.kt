package com.whatsappv2.di

import com.whatsappv2.core.common.dispatcher.DefaultDispatcherProvider
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.logging.PlatformLogger
import com.whatsappv2.permission.PermissionRequestTracker
import com.whatsappv2.permission.SharedPreferencesPermissionTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for the cross-cutting primitives from `:core:common`.
 *
 * [PlatformLogger] resolves to a different class per build variant — `src/debug`
 * logs everything, `src/release` has empty verbose and debug bodies. The choice is
 * made by the source set at compile time, so this module needs no branch on
 * `BuildConfig.DEBUG` and the release binary carries no debug logging path (§7).
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideLogger(): Logger = PlatformLogger()

    @Provides
    @Singleton
    fun providePermissionRequestTracker(
        @ApplicationContext context: Context,
    ): PermissionRequestTracker = SharedPreferencesPermissionTracker(context)
}
