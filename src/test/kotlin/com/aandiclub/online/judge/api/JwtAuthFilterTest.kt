package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.config.JwtAuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtAuthFilterTest {
    private val signingKey = "test-signing-key"
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-12T00:00:00Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()
    private val filter = JwtAuthFilter(
        properties = JwtAuthProperties(
            enabled = true,
            signingKey = signingKey,
            requiredRole = "USER",
            allowWithoutRoleClaim = true,
        ),
        objectMapper = objectMapper,
        clock = fixedClock,
    )

    @Test
    fun `missing bearer header returns 401`() {
        val chain = RecordingChain()
        val exchange = exchange(path = "/v1/submissions")

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `invalid signature returns 401`() {
        val chain = RecordingChain()
        val exchange = exchange(
            path = "/v1/submissions",
            authorization = "Bearer ${jwtToken(payload = mapOf("sub" to "user-1"), signingKey = "wrong-key")}",
        )

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `malformed jwt returns 401`() {
        val chain = RecordingChain()
        val exchange = exchange(
            path = "/v1/submissions",
            authorization = "Bearer not-a-jwt",
        )

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `expired jwt returns 401`() {
        val token = jwtToken(
            payload = mapOf(
                "sub" to "user-1",
                "exp" to Instant.parse("2026-03-11T23:59:59Z").epochSecond,
                "role" to "USER",
            )
        )
        val chain = RecordingChain()
        val exchange = exchange(path = "/v1/submissions", authorization = "Bearer $token")

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `valid jwt passes through`() {
        val token = jwtToken(
            payload = mapOf(
                "sub" to "user-1",
                "exp" to Instant.parse("2026-03-13T00:00:00Z").epochSecond,
                "role" to "ADMIN",
            )
        )
        val chain = RecordingChain()
        val exchange = exchange(path = "/v1/submissions", authorization = "Bearer $token")

        filter.filter(exchange, chain).block()

        assertEquals(1, chain.called)
        assertEquals(null, exchange.response.statusCode)
    }

    @Test
    fun `jwt without role claim is allowed when configured`() {
        val token = jwtToken(
            payload = mapOf(
                "sub" to "user-2",
                "exp" to Instant.parse("2026-03-13T00:00:00Z").epochSecond,
            )
        )
        val chain = RecordingChain()
        val exchange = exchange(path = "/v1/submissions", authorization = "Bearer $token")

        filter.filter(exchange, chain).block()

        assertEquals(1, chain.called)
        assertEquals(null, exchange.response.statusCode)
    }

    @Test
    fun `guest role is denied for USER minimum`() {
        val token = jwtToken(
            payload = mapOf(
                "sub" to "guest-1",
                "exp" to Instant.parse("2026-03-13T00:00:00Z").epochSecond,
                "role" to "GUEST",
            )
        )
        val chain = RecordingChain()
        val exchange = exchange(path = "/v1/submissions", authorization = "Bearer $token")

        filter.filter(exchange, chain).block()

        assertEquals(0, chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    private fun exchange(path: String, authorization: String? = null): ServerWebExchange {
        val builder = MockServerHttpRequest.post(path)
        if (authorization != null) {
            builder.header("Authorization", authorization)
        }
        return MockServerWebExchange.from(builder.build())
    }

    private fun jwtToken(
        header: Map<String, Any> = mapOf("alg" to "HS256", "typ" to "JWT"),
        payload: Map<String, Any>,
        signingKey: String = this.signingKey,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val headerPart = encoder.encodeToString(objectMapper.writeValueAsBytes(header))
        val payloadPart = encoder.encodeToString(objectMapper.writeValueAsBytes(payload))
        val signingInput = "$headerPart.$payloadPart"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(), "HmacSHA256"))
        val signature = encoder.encodeToString(mac.doFinal(signingInput.toByteArray()))
        return "$signingInput.$signature"
    }

    private class RecordingChain : WebFilterChain {
        var called: Int = 0

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            called += 1
            return Mono.empty()
        }
    }
}
