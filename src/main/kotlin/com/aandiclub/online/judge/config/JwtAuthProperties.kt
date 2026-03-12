package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge.jwt-auth")
data class JwtAuthProperties(
    val enabled: Boolean = true,
    val signingKey: String = "",
    val requiredRole: String = "USER",
    val allowWithoutRoleClaim: Boolean = true,
)
