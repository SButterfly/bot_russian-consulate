package com.github.russianconsulatebot.services

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

class AntiCaptureServiceIntegrationTest {

    private lateinit var antiCaptureService: AntiCaptureService

    @BeforeEach
    fun setUp() {
        antiCaptureService = AntiCaptureService(OkHttpClient(), ObjectMapper())
    }

    @Test
    fun `parse simple capture`() = runTest {
        val capture = this::class.java.getResourceAsStream("/capture1.jpeg")!!
        val image = ImageIO.read(capture)

        val code = antiCaptureService.solve(image)

        assertEquals("022880", code)
    }

    @Test
    fun `parse capture and remove whitespaces`() = runTest {
        val capture = this::class.java.getResourceAsStream("/capture2.jpeg")!!
        val image = ImageIO.read(capture)

        val code = antiCaptureService.solve(image)
        assertEquals("110047", code)
    }
}