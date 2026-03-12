package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore

@ConfigurationProperties(prefix = "judge.sandbox")
data class SandboxProperties(
    val timeLimitSeconds: Int = 2,
    val memoryLimitMb: Int = 256,
    val cpuLimit: String = "1.0",
    val pidsLimit: Int = 50,
    val images: Map<String, String> = mapOf(
        "python" to "judge-sandbox-python:latest",
        "kotlin" to "judge-sandbox-kotlin:latest",
        "dart"   to "judge-sandbox-dart:latest",
    ),
)

@ConfigurationProperties(prefix = "judge.worker")
data class WorkerProperties(
    val maxConcurrency: Int = 2,
)

@Configuration
@EnableConfigurationProperties(
    SandboxProperties::class,
    ProblemCatalogProperties::class,
    ProblemEventProperties::class,
    JwtAuthProperties::class,
    RateLimitProperties::class,
    WorkerProperties::class,
)
class AppConfig {

    @Bean
    fun reactiveStringRedisTemplate(
        factory: ReactiveRedisConnectionFactory,
    ): ReactiveStringRedisTemplate = ReactiveStringRedisTemplate(factory)

    @Bean
    fun reactiveRedisMessageListenerContainer(
        factory: ReactiveRedisConnectionFactory,
    ): ReactiveRedisMessageListenerContainer = ReactiveRedisMessageListenerContainer(factory)

    @Bean
    fun judgeWorkerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Bean
    fun judgeWorkerSemaphore(workerProperties: WorkerProperties): Semaphore =
        Semaphore(workerProperties.maxConcurrency.coerceAtLeast(1))
}
