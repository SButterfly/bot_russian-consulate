package com.github.russianconsulatebot.services

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class AntiCaptureServiceIntegrationTest {

    lateinit var antiCaptureService: AntiCaptureService

    @BeforeEach
    fun setUp() {
        antiCaptureService = AntiCaptureService(WebClient.create(), ObjectMapper())
    }

    @Test
    fun `parse simple capture`() = runTest {
        val capture = this::class.java.getResourceAsStream("/capture1.jpeg")!!
        val bytes = capture.readBytes()

        val code = antiCaptureService.solve(bytes, "image/jpeg")

        assertEquals("022880", code)
    }

    @Test
    fun `parse capture and remove whitespaces`() = runTest {
        val capture = this::class.java.getResourceAsStream("/capture2.jpeg")!!
        val bytes = capture.readBytes()

        val code = antiCaptureService.solve(bytes, "image/jpeg")

        assertEquals("110047", code)
    }
}