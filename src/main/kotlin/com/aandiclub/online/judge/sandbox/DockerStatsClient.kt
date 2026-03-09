package com.aandiclub.online.judge.sandbox

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Polls `docker stats --no-stream` for a named container while it runs,
 * tracking the peak memory usage in MB.
 *
 * Used for Kotlin and Dart containers where the runner script cannot
 * measure JVM/Dart-VM heap externally. Python containers use tracemalloc
 * internally and embed memoryMb in their JSON output.
 */
@Component
class DockerStatsClient {

    private val log = LoggerFactory.getLogger(DockerStatsClient::class.java)

    /** Begin polling stats for [containerName]. Call [PollHandle.stop] to finish and get peak MB. */
    fun startPolling(containerName: String): PollHandle {
        val peakBytes = AtomicLong(0L)
        val active = AtomicBoolean(true)

        val thread = Thread {
            while (active.get()) {
                try {
                    val proc = ProcessBuilder(
                        "docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", containerName
                    ).redirectErrorStream(true).start()

                    val output = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()

                    // Format: "64.5MiB / 128MiB" — take the first value
                    val match = Regex("""^([\d.]+)(B|KiB|MiB|GiB)""").find(output)
                    if (match != null) {
                        val value = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val bytes = when (match.groupValues[2]) {
                            "GiB" -> (value * 1024L * 1024L * 1024L).toLong()
                            "MiB" -> (value * 1024L * 1024L).toLong()
                            "KiB" -> (value * 1024L).toLong()
                            else  -> value.toLong()
                        }
                        // Update peak with CAS loop
                        var cur: Long
                        do { cur = peakBytes.get() } while (cur < bytes && !peakBytes.compareAndSet(cur, bytes))
                    }

                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.trace("Stats poll failed for {}: {}", containerName, e.message)
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.name = "docker-stats-$containerName"; it.start() }

        return PollHandle(containerName, peakBytes, active, thread)
    }

    inner class PollHandle(
        private val containerName: String,
        private val peakBytes: AtomicLong,
        private val active: AtomicBoolean,
        private val thread: Thread,
    ) {
        /** Stop polling and return peak memory in MB (0.0 if container exited too fast to sample). */
        fun stop(): Double {
            active.set(false)
            thread.interrupt()
            thread.join(300)
            val mb = peakBytes.get() / (1024.0 * 1024.0)
            log.debug("Peak memory for container {}: {:.2f} MB", containerName, mb)
            return mb
        }
    }
}
