package com.aandiclub.online.judge.config

import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "app.openapi")
data class OpenApiProperties(
    val serverUrl: String = "https://api.aandiclub.com",
)

@Configuration
@EnableConfigurationProperties(OpenApiProperties::class)
class SwaggerConfig(
    private val openApiProperties: OpenApiProperties,
) {
    @Bean
    fun publicServerUrlOpenApiCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val publicServerUrl = openApiProperties.serverUrl.trim()
        if (publicServerUrl.isBlank()) return@OpenApiCustomizer

        openApi.servers = listOf(
            Server()
                .url(publicServerUrl)
                .description("Public API"),
        )
    }
}
