package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

@ConfigurationProperties(prefix = "judge.cors")
data class CorsProperties(
    val apiAllowedOriginPatterns: List<String> = listOf(
        "http://localhost:*",
        "http://127.0.0.1:*",
        "https://aandiclub.com",
        "https://admin.aandiclub.com",
        "https://online.judge.aandiclub.com",
    ),
    val apiAllowedMethods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf("*"),
    val exposedHeaders: List<String> = listOf("Location"),
    val allowCredentials: Boolean = false,
    val maxAgeSeconds: Long = 3600,
)

@Configuration
@EnableConfigurationProperties(CorsProperties::class)
class CorsConfig(
    private val corsProperties: CorsProperties,
) {
    @Bean
    fun corsWebFilter(): CorsWebFilter {
        val apiConfiguration = corsConfiguration(
            allowedOriginPatterns = corsProperties.apiAllowedOriginPatterns,
            allowedMethods = corsProperties.apiAllowedMethods,
        )
        val swaggerConfiguration = corsConfiguration(
            allowedOriginPatterns = corsProperties.apiAllowedOriginPatterns,
            allowedMethods = listOf("GET", "OPTIONS"),
        )
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/v1/**", apiConfiguration)
        source.registerCorsConfiguration("/v3/api-docs/**", swaggerConfiguration)
        source.registerCorsConfiguration("/swagger-ui/**", swaggerConfiguration)
        source.registerCorsConfiguration("/swagger-ui.html", swaggerConfiguration)
        return CorsWebFilter(source)
    }

    private fun corsConfiguration(
        allowedOriginPatterns: List<String>,
        allowedMethods: List<String>,
    ): CorsConfiguration = CorsConfiguration().apply {
        this.allowedOriginPatterns = allowedOriginPatterns
        this.allowedMethods = allowedMethods
        this.allowedHeaders = corsProperties.allowedHeaders
        this.exposedHeaders = corsProperties.exposedHeaders
        this.allowCredentials = corsProperties.allowCredentials
        this.maxAge = corsProperties.maxAgeSeconds
    }
}
