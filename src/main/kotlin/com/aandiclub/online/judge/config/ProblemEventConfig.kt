package com.aandiclub.online.judge.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sqs.SqsClient

@ConfigurationProperties(prefix = "judge.problem-events")
data class ProblemEventProperties(
    val enabled: Boolean = false,
    val queueUrl: String = "",
    val waitTimeSeconds: Int = 20,
    val maxMessages: Int = 10,
)

@Configuration
class ProblemEventConfig {

    @Bean
    @ConditionalOnProperty(prefix = "judge.problem-events", name = ["enabled"], havingValue = "true")
    fun sqsClient(): SqsClient = SqsClient.create()
}
