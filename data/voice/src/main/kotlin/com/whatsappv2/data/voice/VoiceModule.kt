package com.whatsappv2.data.voice

import com.whatsappv2.domain.voice.SpeakerEmbedder
import com.whatsappv2.domain.voice.VoiceEnrolment
import com.whatsappv2.domain.voice.VoiceProfileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the voice profile's implementations to the ports `:domain` declares (ADR-013). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun profileRepository(store: FileVoiceProfileStore): VoiceProfileRepository

    @Binds
    @Singleton
    abstract fun speakerEmbedder(embedder: EcapaEmbedder): SpeakerEmbedder

    @Binds
    @Singleton
    abstract fun enrolment(enroller: VoiceEnroller): VoiceEnrolment
}
