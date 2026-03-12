package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.repository.ProblemRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class ProblemEventSyncServiceTest {
    private val problemRepository = mockk<ProblemRepository>()
    private val objectMapper = ObjectMapper()
    private val service = ProblemEventSyncService(problemRepository, objectMapper)

    @Test
    fun `sync upserts problem test cases from SNS envelope`() = runTest {
        val savedSlot = slot<Problem>()
        every { problemRepository.save(capture(savedSlot)) } answers { Mono.just(firstArg()) }

        val raw = """
            {
              "Type":"Notification",
              "Message":"{\"eventType\":\"PROBLEM_CREATED\",\"problemId\":\"prob-uuid-1\",\"testCases\":[{\"caseId\":1,\"input\":[3,5],\"output\":\"8\"},{\"caseId\":2,\"input\":[10,2],\"output\":\"12\"}]}"
            }
        """.trimIndent()

        val outcome = service.sync(raw)

        assertEquals(ProblemEventSyncOutcome.UPSERTED, outcome)
        assertEquals("prob-uuid-1", savedSlot.captured.problemId)
        assertEquals(2, savedSlot.captured.testCases.size)
        assertEquals(listOf(3, 5), savedSlot.captured.testCases[0].args)
        assertEquals("8", savedSlot.captured.testCases[0].expectedOutput)
    }

    @Test
    fun `sync skips payload when problem id is missing`() = runTest {
        val raw = """
            {
              "eventType":"PROBLEM_CREATED",
              "testCases":[{"caseId":1,"input":[1,2],"output":"3"}]
            }
        """.trimIndent()

        val outcome = service.sync(raw)

        assertEquals(ProblemEventSyncOutcome.SKIPPED, outcome)
        verify(exactly = 0) { problemRepository.save(any()) }
    }
}
