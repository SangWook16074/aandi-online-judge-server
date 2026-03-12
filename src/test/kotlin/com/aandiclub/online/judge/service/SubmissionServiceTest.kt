package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.worker.JudgeWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class SubmissionServiceTest {

    private val submissionRepository = mockk<SubmissionRepository>()
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val listenerContainer = mockk<ReactiveRedisMessageListenerContainer>()
    private val judgeWorker = mockk<JudgeWorker>(relaxed = true)
    private val judgeWorkerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val judgeWorkerSemaphore = Semaphore(2)
    private val objectMapper = ObjectMapper()

    private val service = SubmissionService(
        submissionRepository,
        redisTemplate,
        listenerContainer,
        judgeWorker,
        judgeWorkerScope,
        judgeWorkerSemaphore,
        objectMapper,
    )

    // ── createSubmission ──────────────────────────────────────────────────

    @Test
    fun `createSubmission saves submission and returns SubmissionAccepted`() = runTest {
        val request = SubmissionRequest(
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b):\n    return a + b",
        )
        val savedSubmission = Submission(
            id = "saved-uuid",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b):\n    return a + b",
        )

        val savedSlot = slot<Submission>()
        every { submissionRepository.save(capture(savedSlot)) } returns Mono.just(savedSubmission)

        val result = service.createSubmission(request)

        assertEquals("saved-uuid", result.submissionId)
        assertEquals("/v1/submissions/saved-uuid/stream", result.streamUrl)

        verify(exactly = 1) { submissionRepository.save(any()) }
        coVerify(timeout = 1_000, exactly = 1) { judgeWorker.execute(match { it.id == "saved-uuid" }, any()) }
        val captured = savedSlot.captured
        assertEquals("quiz-101", captured.problemId)
        assertEquals(Language.PYTHON, captured.language)
        assertEquals(SubmissionStatus.PENDING, captured.status)
    }

    @Test
    fun `createSubmission sets initial status to PENDING`() = runTest {
        val request = SubmissionRequest(
            problemId = "quiz-202",
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int) = a + b",
        )
        val capturedSlot = slot<Submission>()
        every { submissionRepository.save(capture(capturedSlot)) } answers {
            Mono.just(capturedSlot.captured)
        }
        coEvery { judgeWorker.execute(any(), any()) } returns Unit

        service.createSubmission(request)

        assertEquals(SubmissionStatus.PENDING, capturedSlot.captured.status)
    }

    // ── getResult ─────────────────────────────────────────────────────────

    @Test
    fun `getResult returns null when submission does not exist`() = runTest {
        every { submissionRepository.findById("nonexistent") } returns Mono.empty()

        val result = service.getResult("nonexistent")

        assertNull(result)
    }

    @Test
    fun `getResult returns SubmissionResult when submission exists`() = runTest {
        val submission = Submission(
            id = "existing-uuid",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.ACCEPTED,
        )
        every { submissionRepository.findById("existing-uuid") } returns Mono.just(submission)

        val result = service.getResult("existing-uuid")

        assertNotNull(result)
        assertEquals("existing-uuid", result!!.submissionId)
        assertEquals(SubmissionStatus.ACCEPTED, result.status)
        assertEquals(emptyList<Nothing>(), result.testCases)
    }

    @Test
    fun `getResult returns null when submission is pending or running`() = runTest {
        val pending = Submission(
            id = "sub-pending",
            problemId = "quiz-303",
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            status = SubmissionStatus.PENDING,
        )
        val running = pending.copy(id = "sub-running", status = SubmissionStatus.RUNNING)

        every { submissionRepository.findById("sub-pending") } returns Mono.just(pending)
        every { submissionRepository.findById("sub-running") } returns Mono.just(running)

        val pendingResult = service.getResult("sub-pending")
        val runningResult = service.getResult("sub-running")

        assertNull(pendingResult)
        assertNull(runningResult)
    }

    @Test
    fun `streamResults maps redis messages to test_case_result SSE events`() = runTest {
        val submissionId = "sub-123"
        val payload = """{"caseId":1,"status":"PASSED"}"""
        val redisMessage = mockk<ReactiveSubscription.Message<String, String>>()
        every { redisMessage.message } returns payload
        every { listenerContainer.receive(ChannelTopic.of("submission:$submissionId")) } returns Flux.just(redisMessage)

        val events = service.streamResults(submissionId).toList()

        assertEquals(1, events.size)
        assertEquals("test_case_result", events[0].event())
        assertEquals(payload, events[0].data())
    }

    @Test
    fun `streamResults emits done event and stops after done payload`() = runTest {
        val submissionId = "sub-done"
        val first = """{"caseId":1,"status":"PASSED"}"""
        val done = """{"event":"done","overallStatus":"ACCEPTED"}"""
        val afterDone = """{"caseId":2,"status":"WRONG_ANSWER"}"""

        val m1 = mockk<ReactiveSubscription.Message<String, String>>()
        val m2 = mockk<ReactiveSubscription.Message<String, String>>()
        val m3 = mockk<ReactiveSubscription.Message<String, String>>()
        every { m1.message } returns first
        every { m2.message } returns done
        every { m3.message } returns afterDone
        every { listenerContainer.receive(ChannelTopic.of("submission:$submissionId")) } returns Flux.just(m1, m2, m3)

        val events = service.streamResults(submissionId).toList()

        assertEquals(2, events.size)
        assertEquals("test_case_result", events[0].event())
        assertEquals("done", events[1].event())
        assertEquals(done, events[1].data())
    }

    @Test
    fun `streamResults emits error event and stops after error payload`() = runTest {
        val submissionId = "sub-error"
        val first = """{"caseId":1,"status":"PASSED"}"""
        val error = """{"event":"error","message":"worker failed"}"""
        val afterError = """{"caseId":2,"status":"WRONG_ANSWER"}"""

        val m1 = mockk<ReactiveSubscription.Message<String, String>>()
        val m2 = mockk<ReactiveSubscription.Message<String, String>>()
        val m3 = mockk<ReactiveSubscription.Message<String, String>>()
        every { m1.message } returns first
        every { m2.message } returns error
        every { m3.message } returns afterError
        every { listenerContainer.receive(ChannelTopic.of("submission:$submissionId")) } returns Flux.just(m1, m2, m3)

        val events = service.streamResults(submissionId).toList()

        assertEquals(2, events.size)
        assertEquals("test_case_result", events[0].event())
        assertEquals("error", events[1].event())
        assertEquals(error, events[1].data())
    }
}
