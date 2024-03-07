package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.services.dto.Website
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration

@SpringBootTest
@Disabled("Disabled as it does a heavy work")
class Passport10ServiceTest {

    @Autowired
    lateinit var passport10Service : Passport10Service

    @Test
    fun name() = runTest(timeout = Duration.INFINITE) {
        val checkAvailableSlots = passport10Service.containsAvailableSlots(Website.HAGUE)
    }
}