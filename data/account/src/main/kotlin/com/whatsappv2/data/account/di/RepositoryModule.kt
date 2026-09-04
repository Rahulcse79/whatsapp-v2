package com.whatsappv2.data.account.di

import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.core.common.time.SystemClock
import com.whatsappv2.data.account.SipAccountRepositoryImpl
import com.whatsappv2.domain.repository.SipAccountRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the account repository to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSipAccountRepository(impl: SipAccountRepositoryImpl): SipAccountRepository

    companion object {
        /**
         * The real clock.
         *
         * Injected rather than called statically so a test can pin creation timestamps
         * and assert ordering exactly, instead of asserting a range.
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = SystemClock
    }
}
