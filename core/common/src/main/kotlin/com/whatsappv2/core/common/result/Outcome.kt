package com.whatsappv2.core.common.result

/**
 * A result that is either a [Success] carrying a value or a [Failure] carrying a typed
 * error.
 *
 * Named `Outcome` rather than `Result` on purpose: `kotlin.Result` is imported by
 * default in every file, so a same-named type would shadow it and force awkward
 * fully-qualified references at call sites.
 *
 * This is the return type at every layer boundary (§4.2). Errors are values, not
 * exceptions: a caller cannot forget to handle a `Failure` the way it can forget a
 * `catch`, and the error type is visible in the signature.
 */
sealed interface Outcome<out T, out E> {

    data class Success<out T>(val value: T) : Outcome<T, Nothing>

    data class Failure<out E>(val error: E) : Outcome<Nothing, E>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

/** The value, or `null` when this is a [Outcome.Failure]. */
fun <T, E> Outcome<T, E>.getOrNull(): T? = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> null
}

/** The error, or `null` when this is a [Outcome.Success]. */
fun <T, E> Outcome<T, E>.errorOrNull(): E? = when (this) {
    is Outcome.Success -> null
    is Outcome.Failure -> error
}

/** The value, or [fallback] when this is a [Outcome.Failure]. */
fun <T, E> Outcome<T, E>.getOrElse(fallback: (E) -> @UnsafeVariance T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> fallback(error)
}

/** Transforms a success value, leaving a failure untouched. */
inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

/** Transforms a failure's error, leaving a success untouched. */
inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Success -> this
    is Outcome.Failure -> Outcome.Failure(transform(error))
}

/** Chains another operation that can itself fail. Short-circuits on the first failure. */
inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, @UnsafeVariance E>): Outcome<R, E> =
    when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Failure -> this
    }

/** Collapses both branches into a single value. */
inline fun <T, E, R> Outcome<T, E>.fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

/** Runs [action] on success and returns this unchanged, for side effects such as logging. */
inline fun <T, E> Outcome<T, E>.onSuccess(action: (T) -> Unit): Outcome<T, E> = apply {
    if (this is Outcome.Success) action(value)
}

/** Runs [action] on failure and returns this unchanged, for side effects such as logging. */
inline fun <T, E> Outcome<T, E>.onFailure(action: (E) -> Unit): Outcome<T, E> = apply {
    if (this is Outcome.Failure) action(error)
}

/** Wraps [value] in a [Outcome.Success]. */
fun <T> success(value: T): Outcome<T, Nothing> = Outcome.Success(value)

/** Wraps [error] in a [Outcome.Failure]. */
fun <E> failure(error: E): Outcome<Nothing, E> = Outcome.Failure(error)
