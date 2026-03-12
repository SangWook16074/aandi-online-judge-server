package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.domain.Problem
import com.aandiclub.online.judge.domain.TestCase
import com.aandiclub.online.judge.repository.ProblemRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

enum class ProblemEventSyncOutcome {
    UPSERTED,
    SKIPPED,
}

@Service
class ProblemEventSyncService(
    private val problemRepository: ProblemRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ProblemEventSyncService::class.java)

    suspend fun sync(rawBody: String): ProblemEventSyncOutcome {
        val root = runCatching { objectMapper.readTree(rawBody) }
            .getOrElse { ex ->
                log.warn("Skipping problem event: invalid JSON body ({})", ex.message)
                return ProblemEventSyncOutcome.SKIPPED
            }

        val payload = unwrapSnsEnvelope(root) ?: return ProblemEventSyncOutcome.SKIPPED
        val problemId = extractProblemId(payload)
        if (problemId.isNullOrBlank()) {
            log.debug("Skipping problem event: no problem UUID field")
            return ProblemEventSyncOutcome.SKIPPED
        }

        val testCasesNode = findFirst(payload, "testCases", "test_cases", "cases")
        if (testCasesNode == null || !testCasesNode.isArray) {
            log.debug("Skipping problem event: no testCases array, problemId={}", problemId)
            return ProblemEventSyncOutcome.SKIPPED
        }

        val testCases = testCasesNode.mapIndexedNotNull { idx, testCaseNode ->
            val outputNode = findFirst(testCaseNode, "expectedOutput", "expected_output", "output", "expected")
            val expectedOutput = nodeToOutput(outputNode) ?: return@mapIndexedNotNull null

            val caseId = findFirst(testCaseNode, "caseId", "case_id")?.asInt(idx + 1) ?: (idx + 1)
            val argsNode = findFirst(testCaseNode, "args", "input", "inputs")
            val args = nodeToArgs(argsNode)

            TestCase(
                caseId = caseId,
                args = args,
                expectedOutput = expectedOutput,
            )
        }

        problemRepository.save(
            Problem(
                problemId = problemId,
                testCases = testCases,
            )
        ).awaitSingle()

        log.info("Problem test cases upserted: problemId={}, cases={}", problemId, testCases.size)
        return ProblemEventSyncOutcome.UPSERTED
    }

    private fun unwrapSnsEnvelope(root: JsonNode): JsonNode? {
        val messageNode = root.get("Message") ?: return root
        return when {
            messageNode.isTextual -> runCatching { objectMapper.readTree(messageNode.asText()) }.getOrNull()
            messageNode.isObject -> messageNode
            else -> null
        }
    }

    private fun extractProblemId(node: JsonNode): String? =
        findFirst(node, "problemId", "problem_id", "problemUuid", "problem_uuid", "uuid", "id")
            ?.asText()
            ?.takeIf { it.isNotBlank() }

    private fun findFirst(node: JsonNode, vararg keys: String): JsonNode? =
        keys.asSequence()
            .mapNotNull { key -> node.get(key) }
            .firstOrNull { !it.isNull }

    private fun nodeToArgs(node: JsonNode?): List<Any?> {
        if (node == null || node.isNull) return emptyList()
        return if (node.isArray) {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(node.toString(), List::class.java) as List<Any?>
        } else {
            listOf(objectMapper.convertValue(node, Any::class.java))
        }
    }

    private fun nodeToOutput(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        return if (node.isValueNode) node.asText() else node.toString()
    }
}
