package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.CoralServerUrl
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * One client per origin, and a new one the moment the origin changes.
 *
 * Both halves are bugs that only appear on the *second* use, which is why they are asserted
 * rather than assumed. The paths these clients call are covered by [CoralApiPathTest], and
 * the header they attach by [BearerInterceptorTest]; this is about the object's lifetime.
 */
class CoralClientFactoryTest {

    private val factory = CoralClientFactory(Gson()) {
        object : BearerTokenSource {
            override fun currentToken(): String? = null
        }
    }

    private fun url(origin: String): CoralServerUrl =
        (CoralServerUrl.parse(origin) as Outcome.Success).value

    @Test
    fun `the same origin gets the same client`() {
        // An OkHttpClient owns a connection pool and two thread pools. Building one per
        // request is the standard way to exhaust a device's file descriptors.
        val first = factory.apiFor(url("https://gujlogin.coraltele.com"))

        assertSame(first, factory.apiFor(url("https://gujlogin.coraltele.com")))
    }

    @Test
    fun `a changed origin gets a new client rather than the old host`() {
        // The origin is a value the user types, so it changes inside a running process -
        // at sign-in, and again at the next one after a sign-out. A client cached for the
        // life of the process would keep talking to the previous host, with no symptom but
        // a connection refused.
        val first = factory.apiFor(url("https://gujlogin.coraltele.com"))

        assertNotSame(first, factory.apiFor(url("https://elsewhere.example")))
    }

    @Test
    fun `switching back again rebuilds rather than resurrecting the first client`() {
        // One cached entry, not a map: a map keyed by origin would hold a connection pool
        // per host the user ever typed, for the life of the process.
        val first = factory.apiFor(url("https://gujlogin.coraltele.com"))
        factory.apiFor(url("https://elsewhere.example"))

        assertNotSame(first, factory.apiFor(url("https://gujlogin.coraltele.com")))
    }

    @Test
    fun `a port is part of the identity, so two ports on one host are two clients`() {
        val plain = factory.apiFor(url("https://host.example"))

        assertNotSame(plain, factory.apiFor(url("https://host.example:8443")))
    }
}
