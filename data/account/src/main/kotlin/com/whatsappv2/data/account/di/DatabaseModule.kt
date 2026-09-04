package com.whatsappv2.data.account.di

import android.content.Context
import androidx.room.Room
import com.whatsappv2.data.account.db.SipAccountDao
import com.whatsappv2.data.account.db.SipAccountDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Builds the account database. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SipAccountDatabase =
        Room.databaseBuilder(context, SipAccountDatabase::class.java, SipAccountDatabase.NAME)
            .addMigrations(*SipAccountDatabase.MIGRATIONS)
            // No fallbackToDestructiveMigration: it would silently delete every account
            // and credential on a schema change, on exactly the users who upgrade first.
            // A missing migration must fail loudly instead.
            .build()

    @Provides
    fun provideSipAccountDao(database: SipAccountDatabase): SipAccountDao =
        database.sipAccountDao()
}
