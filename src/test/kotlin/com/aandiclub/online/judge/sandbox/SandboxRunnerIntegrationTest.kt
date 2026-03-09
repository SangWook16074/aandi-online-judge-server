package com.aandiclub.online.judge.sandbox

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.TestCaseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class SandboxRunnerIntegrationTest {

    private val runner = SandboxRunner(
        properties = SandboxProperties(
            timeLimitSeconds = 2,
            memoryLimitMb = 256,
            cpuLimit = "1.0",
            pidsLimit = 50,
            images = mapOf(
                "python" to "judge-sandbox-python:latest",
                "kotlin" to "judge-sandbox-kotlin:latest",
                "dart" to "judge-sandbox-dart:latest",
            ),
        ),
        objectMapper = ObjectMapper(),
        dockerStatsClient = DockerStatsClient(),
    )

    @Test
    fun `python executes solution successfully`() {
        val result = runBlockingRunner(
            language = Language.PYTHON,
            code = "def solution(a, b): return a + b",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.PASSED, result.status)
        assertEquals("8", result.output)
        assertEquals(null, result.error)
    }

    @Test
    fun `kotlin executes solution successfully`() {
        val result = runBlockingRunner(
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a + b",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.PASSED, result.status)
        assertEquals("8", result.output)
        assertEquals(null, result.error)
    }

    @Test
    fun `dart executes solution successfully`() {
        val result = runBlockingRunner(
            language = Language.DART,
            code = "int solution(int a, int b) => a + b;",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.PASSED, result.status)
        assertEquals("8", result.output)
        assertEquals(null, result.error)
    }

    @Test
    fun `python runtime error is reported`() {
        val result = runBlockingRunner(
            language = Language.PYTHON,
            code = "def solution(a, b): return a // 0",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.RUNTIME_ERROR, result.status)
        assertTrue(result.error?.startsWith("RUNTIME_ERROR") == true)
    }

    @Test
    fun `python memory measurement detects allocation over 1MB`() {
        val result = runBlockingRunner(
            language = Language.PYTHON,
            code = """
                def solution():
                    payload = bytearray(2 * 1024 * 1024)
                    return len(payload)
            """.trimIndent(),
            args = emptyList(),
        )

        assertEquals(TestCaseStatus.PASSED, result.status)
        assertEquals("2097152", result.output)
        assertTrue(result.memoryMb >= 1.0, "expected memoryMb >= 1.0, but was ${result.memoryMb}")
    }

    @Test
    fun `kotlin compile error is reported`() {
        val result = runBlockingRunner(
            language = Language.KOTLIN,
            code = "fun solution(a: Int, b: Int): Int = a +",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.COMPILE_ERROR, result.status)
        assertTrue(result.error?.startsWith("COMPILE_ERROR") == true)
    }

    @Test
    fun `dart compile error is reported`() {
        val result = runBlockingRunner(
            language = Language.DART,
            code = "int solution(int a, int b) => ;",
            args = listOf(3, 5),
        )

        assertEquals(TestCaseStatus.COMPILE_ERROR, result.status)
        assertTrue(result.error?.startsWith("COMPILE_ERROR") == true)
    }

    @Test
    fun `python infinite loop returns time limit exceeded`() {
        val shortLimitRunner = SandboxRunner(
            properties = SandboxProperties(
                timeLimitSeconds = 1,
                memoryLimitMb = 256,
                cpuLimit = "1.0",
                pidsLimit = 50,
                images = mapOf("python" to "judge-sandbox-python:latest"),
            ),
            objectMapper = ObjectMapper(),
            dockerStatsClient = DockerStatsClient(),
        )

        val result = kotlinx.coroutines.runBlocking {
            shortLimitRunner.run(
                Language.PYTHON,
                SandboxInput(
                    code = "def solution(a, b):\n    while True:\n        pass",
                    args = listOf(3, 5),
                )
            )
        }

        assertEquals(TestCaseStatus.TIME_LIMIT_EXCEEDED, result.status)
        assertTrue(result.error?.contains("limit") == true)
    }

    private fun runBlockingRunner(
        language: Language,
        code: String,
        args: List<Any?>,
    ): SandboxOutput = kotlinx.coroutines.runBlocking {
        runner.run(language, SandboxInput(code = code, args = args))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireDockerAndImages() {
            assumeTrue(check("docker", "info"), "Docker daemon is not available")
            assumeTrue(check("docker", "image", "inspect", "judge-sandbox-python:latest"), "Missing image: judge-sandbox-python:latest")
            assumeTrue(check("docker", "image", "inspect", "judge-sandbox-kotlin:latest"), "Missing image: judge-sandbox-kotlin:latest")
            assumeTrue(check("docker", "image", "inspect", "judge-sandbox-dart:latest"), "Missing image: judge-sandbox-dart:latest")
        }

        private fun check(vararg cmd: String): Boolean = runCatching {
            ProcessBuilder(*cmd).start().waitFor() == 0
        }.getOrDefault(false)
    }
}
