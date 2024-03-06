package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.WrongCaptureSessionException
import com.github.russianconsulatebot.services.dto.Order
import com.github.russianconsulatebot.services.dto.UserInfo
import com.github.russianconsulatebot.services.dto.Website
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Check availability on the passport 10.
 */
@Service
class Passport10Service(
    private val consulateHttpClient: ConsulateHttpClient,
) {
    private val log = LoggerFactory.getLogger(Passport10Service::class.java)

    suspend fun containsAvailableSlots() : Boolean {
        // TODO remove hardcoded values
        val baseUrl = Website.HAGUE.baseUrl
        val order = Order("104497", "8F71F287")

        val userInfo = UserInfo.generateDummyUserInfo()

        val sessionInfo = retry(3) { consulateHttpClient.startSession(baseUrl, userInfo) }
        log.info("Got session: {}", sessionInfo)
        val orderPath = consulateHttpClient.passToCalendarPage(sessionInfo, "BIOPASSPORT")
        log.info("Got order path: {}", orderPath)
        val hasWindows = consulateHttpClient.checkAvailableSlots(sessionInfo, orderPath)
        return hasWindows
    }

    private suspend fun <T> retry(maxAttempts: Int, function: suspend () -> T): T {
        var lastException: Exception
        var invocationNumber = 0
        do {
            try {
                return function()
            } catch (e: WrongCaptureSessionException) {
                log.warn("Retrying due to an error", e)
                lastException = e
            }
        } while (++invocationNumber < maxAttempts)
        throw lastException
    }
}
