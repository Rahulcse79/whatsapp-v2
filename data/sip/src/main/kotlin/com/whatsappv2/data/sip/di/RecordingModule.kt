package com.whatsappv2.data.sip.di

import com.whatsappv2.data.sip.recording.PjsipCallRecorder
import com.whatsappv2.data.sip.recording.RecordingStore
import com.whatsappv2.data.sip.recording.stack.EncryptedRecordingStore
import com.whatsappv2.data.sip.recording.stack.MediaPlayerRecordingPlayer
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.RecordingPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds call recording (Task 58).
 *
 * Two bindings, and the split is the point: [CallRecorder] is what the app can reach, and
 * it can only start a recording by presenting a consent. [RecordingStore] is not exposed
 * outside `:data:sip` at all, because it is the only thing that knows where a recording
 * lives — and a file path that escapes this module is a path something can copy from.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecordingModule {

    @Binds
    @Singleton
    abstract fun bindCallRecorder(recorder: PjsipCallRecorder): CallRecorder

    @Binds
    @Singleton
    abstract fun bindRecordingStore(store: EncryptedRecordingStore): RecordingStore

    /**
     * Playback, behind the same seam as recording: a screen can ask for a recording to be
     * played and can never learn where its bytes are, decrypted or otherwise.
     */
    @Binds
    @Singleton
    abstract fun bindRecordingPlayer(player: MediaPlayerRecordingPlayer): RecordingPlayer
}
