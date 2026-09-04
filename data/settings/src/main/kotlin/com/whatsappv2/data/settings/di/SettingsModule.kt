package com.whatsappv2.data.settings.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.whatsappv2.data.settings.DataStoreAppSettingsRepository
import com.whatsappv2.domain.repository.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {
        private const val FILE_NAME = "app-settings"

        /**
         * The preferences store.
         *
         * Provided here rather than through a `by preferencesDataStore` delegate on
         * Context: the delegate ties the store to a single global location, which makes
         * the repository impossible to point at a different file - and in tests, where
         * Robolectric reuses the application, made writes leak between cases.
         */
        @Provides
        @Singleton
        fun providePreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(FILE_NAME)
        }
    }
}
