package com.aandiclub.online.judge.sandbox

import com.aandiclub.online.judge.config.SandboxProperties
import com.aandiclub.online.judge.domain.Language
import com.aandiclub.online.judge.domain.TestCaseStatus
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SandboxInput(
    val code: String,
    val args: List<Any?>,
)

data class SandboxOutput(
    val status: TestCaseStatus,
    val output: String?,
    val error: String?,
    val timeMs: Double,
    val memoryMb: Double,
)

/** Internal DTO for deserializing the runner's stdout JSON. */
private data class RunnerResult(
    val output: String?,
    val error: String?,
    val timeMs: Double = 0.0,
    val memoryMb: Double = 0.0,
)

@Component
class SandboxRunner(
    private val properties: SandboxProperties,
    private val objectMapper: ObjectMapper,
    private val dockerStatsClient: DockerStatsClient,
) {
    private val log = LoggerFactory.getLogger(SandboxRunner::class.java)

    suspend fun run(language: Language, input: SandboxInput): SandboxOutput =
        withContext(Dispatchers.IO) {
            val image = properties.images[language.value]
                ?: error("No sandbox image configured for language: $language")
            runContainer(image, input, language)
        }

    private fun runContainer(image: String, input: SandboxInput, language: Language): SandboxOutput {
        val containerName = "judge-${UUID.randomUUID().toString().take(12)}"
        val inputJson = objectMapper.writeValueAsString(
            mapOf("code" to input.code, "args" to input.args)
        )

        val cmd = listOf(
            "docker", "run", "--rm",
            "--name", containerName,
            "--network", "none",
            "--cpus", properties.cpuLimit,
            "--memory", "${properties.memoryLimitMb}m",
            "--read-only",
            "--security-opt", "no-new-privileges",
            "--pids-limit", "${properties.pidsLimit}",
            "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
            "-i", image,
        )

        log.debug("Starting sandbox container: name={}, image={}", containerName, image)

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        // For Kotlin/Dart, poll docker stats for external memory measurement.
        // Python containers use tracemalloc internally and embed memoryMb in their output.
        val statsHandle = if (language != Language.PYTHON) {
            dockerStatsClient.startPolling(containerName)
        } else null

        // Write JSON to stdin in a daemon thread to avoid deadlock if stdout buffer fills
        val stdinThread = Thread {
            try {
                process.outputStream.use { it.write(inputJson.toByteArray()) }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        // Collect stdout in a daemon thread
        val stdoutBuilder = StringBuilder()
        val stdoutThread = Thread {
            try {
                stdoutBuilder.append(process.inputStream.bufferedReader().readText())
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        // Hard wall-clock cap includes language-specific startup/compile headroom.
        // This preserves a strict execution limit while avoiding false TLE for Kotlin/Dart compile latency.
        val timeLimitMs = properties.timeLimitSeconds * 1_000L
        val timedOut = !process.waitFor(timeLimitMs + startupHeadroomMs(language), TimeUnit.MILLISECONDS)

        val externalMemoryMb = statsHandle?.stop() ?: 0.0

        if (timedOut) {
            process.destroyForcibly()
            log.warn("Container {} timed out after {}s", containerName, properties.timeLimitSeconds)
            return SandboxOutput(
                status = TestCaseStatus.TIME_LIMIT_EXCEEDED,
                output = null,
                error = "Execution exceeded ${properties.timeLimitSeconds}s limit",
                timeMs = timeLimitMs.toDouble(),
                memoryMb = externalMemoryMb,
            )
        }

        stdoutThread.join(500)
        stdinThread.join(200)

        val rawOutput = stdoutBuilder.toString().trim()
        val exitCode = process.exitValue()

        log.debug("Container {} exited: code={}, output.length={}", containerName, exitCode, rawOutput.length)

        return parseRunnerOutput(rawOutput, exitCode, language, externalMemoryMb)
    }

    private fun parseRunnerOutput(
        rawOutput: String,
        exitCode: Int,
        language: Language,
        externalMemoryMb: Double,
    ): SandboxOutput {
        if (rawOutput.isBlank()) {
            return SandboxOutput(
                status = TestCaseStatus.RUNTIME_ERROR,
                output = null,
                error = "RUNTIME_ERROR: container produced no output (exit code $exitCode)",
                timeMs = 0.0,
                memoryMb = externalMemoryMb,
            )
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(rawOutput, Map::class.java) as Map<String, Any?>
            val result = RunnerResult(
                output = map["output"] as? String,
                error = map["error"] as? String,
                timeMs = (map["timeMs"] as? Number)?.toDouble() ?: 0.0,
                memoryMb = (map["memoryMb"] as? Number)?.toDouble() ?: 0.0,
            )

            // Python uses tracemalloc; Kotlin/Dart use docker stats from externalMemoryMb
            val resolvedMemoryMb = if (language == Language.PYTHON) result.memoryMb else externalMemoryMb

            val status = when {
                result.error != null && result.error.startsWith("COMPILE_ERROR") -> TestCaseStatus.COMPILE_ERROR
                result.error != null -> TestCaseStatus.RUNTIME_ERROR
                // PASSED is provisional — JudgeWorker will confirm against expected output
                else -> TestCaseStatus.PASSED
            }

            SandboxOutput(
                status = status,
                output = result.output,
                error = result.error,
                timeMs = result.timeMs,
                memoryMb = resolvedMemoryMb,
            )
        } catch (e: Exception) {
            log.error("Failed to parse runner output: {}", rawOutput, e)
            SandboxOutput(
                status = TestCaseStatus.RUNTIME_ERROR,
                output = null,
                error = "RUNTIME_ERROR: failed to parse runner output: ${e.message}",
                timeMs = 0.0,
                memoryMb = externalMemoryMb,
            )
        }
    }

    private fun startupHeadroomMs(language: Language): Long = when (language) {
        Language.PYTHON -> 2_000L
        // Kotlin runner compiles user code per case; allow extra wall-clock headroom.
        Language.KOTLIN -> 16_000L
        // Dart runner creates temp sources and runs JIT; moderate headroom is enough.
        Language.DART -> 4_000L
    }
}
