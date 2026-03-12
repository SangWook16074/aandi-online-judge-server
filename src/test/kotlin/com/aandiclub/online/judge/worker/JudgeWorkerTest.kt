package com.aandiclub.online.judge.worker

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.config.ProblemCatalogProperties
import com.aandiclub.online.judge.config.ProblemItem
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.repository.ProblemRepository
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxInput
import com.aandiclub.online.judge.sandbox.SandboxOutput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class JudgeWorkerTest {
    private val sandboxRunner = mockk<SandboxRunner>()
    private val submissionRepository = mockk<SubmissionRepository>()
    private val problemRepository = mockk<ProblemRepository>()
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val objectMapper = ObjectMapper()
    private val sandboxProperties = SandboxProperties(memoryLimitMb = 128)
    private val problemCatalogProperties = ProblemCatalogProperties(
        items = mapOf(
            "quiz-101" to ProblemItem(
                testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = "8"))
            )
        )
    )

    private val judgeWorker = JudgeWorker(
        sandboxRunner = sandboxRunner,
        submissionRepository = submissionRepository,
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        sandboxProperties = sandboxProperties,
        problemRepository = problemRepository,
        problemCatalogProperties = problemCatalogProperties,
    )

    init {
        every { problemRepository.findById(any<String>()) } returns Mono.empty<Problem>()
    }

    @Test
    fun `execute publishes case result and done when accepted`() = runTest {
        val submission = Submission(
            id = "sub-1",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = "8"))
        coEvery {
            sandboxRunner.run(Language.PYTHON, SandboxInput(submission.code, listOf(3, 5)))
        } returns SandboxOutput(
            status = TestCaseStatus.PASSED,
            output = "8",
            error = null,
            timeMs = 1.2,
            memoryMb = 2.1,
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        coVerify(exactly = 1) {
            sandboxRunner.run(Language.PYTHON, SandboxInput(submission.code, listOf(3, 5)))
        }
        verify(atLeast = 2) { submissionRepository.save(any()) }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-1",
                match { it.contains("\"caseId\":1") && it.contains("\"status\":\"PASSED\"") }
            )
        }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-1",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"ACCEPTED\"") }
            )
        }
    }

    @Test
    fun `execute marks wrong answer when output mismatches expected`() = runTest {
        val submission = Submission(
            id = "sub-2",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = "8"))
        coEvery { sandboxRunner.run(Language.PYTHON, any()) } returns SandboxOutput(
            status = TestCaseStatus.PASSED,
            output = "7",
            error = null,
            timeMs = 1.0,
            memoryMb = 2.0,
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        assertEquals(SubmissionStatus.WRONG_ANSWER, submission.status)
        assertEquals(TestCaseStatus.WRONG_ANSWER, submission.testCaseResults.first().status)
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-2",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"WRONG_ANSWER\"") }
            )
        }
    }

    @Test
    fun `execute marks runtime error when no test cases are provided`() = runTest {
        val submission = Submission(
            id = "sub-3",
            problemId = "unknown-problem",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, emptyList())

        assertEquals(SubmissionStatus.RUNTIME_ERROR, submission.status)
        assertEquals(1, submission.testCaseResults.size)
        assertEquals(TestCaseStatus.RUNTIME_ERROR, submission.testCaseResults.first().status)
        coVerify(exactly = 0) { sandboxRunner.run(any(), any()) }
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-3",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"RUNTIME_ERROR\"") }
            )
        }
    }

    @Test
    fun `execute marks memory limit exceeded when memory is over configured limit`() = runTest {
        val submission = Submission(
            id = "sub-4",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        val testCases = listOf(TestCase(caseId = 1, args = listOf(3, 5), expectedOutput = "8"))
        coEvery { sandboxRunner.run(Language.PYTHON, any()) } returns SandboxOutput(
            status = TestCaseStatus.PASSED,
            output = "8",
            error = null,
            timeMs = 5.0,
            memoryMb = 256.0,
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission, testCases)

        assertEquals(SubmissionStatus.MEMORY_LIMIT_EXCEEDED, submission.status)
        assertEquals(TestCaseStatus.MEMORY_LIMIT_EXCEEDED, submission.testCaseResults.first().status)
        verify {
            redisTemplate.convertAndSend(
                "submission:sub-4",
                match { it.contains("\"event\":\"done\"") && it.contains("\"overallStatus\":\"MEMORY_LIMIT_EXCEEDED\"") }
            )
        }
    }

    @Test
    fun `execute loads test cases from problem catalog when cases are not provided`() = runTest {
        val submission = Submission(
            id = "sub-5",
            problemId = "quiz-101",
            language = Language.PYTHON,
            code = "def solution(a,b): return a+b",
        )
        coEvery { sandboxRunner.run(Language.PYTHON, any()) } returns SandboxOutput(
            status = TestCaseStatus.PASSED,
            output = "8",
            error = null,
            timeMs = 1.0,
            memoryMb = 2.0,
        )
        every { submissionRepository.save(any()) } answers { Mono.just(firstArg()) }
        every { redisTemplate.convertAndSend(any(), any()) } returns Mono.just(1L)

        judgeWorker.execute(submission)

        coVerify(exactly = 1) { sandboxRunner.run(Language.PYTHON, SandboxInput(submission.code, listOf(3, 5))) }
        assertEquals(SubmissionStatus.ACCEPTED, submission.status)
    }
}
