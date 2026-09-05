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
 *
 * ## Two views of the same calls
 *
 * [addedAccounts] is the test's own **recording** of every call, kept for good. [held] is
 * what the stack would still be holding right now, and it is emptied by `removeAccount`
 * and `stop` exactly as the real core's account list and auth store are. Task 29 needs
 * the second: "no decrypted password remains after logout" is a question about what is
 * still held, and an append-only log could never answer it.
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

    private val held = mutableMapOf<String, StackAccount>()

    /**
     * The accounts, and therefore the credentials, the stack is still holding.
     *
     * Stands in for the real core's account list plus its auth store, which is where a
     * decrypted password actually lives once a REGISTER has been built.
     */
    val heldAccounts: Map<String, StackAccount> get() = held.toMap()
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun start() {
        startCount++
    }

    override fun addAccount(account: StackAccount) {
        addedAccounts += account
        held[account.key] = account
    }

    override fun removeAccount(accountKey: String) {
        removedKeys += accountKey
        // The credentials go with the account, which is what the seam's contract requires
        // and what the real gateway does by removing the core's matching auth info.
        held -= accountKey
    }

    override fun refreshAccount(accountKey: String) {
        refreshedKeys += accountKey
    }

    override fun stop() {
        stopCount++
        held.clear()
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
