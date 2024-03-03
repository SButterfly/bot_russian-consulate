package com.github.russianconsulatebot.services

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration

@SpringBootTest
class Passport10ServiceTest {

    @Autowired
    lateinit var passport10Service : Passport10Service

    @Test
    fun name() = runTest(timeout = Duration.INFINITE) {
        val checkAvailableSlots = passport10Service.containsAvailableSlots()
    }
}