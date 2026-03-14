package com.aandiclub.online.judge.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.servers.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SwaggerConfigTest {

    @Test
    fun `public server url overrides generated servers`() {
        val openApi = OpenAPI().servers(listOf(Server().url("http://10.0.0.12:8080")))

        SwaggerConfig(
            OpenApiProperties(serverUrl = "https://api.aandiclub.com"),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf("https://api.aandiclub.com"), openApi.servers.map { it.url })
    }

    @Test
    fun `blank public server url keeps generated servers intact`() {
        val original = Server().url("http://10.0.0.12:8080")
        val openApi = OpenAPI().servers(listOf(original))

        SwaggerConfig(
            OpenApiProperties(serverUrl = " "),
        ).publicServerUrlOpenApiCustomizer().customise(openApi)

        assertEquals(listOf("http://10.0.0.12:8080"), openApi.servers.map { it.url })
    }
}
