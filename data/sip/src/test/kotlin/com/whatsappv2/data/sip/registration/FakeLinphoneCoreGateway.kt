package com.whatsappv2.data.sip.registration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A [LinphoneCoreGateway] with no SIP stack behind it.
 *
 * Exists so the callback-to-Flow mapping can be exercised on the JVM. liblinphone cannot
 * run there, so without this the mapping could only be tested on a device - which is the
 * same as untested.
 *
 * It records what it was asked to do, so a test can assert that `removeAccount` was
 * called before the account was forgotten, and emits whatever states a test scripts.
 */
internal class FakeLinphoneCoreGateway : LinphoneCoreGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    val addedAccounts: MutableList<StackAccount> = mutableListOf()
    val removedKeys: MutableList<String> = mutableListOf()
    val refreshedKeys: MutableList<String> = mutableListOf()
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun start() {
        startCount++
    }

    override fun addAccount(account: StackAccount) {
        addedAccounts += account
    }

    override fun removeAccount(accountKey: String) {
        removedKeys += accountKey
    }

    override fun refreshAccount(accountKey: String) {
        refreshedKeys += accountKey
    }

    override fun stop() {
        stopCount++
    }

    /** Emits a state change as the stack would. */
    fun emit(
        accountKey: String,
        state: StackRegistrationState,
        statusCode: Int? = null,
        message: String? = null,
    ) {
        events.tryEmit(StackRegistrationEvent(accountKey, state, statusCode, message))
    }

    private companion object {
        const val BUFFER = 64
    }
}
