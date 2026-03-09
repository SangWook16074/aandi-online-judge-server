package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

class SubmissionControllerWebFluxTest {

    private val submissionService = mockk<SubmissionService>()
    private val webTestClient = WebTestClient
        .bindToController(SubmissionController(submissionService))
        .build()

    @Test
    fun `stream endpoint emits SSE events via WebTestClient`() {
        val caseEvent = ServerSentEvent.builder<String>()
            .event("test_case_result")
            .data("""{"caseId":1,"status":"PASSED","timeMs":1.1,"memoryMb":2.2}""")
            .build()
        val doneEvent = ServerSentEvent.builder<String>()
            .event("done")
            .data("""{"event":"done","submissionId":"sub-1","overallStatus":"ACCEPTED"}""")
            .build()
        every { submissionService.streamResults("sub-1") } returns flowOf(caseEvent, doneEvent)

        val events = webTestClient.get()
            .uri("/v1/submissions/sub-1/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(2))

        assertNotNull(events)
        assertEquals(2, events!!.size)
        assertEquals("test_case_result", events[0].event())
        assertEquals("done", events[1].event())
    }

    @Test
    fun `multiple clients can subscribe to same submission stream`() {
        val event = ServerSentEvent.builder<String>()
            .event("test_case_result")
            .data("""{"caseId":1,"status":"PASSED"}""")
            .build()
        val done = ServerSentEvent.builder<String>()
            .event("done")
            .data("""{"event":"done","submissionId":"sub-2","overallStatus":"ACCEPTED"}""")
            .build()
        every { submissionService.streamResults("sub-2") } returnsMany listOf(
            flowOf(event, done),
            flowOf(event, done),
        )

        val firstClientEvents = webTestClient.get()
            .uri("/v1/submissions/sub-2/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(2))

        val secondClientEvents = webTestClient.get()
            .uri("/v1/submissions/sub-2/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(2))

        assertNotNull(firstClientEvents)
        assertNotNull(secondClientEvents)
        assertEquals("test_case_result", firstClientEvents!![0].event())
        assertEquals("test_case_result", secondClientEvents!![0].event())
    }
}
