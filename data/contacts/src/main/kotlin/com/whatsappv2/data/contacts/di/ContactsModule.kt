package com.whatsappv2.data.contacts.di

import com.whatsappv2.data.contacts.ContactsContractRepository
import com.whatsappv2.domain.contacts.ContactRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds contact resolution to the device address book. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContactsModule {

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactsContractRepository): ContactRepository
}
