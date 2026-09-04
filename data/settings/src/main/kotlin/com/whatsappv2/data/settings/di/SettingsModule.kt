package com.whatsappv2.data.settings.di

import com.whatsappv2.data.settings.DataStoreAppSettingsRepository
import com.whatsappv2.domain.repository.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds app preferences to their DataStore implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(
        impl: DataStoreAppSettingsRepository,
    ): AppSettingsRepository
}
