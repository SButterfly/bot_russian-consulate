package com.github.russianconsulatebot.services

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

        val sessionInfo = consulateHttpClient.startSession(baseUrl, userInfo)
        val orderPath = consulateHttpClient.passToCalendarPage(sessionInfo, "BIOPASSPORT")
        val hasWindows = consulateHttpClient.checkAvailableSlots(sessionInfo, orderPath)
        return hasWindows
    }
}
