package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.config.RateLimitProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SubmissionRateLimitFilterTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-03-09T00:00:00Z"), ZoneOffset.UTC)
    private val filter = SubmissionRateLimitFilter(
        properties = RateLimitProperties(enabled = true, submitRequests = 1, windowSeconds = 60),
        clock = fixedClock,
    )

    @Test
    fun `second submit request from same ip returns 429`() {
        val chain = RecordingChain()

        val first = exchange("203.0.113.10")
        filter.filter(first, chain).block()
        assertEquals(1, chain.called)

        val second = exchange("203.0.113.10")
        filter.filter(second, chain).block()

        assertEquals(1, chain.called)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.response.statusCode)
        assertEquals("60", second.response.headers.getFirst("Retry-After"))
    }

    @Test
    fun `different client ip has independent rate limit counter`() {
        val chain = RecordingChain()

        filter.filter(exchange("203.0.113.10"), chain).block()
        filter.filter(exchange("203.0.113.11"), chain).block()

        assertEquals(2, chain.called)
    }

    private fun exchange(ip: String): ServerWebExchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/submissions")
                .header("X-Forwarded-For", ip)
                .build()
        )

    private class RecordingChain : WebFilterChain {
        var called: Int = 0

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            called += 1
            return Mono.empty()
        }
    }
}
