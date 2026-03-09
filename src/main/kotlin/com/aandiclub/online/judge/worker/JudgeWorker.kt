package com.aandiclub.online.judge.worker

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.config.ProblemCatalogProperties
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.domain.TestCaseStatus
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxInput
import com.aandiclub.online.judge.sandbox.SandboxRunner
import kotlinx.coroutines.withContext
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class JudgeWorker(
    private val sandboxRunner: SandboxRunner,
    private val submissionRepository: SubmissionRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val sandboxProperties: SandboxProperties,
    private val problemCatalogProperties: ProblemCatalogProperties,
) {
    private val log = LoggerFactory.getLogger(JudgeWorker::class.java)

    suspend fun execute(
        submission: Submission,
        testCases: List<TestCase>? = null,
    ): Unit = withContext(SubmissionMdc.context(submission.id)) {
        val resolvedTestCases = testCases ?: loadTestCases(submission.problemId)
        log.info("Judge worker started: cases={}", resolvedTestCases.size)

        submission.status = SubmissionStatus.RUNNING
        submissionRepository.save(submission).awaitSingle()

        val channel = "submission:${submission.id}"
        if (resolvedTestCases.isEmpty()) {
            val result = TestCaseResult(
                caseId = 0,
                status = TestCaseStatus.RUNTIME_ERROR,
                error = "No test cases configured for problemId=${submission.problemId}",
            )
            submission.testCaseResults = listOf(result)
            submission.status = SubmissionStatus.RUNTIME_ERROR
            submission.completedAt = Instant.now()
            submissionRepository.save(submission).awaitSingle()

            val resultPayload = objectMapper.writeValueAsString(result)
            redisTemplate.convertAndSend(channel, resultPayload).awaitSingle()
            val donePayload = objectMapper.writeValueAsString(
                mapOf(
                    "event" to "done",
                    "submissionId" to submission.id,
                    "overallStatus" to SubmissionStatus.RUNTIME_ERROR.name,
                )
            )
            redisTemplate.convertAndSend(channel, donePayload).awaitSingle()
            return@withContext
        }

        val results = resolvedTestCases.map { testCase ->
            val output = sandboxRunner.run(
                language = submission.language,
                input = SandboxInput(code = submission.code, args = testCase.args),
            )
            val status = resolveStatus(
                runnerStatus = output.status,
                output = output.output,
                memoryMb = output.memoryMb,
                expectedOutput = testCase.expectedOutput,
            )
            TestCaseResult(
                caseId = testCase.caseId,
                status = status,
                timeMs = output.timeMs,
                memoryMb = output.memoryMb,
                output = output.output,
                error = output.error,
            )
        }

        results.forEach { result ->
            val payload = objectMapper.writeValueAsString(result)
            redisTemplate.convertAndSend(channel, payload).awaitSingle()
        }

        val finalStatus = results.firstOrNull { it.status != TestCaseStatus.PASSED }
            ?.status
            ?.toSubmissionStatus()
            ?: SubmissionStatus.ACCEPTED

        submission.testCaseResults = results
        submission.status = finalStatus
        submission.completedAt = Instant.now()
        submissionRepository.save(submission).awaitSingle()

        val donePayload = objectMapper.writeValueAsString(
            mapOf(
                "event" to "done",
                "submissionId" to submission.id,
                "overallStatus" to finalStatus.name,
            )
        )
        redisTemplate.convertAndSend(channel, donePayload).awaitSingle()
        Unit
    }

    private fun resolveStatus(
        runnerStatus: TestCaseStatus,
        output: String?,
        memoryMb: Double,
        expectedOutput: String,
    ): TestCaseStatus {
        if (runnerStatus != TestCaseStatus.PASSED) return runnerStatus
        if (memoryMb > sandboxProperties.memoryLimitMb) return TestCaseStatus.MEMORY_LIMIT_EXCEEDED
        return if (output == expectedOutput) TestCaseStatus.PASSED else TestCaseStatus.WRONG_ANSWER
    }

    private fun TestCaseStatus.toSubmissionStatus(): SubmissionStatus = when (this) {
        TestCaseStatus.PASSED -> SubmissionStatus.ACCEPTED
        TestCaseStatus.WRONG_ANSWER -> SubmissionStatus.WRONG_ANSWER
        TestCaseStatus.TIME_LIMIT_EXCEEDED -> SubmissionStatus.TIME_LIMIT_EXCEEDED
        TestCaseStatus.MEMORY_LIMIT_EXCEEDED -> SubmissionStatus.MEMORY_LIMIT_EXCEEDED
        TestCaseStatus.RUNTIME_ERROR -> SubmissionStatus.RUNTIME_ERROR
        TestCaseStatus.COMPILE_ERROR -> SubmissionStatus.COMPILE_ERROR
    }

    private fun loadTestCases(problemId: String): List<TestCase> =
        problemCatalogProperties.find(problemId)?.testCases ?: emptyList()
}
