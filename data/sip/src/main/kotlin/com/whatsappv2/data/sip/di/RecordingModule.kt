package com.whatsappv2.data.sip.di

import com.whatsappv2.data.sip.recording.LinphoneCallRecorder
import com.whatsappv2.data.sip.recording.RecordingStore
import com.whatsappv2.data.sip.recording.stack.EncryptedRecordingStore
import com.whatsappv2.domain.recording.CallRecorder
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
    abstract fun bindCallRecorder(recorder: LinphoneCallRecorder): CallRecorder

    @Binds
    @Singleton
    abstract fun bindRecordingStore(store: EncryptedRecordingStore): RecordingStore
}
