package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val submitRequests: Int = 30,
    val windowSeconds: Long = 60,
)
