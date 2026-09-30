package com.whatsappv2.data.chat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.gson.Gson
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.chat.ChatContactRepositoryImpl
import com.whatsappv2.data.chat.ChatSessionRepositoryImpl
import com.whatsappv2.data.chat.net.BearerTokenSource
import com.whatsappv2.data.chat.store.ChatTokenFile
import com.whatsappv2.data.chat.store.PrivateChatTokenFile
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * This module's own preferences file.
 *
 * A qualifier rather than a second unqualified `DataStore<Preferences>`, which would be a
 * duplicate binding and a build failure — `:data:settings` already provides one. Two files
 * on purpose: sharing would let a sign-out touch a user's theme, and would put a chat
 * identity in a store whose stated contract is "app preferences, nothing sensitive".
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ChatSessionPreferences

/** Binds the chat identity and the company directory to their Coral-platform implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatDataModule {

    @Binds
    @Singleton
    abstract fun bindChatSessionRepository(impl: ChatSessionRepositoryImpl): ChatSessionRepository

    /**
     * The same instance, under its second role.
     *
     * The repository is what knows the live token, and an OkHttp interceptor has to read
     * one synchronously. Binding the singleton twice is what keeps that one object rather
     * than two views of a session that can disagree.
     */
    @Binds
    @Singleton
    abstract fun bindBearerTokenSource(impl: ChatSessionRepositoryImpl): BearerTokenSource

    @Binds
    @Singleton
    abstract fun bindChatContactRepository(impl: ChatContactRepositoryImpl): ChatContactRepository

    companion object {

        private const val PREFERENCES_FILE = "chat-session"

        /** Under `files/chat/`, so the credential is not loose beside unrelated app data. */
        private const val TOKEN_PATH = "chat/token.enc"

        @Provides
        @Singleton
        @ChatSessionPreferences
        fun provideChatPreferences(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(PREFERENCES_FILE)
        }

        @Provides
        @Singleton
        fun provideChatTokenFile(
            @ApplicationContext context: Context,
            logger: Logger,
        ): ChatTokenFile = PrivateChatTokenFile(File(context.filesDir, TOKEN_PATH), logger)

        /**
         * This module's own Gson.
         *
         * Not shared with the chat SDK's, which is private inside `ChatSdk` and
         * unreachable by design. Default configuration: the platform writes plain JSON and
         * a custom date format or naming policy applied here would silently reshape a
         * field name the server chose.
         */
        @Provides
        @Singleton
        fun provideChatGson(): Gson = Gson()
    }
}
