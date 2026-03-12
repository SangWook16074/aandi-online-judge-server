package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxInput
import com.aandiclub.online.judge.sandbox.SandboxOutput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "judge.rate-limit.enabled=false",
        "judge.jwt-auth.enabled=false",
    ],
)
class SubmissionWorkflowE2ETest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var submissionRepository: SubmissionRepository

    @MockkBean
    private lateinit var sandboxRunner: SandboxRunner

    @MockkBean
    private lateinit var redisTemplate: ReactiveStringRedisTemplate

    @MockkBean
    private lateinit var listenerContainer: ReactiveRedisMessageListenerContainer

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setup() {
        submissionRepository.deleteAll().block()
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)
        every { listenerContainer.receive(any<ChannelTopic>()) } returns Flux.empty()
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
    }

    @Test
    fun `post submission triggers worker and updates mongodb status`() {
        coEvery { sandboxRunner.run(Language.PYTHON, any<SandboxInput>()) } returns SandboxOutput(
            status = TestCaseStatus.PASSED,
            output = "8",
            error = null,
            timeMs = 1.2,
            memoryMb = 2.4,
        )

        val accepted = submitSamplePython()
        val completed = awaitCompleted(accepted.submissionId)

        assertEquals(SubmissionStatus.ACCEPTED, completed.status)
        assertEquals(1, completed.testCaseResults.size)
        assertEquals(TestCaseStatus.PASSED, completed.testCaseResults.first().status)
        assertEquals("8", completed.testCaseResults.first().output)
    }

    @Test
    fun `three concurrent submissions are processed in parallel`() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        coEvery { sandboxRunner.run(Language.PYTHON, any<SandboxInput>()) } coAnswers {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { prev -> max(prev, now) }
            try {
                delay(300)
                SandboxOutput(
                    status = TestCaseStatus.PASSED,
                    output = "8",
                    error = null,
                    timeMs = 5.0,
                    memoryMb = 3.0,
                )
            } finally {
                active.decrementAndGet()
            }
        }

        val ids = (1..3).map { submitSamplePython().submissionId }
        val completed = ids.map { awaitCompleted(it) }

        assertTrue(completed.all { it.status == SubmissionStatus.ACCEPTED })
        assertTrue(maxActive.get() >= 2, "expected parallel execution but maxActive=${maxActive.get()}")
    }

    private fun submitSamplePython(): SubmissionAccepted =
        webTestClient.post()
            .uri("/v1/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "problemId": "quiz-101",
                  "language": "PYTHON",
                  "code": "def solution(a,b): return a+b"
                }
                """.trimIndent()
            )
            .exchange()
            .expectStatus().isAccepted
            .expectBody(SubmissionAccepted::class.java)
            .returnResult()
            .responseBody
            ?: error("missing SubmissionAccepted response")

    private fun awaitCompleted(submissionId: String, timeoutMs: Long = 10_000): Submission {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = submissionRepository.findById(submissionId).block()
            if (current != null &&
                current.status != SubmissionStatus.PENDING &&
                current.status != SubmissionStatus.RUNNING
            ) {
                return current
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for completion: $submissionId")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo: MongoDBContainer = MongoDBContainer("mongo:8.0")
    }
}
