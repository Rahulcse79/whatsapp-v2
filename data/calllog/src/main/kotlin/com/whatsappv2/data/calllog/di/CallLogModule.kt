package com.whatsappv2.data.calllog.di

import android.content.Context
import androidx.room.Room
import com.whatsappv2.data.calllog.CallLogRepositoryImpl
import com.whatsappv2.data.calllog.db.CallLogDao
import com.whatsappv2.data.calllog.db.CallLogDatabase
import com.whatsappv2.domain.repository.CallLogRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the call log repository to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallLogModule {

    @Binds
    @Singleton
    abstract fun bindCallLogRepository(impl: CallLogRepositoryImpl): CallLogRepository

    companion object {

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): CallLogDatabase =
            Room.databaseBuilder(context, CallLogDatabase::class.java, CallLogDatabase.NAME)
                .apply { CallLogDatabase.MIGRATIONS.forEach(::addMigrations) }
                // No fallbackToDestructiveMigration: history that vanishes on upgrade is
                // indistinguishable from a bug, and the user cannot get it back.
                .build()

        @Provides
        fun provideCallLogDao(database: CallLogDatabase): CallLogDao = database.callLogDao()
    }
}
