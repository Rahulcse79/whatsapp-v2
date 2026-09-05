package com.whatsappv2.di

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A scope that lives as long as the process.
 *
 * For work that outlives whatever started it: a notification action must still answer the
 * call after its broadcast returns, and a coordinator watching the engine has no screen to
 * belong to. `SupervisorJob`, so one failure does not cancel every other user of it.
 *
 * Deliberately not for anything a ViewModel could own — `viewModelScope` cancels with the
 * screen, which is what a screen's work should do.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppCoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)
}
