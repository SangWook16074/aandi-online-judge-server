package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.config.ProblemEventProperties
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
@ConditionalOnProperty(prefix = "judge.problem-events", name = ["enabled"], havingValue = "true")
class ProblemEventSqsConsumer(
    private val sqsClient: SqsClient,
    private val properties: ProblemEventProperties,
    private val problemEventSyncService: ProblemEventSyncService,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(ProblemEventSqsConsumer::class.java)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "problem-event-sqs-consumer").apply { isDaemon = true }
    }

    override fun start() {
        if (properties.queueUrl.isBlank()) {
            log.warn("Problem event SQS consumer disabled: queueUrl is blank")
            return
        }
        if (!running.compareAndSet(false, true)) return
        executor.submit { pollLoop() }
        log.info("Problem event SQS consumer started")
    }

    override fun stop() {
        running.set(false)
        executor.shutdownNow()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        log.info("Problem event SQS consumer stopped")
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    @PreDestroy
    fun onDestroy() {
        stop()
    }

    private fun pollLoop() {
        while (running.get()) {
            try {
                val response = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(properties.queueUrl)
                        .waitTimeSeconds(properties.waitTimeSeconds.coerceIn(1, 20))
                        .maxNumberOfMessages(properties.maxMessages.coerceIn(1, 10))
                        .build()
                )

                response.messages().forEach { message ->
                    val ack = handleMessage(message.body())
                    if (ack) {
                        sqsClient.deleteMessage(
                            DeleteMessageRequest.builder()
                                .queueUrl(properties.queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build()
                        )
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (ex: Exception) {
                if (running.get()) {
                    log.error("SQS polling failed", ex)
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
    }

    private fun handleMessage(rawBody: String): Boolean {
        return runBlocking {
            when (problemEventSyncService.sync(rawBody)) {
                ProblemEventSyncOutcome.UPSERTED -> true
                ProblemEventSyncOutcome.SKIPPED -> true
            }
        }
    }
}
