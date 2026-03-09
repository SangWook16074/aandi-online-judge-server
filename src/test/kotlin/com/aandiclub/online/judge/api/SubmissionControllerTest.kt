package com.aandiclub.online.judge.api

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.service.SubmissionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent

/**
 * Spring 컨텍스트 없이 컨트롤러 로직을 직접 검증하는 단위 테스트.
 * @Valid 등 Spring 검증 레이어 테스트는 Phase 5에서 @WebFluxTest 슬라이스 테스트로 추가 예정.
 */
class SubmissionControllerTest {

    private val submissionService = mockk<SubmissionService>()
    private val controller = SubmissionController(submissionService)

    // ── POST /v1/submissions ───────────────────────────────────────────────

    @Test
    fun `submit calls service and returns 202 Accepted`() = runTest {
        val request = SubmissionRequest(
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a, b):\n    return a + b",
        )
        val expected = SubmissionAccepted(
            submissionId = "test-uuid",
            streamUrl = "/v1/submissions/test-uuid/stream",
        )
        coEvery { submissionService.createSubmission(any()) } returns expected

        val response = controller.submit(request)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("test-uuid", response.body?.submissionId)
        assertEquals("/v1/submissions/test-uuid/stream", response.body?.streamUrl)
        coVerify(exactly = 1) { submissionService.createSubmission(any()) }
    }

    @Test
    fun `submit propagates service result accurately`() = runTest {
        val request = SubmissionRequest(
            problemId = "quiz-202",
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int) = a + b",
        )
        val expected = SubmissionAccepted(
            submissionId = "kotlin-uuid",
            streamUrl = "/v1/submissions/kotlin-uuid/stream",
        )
        coEvery { submissionService.createSubmission(any()) } returns expected

        val response = controller.submit(request)

        assertNotNull(response.body)
        assertEquals("kotlin-uuid", response.body!!.submissionId)
    }

    // ── GET /v1/submissions/{id}/stream ───────────────────────────────────

    @Test
    fun `streamResults delegates to service and returns Flow`() {
        val event = ServerSentEvent.builder<String>()
            .event("test_case_result")
            .data("""{"caseId":1,"status":"PASSED","timeMs":10.0,"memoryMb":4.0}""")
            .build()
        coEvery { submissionService.streamResults("test-uuid") } returns flowOf(event)

        val flow = controller.streamResults("test-uuid")

        assertNotNull(flow)
        coVerify(exactly = 1) { submissionService.streamResults("test-uuid") }
    }

    // ── GET /v1/submissions/{id} ───────────────────────────────────────────

    @Test
    fun `getResult returns 200 with body when submission exists`() = runTest {
        val result = SubmissionResult(
            submissionId = "test-uuid",
            status = SubmissionStatus.ACCEPTED,
            testCases = emptyList(),
        )
        coEvery { submissionService.getResult("test-uuid") } returns result

        val response = controller.getResult("test-uuid")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(SubmissionStatus.ACCEPTED, response.body?.status)
        assertEquals("test-uuid", response.body?.submissionId)
    }

    @Test
    fun `getResult returns 404 when submission not found`() = runTest {
        coEvery { submissionService.getResult("missing-id") } returns null

        val response = controller.getResult("missing-id")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `getResult polling returns 404 while running then 200 when completed`() = runTest {
        val done = SubmissionResult(
            submissionId = "poll-uuid",
            status = SubmissionStatus.ACCEPTED,
            testCases = emptyList(),
        )
        coEvery { submissionService.getResult("poll-uuid") } returnsMany listOf(null, done)

        val first = controller.getResult("poll-uuid")
        val second = controller.getResult("poll-uuid")

        assertEquals(HttpStatus.NOT_FOUND, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(SubmissionStatus.ACCEPTED, second.body?.status)
    }

    @Test
    fun `getResult handles all final SubmissionStatus values`() = runTest {
        listOf(
            SubmissionStatus.WRONG_ANSWER,
            SubmissionStatus.TIME_LIMIT_EXCEEDED,
            SubmissionStatus.RUNTIME_ERROR,
            SubmissionStatus.COMPILE_ERROR,
        ).forEach { status ->
            val result = SubmissionResult(
                submissionId = "uuid-$status",
                status = status,
                testCases = emptyList(),
            )
            coEvery { submissionService.getResult("uuid-$status") } returns result

            val response = controller.getResult("uuid-$status")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(status, response.body?.status)
        }
    }
}
