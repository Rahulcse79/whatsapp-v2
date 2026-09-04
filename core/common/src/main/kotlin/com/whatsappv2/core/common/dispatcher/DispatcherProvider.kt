package com.whatsappv2.core.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over [Dispatchers] so code under test can run on a controlled scheduler.
 *
 * Referencing [Dispatchers.IO] directly makes a class untestable without real threads
 * and wall-clock waits; injecting this keeps every test deterministic (§8).
 */
interface DispatcherProvider {
    /** UI thread. Only ViewModels and Compose-facing code should need it. */
    val main: CoroutineDispatcher

    /** Blocking I/O: disk, database, network sockets. */
    val io: CoroutineDispatcher

    /** CPU-bound work: parsing, encryption, codec setup. */
    val default: CoroutineDispatcher

    /** Runs in the caller's thread until first suspension. Use sparingly. */
    val unconfined: CoroutineDispatcher
}

/** The production implementation, backed by [Dispatchers]. */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}
