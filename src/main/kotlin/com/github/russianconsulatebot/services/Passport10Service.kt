package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.WrongCaptureSessionException
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

    suspend fun containsAvailableSlots(website: Website): Boolean {
        val userInfo = UserInfo.generateDummyUserInfo()

        when (website) {
            Website.HAGUE -> {
                val sessionInfo = retry(3) { consulateHttpClient.startSession(website.baseUrl, userInfo) }
                log.info("Got session: {}", sessionInfo)
                val calendarPath = consulateHttpClient.passToOrderPage(sessionInfo, "BIOPASSPORT")
                log.info("Got order path: {}", calendarPath)
                val hasWindows = consulateHttpClient.checkAvailableSlots(sessionInfo, calendarPath)
                log.info("Has windows {}", hasWindows)
                return hasWindows
            }
            Website.BELGUM -> {
                val sessionInfo = retry(3) { consulateHttpClient.startSession(website.baseUrl, userInfo) }
                log.info("Got session: {}", sessionInfo)
                val orderPath = consulateHttpClient.passToOrderPage(sessionInfo, "BIOPASSPORT")
                log.info("Got order path: {}", orderPath)
                val order = consulateHttpClient.parseOrder(sessionInfo, orderPath)

                // TODO confirm an order by email

                log.info("Got order {}", order)
                val calendarPath = retry(3) { consulateHttpClient.startCheckingAndOrder(website.baseUrl, order) }
                log.info("Got calendar path {}", order)
                val hasWindows = consulateHttpClient.checkAvailableSlots(sessionInfo, calendarPath)
                log.info("Has windows {}", hasWindows)
                return hasWindows
            }
        }
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
