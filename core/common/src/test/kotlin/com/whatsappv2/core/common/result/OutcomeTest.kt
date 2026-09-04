package com.whatsappv2.core.common.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeTest {

    private val ok: Outcome<Int, String> = Outcome.Success(2)
    private val err: Outcome<Int, String> = Outcome.Failure("boom")

    @Test
    fun `success reports isSuccess and exposes its value`() {
        assertTrue(ok.isSuccess)
        assertFalse(ok.isFailure)
        assertEquals(2, ok.getOrNull())
        assertNull(ok.errorOrNull())
    }

    @Test
    fun `failure reports isFailure and exposes its error`() {
        assertTrue(err.isFailure)
        assertFalse(err.isSuccess)
        assertNull(err.getOrNull())
        assertEquals("boom", err.errorOrNull())
    }

    @Test
    fun `map transforms a success`() {
        assertEquals(Outcome.Success(4), ok.map { it * 2 })
    }

    @Test
    fun `map leaves a failure untouched and does not run the transform`() {
        var ran = false
        val result = err.map {
            ran = true
            it * 2
        }
        assertEquals(Outcome.Failure("boom"), result)
        assertFalse(ran, "transform must not run on a failure")
    }

    @Test
    fun `mapError transforms a failure`() {
        assertEquals(Outcome.Failure(5), err.mapError { it.length })
    }

    @Test
    fun `mapError leaves a success untouched`() {
        assertEquals(Outcome.Success(2), ok.mapError { it.length })
    }

    @Test
    fun `flatMap chains a second fallible operation`() {
        assertEquals(Outcome.Success("2"), ok.flatMap { Outcome.Success(it.toString()) })
    }

    @Test
    fun `flatMap short-circuits on the first failure`() {
        var ran = false
        val result = err.flatMap {
            ran = true
            Outcome.Success(it.toString())
        }
        assertEquals(Outcome.Failure("boom"), result)
        assertFalse(ran, "flatMap must short-circuit on a failure")
    }

    @Test
    fun `flatMap propagates a failure raised by the transform`() {
        val result: Outcome<String, String> = ok.flatMap { Outcome.Failure("inner") }
        assertEquals(Outcome.Failure("inner"), result)
    }

    @Test
    fun `fold collapses both branches`() {
        assertEquals("v2", ok.fold(onSuccess = { "v$it" }, onFailure = { "e$it" }))
        assertEquals("eboom", err.fold(onSuccess = { "v$it" }, onFailure = { "e$it" }))
    }

    @Test
    fun `getOrElse returns the fallback only on failure`() {
        assertEquals(2, ok.getOrElse { -1 })
        assertEquals(-1, err.getOrElse { -1 })
    }

    @Test
    fun `getOrElse gives the fallback access to the error`() {
        assertEquals(4, err.getOrElse { it.length - 1 })
    }

    @Test
    fun `onSuccess runs only on success and returns the receiver`() {
        var seen: Int? = null
        assertEquals(ok, ok.onSuccess { seen = it })
        assertEquals(2, seen)

        seen = null
        assertEquals(err, err.onSuccess { seen = it })
        assertNull(seen)
    }

    @Test
    fun `onFailure runs only on failure and returns the receiver`() {
        var seen: String? = null
        assertEquals(err, err.onFailure { seen = it })
        assertEquals("boom", seen)

        seen = null
        assertEquals(ok, ok.onFailure { seen = it })
        assertNull(seen)
    }

    @Test
    fun `success and failure factories build the matching variants`() {
        assertEquals(Outcome.Success(7), success(7))
        assertEquals(Outcome.Failure("x"), failure("x"))
    }

    @Test
    fun `operations compose across several steps`() {
        val result = success(10)
            .map { it + 5 }
            .flatMap { if (it > 0) success(it) else failure("negative") }
            .map { it.toString() }
        assertEquals(Outcome.Success("15"), result)
    }
}
