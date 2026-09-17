package com.whatsappv2.di

import com.whatsappv2.domain.engine.ConferenceRoom
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Where the conference bridge's address is decided (ADR-003).
 *
 * One binding, in `:app`, because the room is a property of the deployment this build
 * talks to and nothing below `:app` should have an opinion about it. A different server
 * changes this line; a test supplies its own [ConferenceRoom] and never reaches here.
 *
 * [ConferenceRoom.DEFAULT] is extension `3000`, verified live on the reference FreeSWITCH
 * rather than assumed — see the type's own documentation for what was checked and why the
 * profile behind the number matters as much as the number.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConferenceModule {

    @Provides
    @Singleton
    fun provideConferenceRoom(): ConferenceRoom = ConferenceRoom.DEFAULT
}
