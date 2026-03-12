package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.worker.JudgeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class SubmissionService(
    private val submissionRepository: SubmissionRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val judgeWorker: JudgeWorker,
    private val judgeWorkerScope: CoroutineScope,
    private val judgeWorkerSemaphore: Semaphore,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SubmissionService::class.java)

    suspend fun createSubmission(request: SubmissionRequest): SubmissionAccepted {
        val submission = Submission(
            problemId = request.problemId,
            language = request.language,
            code = request.code,
        )
        val saved = submissionRepository.save(submission).awaitSingle()
        SubmissionMdc.withSubmissionId(saved.id) {
            log.info("Submission created: language={}", saved.language)
        }

        judgeWorkerScope.launch(Dispatchers.IO + SubmissionMdc.context(saved.id)) {
            judgeWorkerSemaphore.withPermit {
                runCatching { judgeWorker.execute(saved) }
                    .onFailure { ex ->
                        log.error("Judge worker failed", ex)
                        val errorPayload = objectMapper.writeValueAsString(
                            mapOf(
                                "event" to "error",
                                "submissionId" to saved.id,
                                "message" to (ex.message ?: "internal worker error"),
                            )
                        )
                        redisTemplate.convertAndSend("submission:${saved.id}", errorPayload).awaitSingle()
                    }
            }
        }
        return SubmissionAccepted(
            submissionId = saved.id,
            streamUrl = "/v1/submissions/${saved.id}/stream",
        )
    }

    fun streamResults(submissionId: String): Flow<ServerSentEvent<String>> =
        listenerContainer.receive(ChannelTopic.of("submission:$submissionId"))
            .asFlow()
            .transformWhile { message ->
                val payload = message.message
                val event = when {
                    payload.contains("\"event\":\"done\"") -> "done"
                    payload.contains("\"event\":\"error\"") -> "error"
                    else -> "test_case_result"
                }
                emit(
                    ServerSentEvent.builder<String>()
                        .event(event)
                        .data(payload)
                        .build()
                )
                event != "done" && event != "error"
            }

    suspend fun getResult(submissionId: String): SubmissionResult? {
        val submission = submissionRepository.findById(submissionId).awaitSingleOrNull()
            ?: return null
        if (submission.status == SubmissionStatus.PENDING || submission.status == SubmissionStatus.RUNNING) {
            return null
        }
        return SubmissionResult(
            submissionId = submission.id,
            status = submission.status,
            testCases = submission.testCaseResults,
        )
    }
}
